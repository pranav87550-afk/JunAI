package com.junai.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translator)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        val sourceLang  = findViewById<Spinner>(R.id.sourceLang)
        val targetLang  = findViewById<Spinner>(R.id.targetLang)
        val inputText   = findViewById<EditText>(R.id.inputText)
        val outputText  = findViewById<TextView>(R.id.outputText)
        val charCount   = findViewById<TextView>(R.id.charCount)
        val copyButton  = findViewById<ImageButton>(R.id.copyButton)

        val langNames = languages.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, langNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sourceLang.adapter = adapter
        targetLang.adapter = adapter

        sourceLang.setSelection(0)
        targetLang.setSelection(1)

        // Character counter
        inputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val len = s?.length ?: 0
                charCount.text = "$len/5000"
                if (len > 4500) charCount.setTextColor(android.graphics.Color.parseColor("#E53935"))
                else charCount.setTextColor(android.graphics.Color.parseColor("#666666"))
            }
        })

        // Swap languages
        findViewById<ImageButton>(R.id.swapLang).setOnClickListener {
            val srcPos = sourceLang.selectedItemPosition
            val tgtPos = targetLang.selectedItemPosition
            sourceLang.setSelection(tgtPos)
            targetLang.setSelection(srcPos)
        }

        // Copy translation
        copyButton.setOnClickListener {
            val text = outputText.text.toString()
            if (text.isEmpty() || text == "Translation will appear here...") {
                Toast.makeText(this, "Nothing to copy!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
            Toast.makeText(this, "Copied! ✅", Toast.LENGTH_SHORT).show()
        }

        // Translate
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

                try {
                    val encoded = URLEncoder.encode(text, "UTF-8")
                    val url = "https://translate.googleapis.com/translate_a/single" +
                        "?client=gtx&sl=$srcCode&tl=$tgtCode&dt=t&q=$encoded"

                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .get()
                        .build()

                    client.newCall(request).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            runOnUiThread {
                                outputText.text = "Network error: ${e.message}"
                                outputText.setTextColor(android.graphics.Color.parseColor("#E53935"))
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            val body = response.body?.string()
                            runOnUiThread {
                                try {
                                    val outer = JSONArray(body)
                                    val inner = outer.getJSONArray(0)
                                    val sb = StringBuilder()
                                    for (i in 0 until inner.length()) {
                                        val part = inner.getJSONArray(i)
                                        val chunk = part.optString(0)
                                        if (chunk.isNotEmpty()) sb.append(chunk)
                                    }
                                    val translated = sb.toString().trim()

                                    if (translated.isEmpty()) {
                                        outputText.text = "Translation failed. Try again."
                                        outputText.setTextColor(android.graphics.Color.parseColor("#E53935"))
                                        return@runOnUiThread
                                    }

                                    outputText.text = translated
                                    outputText.setTextColor(android.graphics.Color.WHITE)

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
                                    outputText.text = "Error: ${e.message}"
                                    outputText.setTextColor(android.graphics.Color.parseColor("#E53935"))
                                }
                            }
                        }
                    })
                } catch (e: Exception) {
                    runOnUiThread {
                        outputText.text = "Error: ${e.message}"
                        outputText.setTextColor(android.graphics.Color.parseColor("#E53935"))
                    }
                }
            }
        }
    }
}
