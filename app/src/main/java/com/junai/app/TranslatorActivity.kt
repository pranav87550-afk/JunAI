package com.junai.app

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
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

    private lateinit var learningRepo: LearningRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translator)

        learningRepo = LearningRepository(this)

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
            val srcName = languages[sourceLang.selectedItemPosition].first
            val tgtName = languages[targetLang.selectedItemPosition].first

            // Pehle cache check karo
            val cacheKey = "translate:${srcCode}_${tgtCode}:${text.lowercase().trim()}"
            CoroutineScope(Dispatchers.IO).launch {
                val cached = AppDatabase.getInstance(this@TranslatorActivity)
                    .knowledgeDao()
                    .getAnswer(cacheKey)

                if (cached != null) {
                    runOnUiThread {
                        outputText.text = cached
                        outputText.setTextColor(android.graphics.Color.WHITE)
                        Toast.makeText(this@TranslatorActivity, "Cached translation ⚡", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Cache miss — API call karo
                runOnUiThread {
                    outputText.text = "Translating..."
                    outputText.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                }

                val url = "https://api.mymemory.translated.net/get?q=${java.net.URLEncoder.encode(text, "UTF-8")}&langpair=$srcCode|$tgtCode"
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread {
                            outputText.text = "Translation failed. Check internet."
                            outputText.setTextColor(android.graphics.Color.parseColor("#E53935"))
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        runOnUiThread {
                            try {
                                val json = JSONObject(body ?: "")
                                val translated = json.getJSONObject("responseData")
                                    .getString("translatedText")

                                outputText.text = translated
                                outputText.setTextColor(android.graphics.Color.WHITE)

                                // Cache mein save karo
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
                                outputText.text = "Error parsing response."
                                outputText.setTextColor(android.graphics.Color.parseColor("#E53935"))
                            }
                        }
                    }
                })
            }
        }
    }
}
