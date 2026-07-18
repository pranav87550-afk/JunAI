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

    private fun hfDownloadUrl(filename: String): String =
        "https://huggingface.co/$HF_USERNAME/$HF_REPO/resolve/main/$filename?download=true"

    enum class ModelId {
        EMBEDDING_GEMMA,
        FUNCTION_GEMMA,
        QWEN3_CHAT,
        // llama.cpp/GGUF migration — separate from QWEN3_CHAT (LiteRT-LM)
        // on purpose. Same underlying model (Qwen3 0.6B), different
        // runtime/quant format, downloaded to a different file. Keeping
        // both ModelIds lets the app run either engine side-by-side for
        // comparison instead of an all-or-nothing swap.
        QWEN3_CHAT_GGUF,
    }

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
    ) {
        val downloadUrl: String get() = hfDownloadUrl(fileName)
    }

    val ALL: List<ModelInfo> = listOf(
        ModelInfo(
            id = ModelId.EMBEDDING_GEMMA,
            displayName = "EmbeddingGemma 300M",
            fileName = "embeddinggemma-300M_seq1024_mixed-precision.tflite",
            approxSizeBytes = 183L * 1024 * 1024,
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
            id = ModelId.QWEN3_CHAT,
            displayName = "Qwen3 0.6B",
            fileName = "qwen3_0_6b_mixed_int4.litertlm",
            approxSizeBytes = 498L * 1024 * 1024,
            description = "General chat and understanding",
        ),
        ModelInfo(
            id = ModelId.QWEN3_CHAT_GGUF,
            displayName = "Qwen3 0.6B (GGUF)",
            fileName = "Qwen3-0.6B-Q4_K_M.gguf",
            approxSizeBytes = 397L * 1024 * 1024,
            description = "General chat and understanding — llama.cpp runtime (testing)",
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
