package com.junai.app.ml

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * FunctionCallEngine — on-device natural-language-to-action routing via
 * FunctionGemma 270M (assets/functiongemma-270M-it.task).
 *
 * WHY THIS EXISTS (the router, per Pranav's "option 1" decision): this is
 * deliberately NOT a general chat model. FunctionGemma is fine-tuned
 * specifically to turn instructions into function calls, and calling it
 * on casual chat ("kaisa hai tu") risks it inventing a function call that
 * was never wanted. So this is only ever invoked from ChatIntentHandler's
 * UNKNOWN branch (i.e. the existing rule-based IntentDetector already
 * couldn't classify the message), AND only when isLikelyAction() below
 * also agrees the text looks command-shaped — never on every message.
 *
 * FUNCTION SCHEMA: deliberately kept to the exact intent vocabulary
 * TrainedCommandHandler.handle() already knows how to execute (see that
 * file) — OPEN_APP, CALL_CONTACT, PLAY_MUSIC, PAUSE_MUSIC, SET_REMINDER,
 * CREATE_NOTE, SEARCH_WEB, SHOW_SETTINGS, TELL_TIME, TELL_DATE,
 * TELL_BATTERY. This means a successful FunctionCallEngine result can be
 * handed straight to the same execution path a trained command already
 * uses — no new execution logic needed, this only widens what can reach
 * that existing path.
 *
 * PROMPT FORMAT: plain text (system instructions + user message), no
 * manual chat-template special tokens — see buildPrompt()'s own doc
 * comment for why a previous version that manually added
 * <bos>/<start_of_turn> tokens was actually a crash-causing bug, not a
 * deliberate choice worth keeping.
 *
 * UNVERIFIED UNTIL YOU TEST: the base model's zero-shot accuracy on
 * Google's own benchmark was ~58% — expect some wrong/missed calls
 * before fine-tuning on your macro data. If parseJsonResponse() keeps
 * failing (malformed JSON, wrong function names) log it and tell me —
 * that's the known accuracy gap this size model has out of the box.
 */
object FunctionCallEngine {

    @Volatile
    private var appContext: Context? = null

    private const val TAG = "FunctionCallEngine"

    data class FunctionCall(val intent: String, val target: String)

    // Keep this list in exact sync with the `when (intent)` branches in
    // TrainedCommandHandler.handle() — a function name here that isn't a
    // real branch there just falls into that function's "else" (a polite
    // "samajh nahi aaya" reply), so it fails safely, but keeping them in
    // sync means fewer of those.
    private const val FUNCTION_DECLARATIONS = """
[
  {"name": "OPEN_APP", "description": "Open an app by name", "parameters": {"target": "app name, e.g. whatsapp, chrome, youtube"}},
  {"name": "CALL_CONTACT", "description": "Call a contact by name", "parameters": {"target": "contact name"}},
  {"name": "PLAY_MUSIC", "description": "Open the music player and start playback", "parameters": {}},
  {"name": "PAUSE_MUSIC", "description": "Pause current music playback", "parameters": {}},
  {"name": "SET_REMINDER", "description": "Open the reminder creation screen", "parameters": {}},
  {"name": "CREATE_NOTE", "description": "Open the note creation screen", "parameters": {}},
  {"name": "SEARCH_WEB", "description": "Search the web for a query", "parameters": {"target": "search query"}},
  {"name": "SHOW_SETTINGS", "description": "Open app settings screen", "parameters": {}},
  {"name": "TELL_TIME", "description": "Tell the current time", "parameters": {}},
  {"name": "TELL_DATE", "description": "Tell today's date", "parameters": {}},
  {"name": "TELL_BATTERY", "description": "Tell current battery level", "parameters": {}}
]
"""

    private val SYSTEM_PROMPT = """
You are a function-calling assistant for an Android app. Given a user's
message, respond with ONLY a single JSON object choosing the best
matching function: {"name": "<FUNCTION_NAME>", "target": "<value or empty string>"}.
If nothing matches, respond with {"name": "NONE", "target": ""}.
Do not include any explanation, only the JSON object.

IMPORTANT: only call a function when the user wants the action performed
RIGHT NOW. If the user is instead asking to be TAUGHT, EXPLAINED, or
INFORMED about how something works — even if it mentions the same words
as a function (app names, "open", "call", etc.) — respond with
{"name": "NONE", "target": ""} instead. For example:
- "whatsapp khol do" -> call OPEN_APP (an actual instruction)
- "whatsapp kholna sikhao" -> NONE (asking to be taught, not to open it)
- "mummy ko call karo" -> call CALL_CONTACT (an actual instruction)
- "call kaise karte hain" -> NONE (asking how calling works, not to call anyone)

Available functions:
$FUNCTION_DECLARATIONS
""".trimIndent()

    @Volatile
    private var llmInference: LlmInference? = null

    @Volatile
    private var initFailed = false

    suspend fun init(context: Context) {
        if (llmInference != null || initFailed) return
        withContext(Dispatchers.IO) {
            if (llmInference != null || initFailed) return@withContext
            // Model now lives in filesDir/models/ (downloaded via the
            // Models screen) instead of assets/. LlmInferenceOptions
            // .setModelPath() has always officially expected an
            // absolute filesystem path per MediaPipe's own docs/samples
            // — using the plain filename here before technically wasn't
            // the documented usage, it just happened to resolve because
            // the file was bundled in assets/. This is the correct form.
            if (!ModelDownloadManager.isDownloaded(context, ModelCatalog.ModelId.FUNCTION_GEMMA)) {
                android.util.Log.w(TAG, "FunctionGemma model not downloaded yet — visit the Models screen.")
                return@withContext
            }
            val modelFile = ModelDownloadManager.localPathFor(context, ModelCatalog.ModelId.FUNCTION_GEMMA)
            try {
                appContext = context.applicationContext
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(256)
                    .build()
                Breadcrumb.log(context, "FunctionCallEngine: about to call LlmInference.createFromOptions (native)")
                llmInference = LlmInference.createFromOptions(context.applicationContext, options)
                Breadcrumb.log(context, "FunctionCallEngine: createFromOptions returned OK")
            } catch (e: Exception) {
                initFailed = true
                android.util.Log.e(TAG, "FunctionGemma failed to load: ${e.message}", e)
            }
        }
    }

    fun isReady(): Boolean = llmInference != null

    /**
     * Cheap heuristic gate — NOT a replacement for IntentDetector, just a
     * pre-filter so we don't burn a ~50-150ms, several-hundred-MB-RAM
     * inference on obvious chit-chat that reached UNKNOWN for other
     * reasons (typos in a greeting, an unrecognized joke request, etc).
     * Deliberately conservative in the "let it through" direction —
     * false positives here just mean FunctionGemma returns NONE and we
     * fall through anyway, which is cheap-ish; false negatives mean a
     * real command silently skips the model entirely, which is worse.
     */
    fun isLikelyAction(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.isEmpty()) return false
        // Question-shaped chit-chat ("kaisa hai", "kya haal hai") is
        // rarely a command — but don't over-filter, a question CAN be a
        // command too ("kya WhatsApp khol sakti ho") so this only
        // excludes the shortest, most obviously conversational cases.
        val chitChatStarts = listOf("kaisa", "kaisi", "kya haal", "how are", "hi ", "hello", "hey")
        if (chitChatStarts.any { t.startsWith(it) } && t.split(" ").size <= 4) return false
        return true
    }

    /**
     * Runs FunctionGemma on `text`, returns a FunctionCall if it named a
     * real function with reasonable confidence-by-parsing (valid JSON,
     * name != "NONE"), else null. Null just means "this engine had
     * nothing" — caller should fall through to existing behavior, not
     * treat it as an error.
     */
    suspend fun tryInterpret(text: String): FunctionCall? {
        val engine = llmInference ?: return null
        val ctx = appContext
        return withContext(Dispatchers.Default) {
            try {
                val prompt = buildPrompt(text)
                if (ctx != null) Breadcrumb.log(ctx, "FunctionCallEngine: about to call generateResponse() (native)")
                val raw = engine.generateResponse(prompt)
                if (ctx != null) Breadcrumb.log(ctx, "FunctionCallEngine: generateResponse() returned OK")
                parseJsonResponse(raw)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "tryInterpret failed for input, falling back to null: ${e.message}")
                null
            }
        }
    }

    /**
     * BUGFIX (this replaced a version that manually built the full
     * <bos><start_of_turn>developer...<start_of_turn>model\n turn
     * structure by hand): Google's own .task bundler
     * (mediapipe.tasks.python.genai.bundler.BundleConfig) bakes a
     * prompt_prefix/prompt_suffix directly into the .task file — by
     * default "<start_of_turn>user\n" / "<end_of_turn>\n<start_of_turn>model\n"
     * — which MediaPipe's generateResponse() applies automatically
     * around whatever text is passed in. The old code was ALSO adding
     * its own <bos>/<start_of_turn>/<end_of_turn> tokens on top of that,
     * so the actual tokenized input ended up double-wrapped, with a
     * <bos> token stranded mid-sequence instead of at the very start.
     * That's a strong match for a documented class of MediaPipe crash
     * (SIGSEGV inside SignatureRunner::AllocateTensors() on the first
     * generateResponse() call — see google-ai-edge/mediapipe issues
     * #6042/#5564/#6083) attributed to malformed/mismatched prompt
     * structure. This version sends plain text and lets the .task
     * file's own baked-in template do the wrapping, matching every
     * official MediaPipe usage example.
     */
    private fun buildPrompt(userText: String): String = buildString {
        append(SYSTEM_PROMPT)
        append("\n\nUser message: ")
        append(userText)
    }

    private fun parseJsonResponse(raw: String): FunctionCall? {
        // Model output can sometimes wrap JSON in markdown fences or add
        // stray whitespace/newlines despite the "only JSON" instruction —
        // small models don't always follow formatting instructions
        // perfectly. Strip the common cases before parsing.
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val jsonStart = cleaned.indexOf('{')
        val jsonEnd = cleaned.lastIndexOf('}')
        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd < jsonStart) return null
        return try {
            val obj = JSONObject(cleaned.substring(jsonStart, jsonEnd + 1))
            val name = obj.optString("name", "NONE")
            if (name.isEmpty() || name == "NONE") return null
            FunctionCall(intent = name, target = obj.optString("target", ""))
        } catch (e: Exception) {
            android.util.Log.w(TAG, "parseJsonResponse: model output wasn't valid JSON — raw: $raw")
            null
        }
    }

    fun close() {
        llmInference?.close()
        llmInference = null
    }
}
