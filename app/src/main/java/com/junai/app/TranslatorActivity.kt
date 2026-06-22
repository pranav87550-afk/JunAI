package com.junai.app

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class TranslatorActivity : AppCompatActivity() {

    private val languages = listOf(
        "English" to "en",
        "Hindi" to "hi",
        "Spanish" to "es",
        "French" to "fr",
        "German" to "de",
        "Japanese" to "ja",
        "Korean" to "ko",
        "Chinese" to "zh",
        "Arabic" to "ar",
        "Portuguese" to "pt",
        "Russian" to "ru",
        "Italian" to "it",
        "Turkish" to "tr",
        "Bengali" to "bn",
        "Urdu" to "ur"
    )

    // Free public LibreTranslate mirrors
    private val apiMirrors = listOf(
        "https://libretranslate.com/translate",
        "https://translate.argosopentech.com/translate",
        "https://libretranslate.de/translate"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translator)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        val sourceLang = findViewById<Spinner>(R.id.sourceLang)
        val targetLang = findViewById<Spinner>(R.id.targetLang)
        val inputText = findViewById<EditText>(R.id.inputText)
        val outputText = findViewById<TextView>(R.id.outputText)

        val langNames = languages.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, langNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sourceLang.adapter = adapter
        targetLang.adapter = adapter

        sourceLang.setSelection(0)
        targetLang.setSelection(1)

        findViewById<ImageButton>(R.id.swapLang).setOnClickListener {
            val srcPos = sourceLang.selectedItemPosition
            val tgtPos = targetLang.selectedItemPosition
            sourceLang.setSelection(tgtPos)
            targetLang.setSelection(srcPos)
        }

        findViewById<Button>(R.id.translateButton).setOnClickListener {
            val text = inputText.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Enter text to translate", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val srcCode = languages[sourceLang.selectedItemPosition].second
            val tgtCode = languages[targetLang.selectedItemPosition].second

            if (srcCode == tgtCode) {
                outputText.text = text
                outputText.setTextColor(android.graphics.Color.WHITE)
                return@setOnClickListener
            }

            // Check cache first
            val cacheKey = "translate:${srcCode}_${tgtCode}:${text.lowercase().trim()}"
            CoroutineScope(Dispatchers.IO).launch {
                val cached = AppDatabase.getInstance(this@TranslatorActivity)
                    .knowledgeDao()
                    .getAnswer(cacheKey)

                if (cached != null) {
                    runOnUiThread {
                        outputText.text = cached
                        outputText.setTextColor(android.graphics.Color.WHITE)
                        Toast.makeText(this@TranslatorActivity, "Cached ⚡", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                runOnUiThread {
                    outputText.text = "Translating..."
                    outputText.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                }

                tryTranslateWithMirrors(text, srcCode, tgtCode, cacheKey, outputText, 0)
            }
        }
    }

    private fun tryTranslateWithMirrors(
        text: String,
        srcCode: String,
        tgtCode: String,
        cacheKey: String,
        outputText: TextView,
        mirrorIndex: Int
    ) {
        if (mirrorIndex >= apiMirrors.size) {
            runOnUiThread {
                outputText.text = "Translation failed. Check internet connection."
                outputText.setTextColor(android.graphics.Color.parseColor("#E53935"))
            }
            return
        }

        val url = apiMirrors[mirrorIndex]
        val json = JSONObject().apply {
            put("q", text)
            put("source", srcCode)
            put("target", tgtCode)
            put("format", "text")
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Try next mirror
                tryTranslateWithMirrors(text, srcCode, tgtCode, cacheKey, outputText, mirrorIndex + 1)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                try {
                    val translated = JSONObject(responseBody ?: "")
                        .getString("translatedText")

                    runOnUiThread {
                        outputText.text = translated
                        outputText.setTextColor(android.graphics.Color.WHITE)
                    }

                    // Save to cache
                    CoroutineScope(Dispatchers.IO).launch {
                        AppDatabase.getInstance(this@TranslatorActivity)
                            .knowledgeDao()
                            .insert(KnowledgeEntity(
                                question = cacheKey,
                                answer = translated,
                                category = "Translation"
                            ))
                    }

                } catch (e: Exception) {
                    // This mirror gave bad response — try next
                    tryTranslateWithMirrors(text, srcCode, tgtCode, cacheKey, outputText, mirrorIndex + 1)
                }
            }
        })
    }
}
