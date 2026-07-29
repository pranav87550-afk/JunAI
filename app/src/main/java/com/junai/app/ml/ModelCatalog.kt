package com.junai.app.ml

/**
 * Central catalog of every on-device AI model JunAI needs. This replaces
 * the old approach of bundling model files inside app/src/main/assets/
 * (which required GitHub Actions to pull them from a private Release at
 * BUILD time — see build.yml history). Instead, the APK ships with NO
 * model files, and each one is downloaded to the device at RUNTIME by
 * ModelDownloadManager/ModelDownloadWorker, the first time the app needs
 * it (or when the user explicitly taps Download on the Models screen).
 *
 * WHY Hugging Face instead of GitHub Releases: our GitHub repo is
 * private, and GitHub Actions authenticates release-asset downloads
 * with GITHUB_TOKEN — a secret that only exists inside the Actions
 * runner. A phone in someone's pocket has no such token, and embedding
 * one in the APK would hand out real repo access to anyone who
 * decompiled it. Hugging Face model repos are public and need no auth
 * for a plain download URL, which is exactly what an installed app
 * needs. (This is the same approach PocketPal AI uses for its GGUF
 * models — public hosting, no token, direct HTTPS GET.)
 *
 * SETUP NEEDED (one-time, Pranav): create a public model repo at
 * https://huggingface.co/new — e.g. "junai-models" — and upload the 3
 * files below under it. Then replace HF_USERNAME below with the actual
 * Hugging Face username/org the repo lives under.
 */
object ModelCatalog {

    // Live at https://huggingface.co/Jun1510/junai-models
    private const val HF_USERNAME = "Jun1510"
    private const val HF_REPO = "junai-models"

    // Public (not private) because KnowledgeFile.downloadUrl below also
    // needs it, and ModelDownloadWorker builds per-file URLs for the
    // knowledge pack the same way.
    fun hfDownloadUrl(filename: String): String =
        "https://huggingface.co/$HF_USERNAME/$HF_REPO/resolve/main/$filename?download=true"

    enum class ModelId {
        EMBEDDING_GEMMA,
        FUNCTION_GEMMA,
        // LiteRT-LM's QWEN3_CHAT entry (and ChatEngine.kt, which used it)
        // was removed here — fully replaced by the llama.cpp/GGUF path
        // below, which is faster (~1-2s vs ~71s per response) and no
        // longer marked "testing" since it's the only chat engine now.
        QWEN3_CHAT_GGUF,
        // RAG knowledge base — see KnowledgeFile/KNOWLEDGE_FILES below.
        // Unlike the three model entries above, this is a *bundle* of
        // many small JSON files (one per domain) rather than a single
        // file, so it needs KnowledgeFile/knowledgeFiles on ModelInfo
        // instead of just fileName/downloadUrl.
        KNOWLEDGE_PACK,
    }

    /**
     * One domain's worth of curated RAG facts, hosted at
     * huggingface.co/Jun1510/junai-models/knowledge/<remoteFileName>.
     * @property domain Stable key (matches the "domain" field inside
     * the JSON file itself). Never rename without a migration.
     * @property remoteFileName Path *within* the HF repo, e.g.
     * "knowledge/ai_ml.json" — also used as the path under
     * filesDir/models/ once downloaded, so remote and local layout
     * mirror each other with zero extra mapping.
     * @property displayLabel Human-friendly name shown in the download
     * notification (e.g. "Downloading GK knowledge...") — separate from
     * `domain` since `domain` is a code-facing key (snake_case, matches
     * JSON) while this is what the user actually reads.
     */
    data class KnowledgeFile(
        val domain: String,
        val remoteFileName: String,
        val displayLabel: String,
    ) {
        val downloadUrl: String get() = hfDownloadUrl(remoteFileName)
    }

    /**
     * All 13 domain files for the RAG knowledge pack v1 (~195 entries,
     * ~110 KB total — see KnowledgeBase.kt for how these get loaded and
     * embedded once downloaded). Add more domains later just by
     * appending entries here — no other code needs to change as long as
     * the uploaded JSON file matches the {id, domain, topic, content}
     * shape KnowledgeBase.kt expects.
     */
    val KNOWLEDGE_FILES: List<KnowledgeFile> = listOf(
        KnowledgeFile("gk", "knowledge/gk.json", "GK"),
        KnowledgeFile("tech", "knowledge/tech.json", "Tech"),
        KnowledgeFile("medical", "knowledge/medical.json", "Health Care"),
        KnowledgeFile("career", "knowledge/career.json", "Career"),
        KnowledgeFile("finance", "knowledge/finance.json", "Finance"),
        KnowledgeFile("lifestyle", "knowledge/lifestyle.json", "Lifestyle"),
        KnowledgeFile("cooking", "knowledge/cooking.json", "Cooking"),
        KnowledgeFile("apps_games", "knowledge/apps_games.json", "Apps & Games"),
        KnowledgeFile("films", "knowledge/films.json", "Films"),
        KnowledgeFile("education", "knowledge/education.json", "Education"),
        KnowledgeFile("programming", "knowledge/programming.json", "Programming"),
        KnowledgeFile("ai_ml", "knowledge/ai_ml.json", "AI & ML"),
        KnowledgeFile("webdev", "knowledge/webdev.json", "Web Dev"),
    )

    /**
     * @property id Stable identifier, used as the WorkManager tag and
     * ModelStateStore key — never rename these without a migration.
     * @property displayName Shown on the Models screen.
     * @property fileName Name of the file both on Hugging Face and once
     * downloaded — kept identical on both ends so there's no separate
     * "remote name vs local name" mapping to keep in sync.
     * @property approxSizeBytes For showing "~600 MB" etc. before
     * download starts, when the server doesn't give us a size up front.
     * Rough numbers — fine to eyeball these against the real files.
     * @property description One line explaining what this model is for,
     * shown under its name on the Models screen.
     */
    data class ModelInfo(
        val id: ModelId,
        val displayName: String,
        val fileName: String,
        val approxSizeBytes: Long,
        val description: String,
        // Non-null ONLY for KNOWLEDGE_PACK — signals to
        // ModelDownloadWorker/ModelDownloadManager that this entry is a
        // multi-file bundle rather than a single downloadable file, and
        // that fileName/downloadUrl above should be ignored for it.
        val knowledgeFiles: List<KnowledgeFile>? = null,
        // Non-null when a model isn't hosted on the Jun1510/junai-models
        // HF repo — e.g. Universal Sentence Encoder, which Google serves
        // directly from storage.googleapis.com. Avoids needing to
        // manually re-upload/re-host every third-party model file.
        val downloadUrlOverride: String? = null,
    ) {
        val downloadUrl: String get() = downloadUrlOverride ?: hfDownloadUrl(fileName)
    }

    val ALL: List<ModelInfo> = listOf(
        ModelInfo(
            id = ModelId.EMBEDDING_GEMMA,
            displayName = "Universal Sentence Encoder",
            fileName = "universal_sentence_encoder.tflite",
            // Google's own file, hosted directly — EmbeddingGemma (any
            // quantization variant) turned out to be fundamentally
            // unsupported by MediaPipe's TextEmbedder task (confirmed:
            // google-ai-edge/mediapipe issue #6217 is literally a
            // feature request asking for that support, still open).
            // Universal Sentence Encoder is Google's own documented
            // recommendation for TextEmbedder specifically. Trade-off:
            // it's primarily English-trained, weaker than EmbeddingGemma
            // would have been on Hinglish — the multilingual USE variant
            // was tried and rejected too (needs a Flex-ops delegate
            // TextEmbedder doesn't support; see mediapipe issue #4929).
            downloadUrlOverride = "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite",
            approxSizeBytes = 25L * 1024 * 1024, // rough — actual size gets verified from Content-Length on first download regardless
            description = "Semantic matching for trained commands",
        ),
        ModelInfo(
            id = ModelId.FUNCTION_GEMMA,
            displayName = "FunctionGemma 270M",
            fileName = "functiongemma-270M-it.task",
            approxSizeBytes = 284L * 1024 * 1024,
            description = "Understands app actions and commands",
        ),
        ModelInfo(
            id = ModelId.QWEN3_CHAT_GGUF,
            displayName = "Qwen3 0.6B",
            fileName = "Qwen3-0.6B-Q4_K_M.gguf",
            approxSizeBytes = 397L * 1024 * 1024,
            description = "General chat and understanding",
        ),
        ModelInfo(
            id = ModelId.KNOWLEDGE_PACK,
            displayName = "Knowledge Pack",
            // Not a real remote file — knowledgeFiles below is what
            // ModelDownloadWorker actually iterates for this entry.
            fileName = "knowledge",
            approxSizeBytes = 110L * 1024, // ~110 KB total across all 13 files
            description = "General knowledge facts (GK, tech, health, and more) for accurate answers",
            knowledgeFiles = KNOWLEDGE_FILES,
        ),
    )

    fun byId(id: ModelId): ModelInfo =
        ALL.first { it.id == id }

    /**
     * Every model lands in the same app-private subfolder, one file
     * each, named exactly as on Hugging Face. Using getFilesDir()
     * (not external/cache storage) because these need to survive
     * "clear cache" and don't need to be visible to other apps —
     * same directory ChatEngine already copies the Qwen3 file into
     * today, just now it's the download destination directly instead
     * of an assets-to-filesDir copy step.
     */
    fun localDirName(): String = "models"
}
