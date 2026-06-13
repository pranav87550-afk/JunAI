package com.junai.app

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
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

        // Default: English -> Hindi
        sourceLang.setSelection(0)
        targetLang.setSelection(1)

        // Swap button
        findViewById<ImageButton>(R.id.swapLang).setOnClickListener {
            val srcPos = sourceLang.selectedItemPosition
            val tgtPos = targetLang.selectedItemPosition
            sourceLang.setSelection(tgtPos)
            targetLang.setSelection(srcPos)
        }

        // Translate button
        findViewById<Button>(R.id.translateButton).setOnClickListener {
            val text = inputText.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Enter text to translate", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val srcCode = languages[sourceLang.selectedItemPosition].second
            val tgtCode = languages[targetLang.selectedItemPosition].second

            outputText.text = "Translating..."
            outputText.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))

            val url = "https://api.mymemory.translated.net/get?q=${java.net.URLEncoder.encode(text, "UTF-8")}&langpair=$srcCode|$tgtCode"

            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        outputText.text = "Translation failed. Check internet connection."
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
