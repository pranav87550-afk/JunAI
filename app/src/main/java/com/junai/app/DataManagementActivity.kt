package com.junai.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class DataManagementActivity : AppCompatActivity() {

    private val PREFS = "knowledge_prefs"
    private val KEY = "knowledge_list"

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                importJsonFile(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_management)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        updateKnowledgeCount()

        findViewById<Button>(R.id.importFileButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "application/json"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            filePicker.launch(intent)
        }
    }

    private fun importJsonFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val jsonString = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            inputStream?.close()

            val jsonObj = JSONObject(jsonString)
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val existing = prefs.getString(KEY, "{}") ?: "{}"
            val existingObj = JSONObject(existing)

            var importCount = 0
            val keys = jsonObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = jsonObj.getString(key)
                existingObj.put(key.lowercase().trim(), value)
                importCount++
            }

            prefs.edit().putString(KEY, existingObj.toString()).apply()

            // Show result
            val resultText = findViewById<TextView>(R.id.importResult)
            resultText.text = "✅ Successfully imported $importCount questions!"
            resultText.visibility = View.VISIBLE

            updateKnowledgeCount()

        } catch (e: Exception) {
            val resultText = findViewById<TextView>(R.id.importResult)
            resultText.text = "❌ Import failed! Check JSON format."
            resultText.setTextColor(android.graphics.Color.parseColor("#E53935"))
            resultText.visibility = View.VISIBLE
        }
    }

    private fun updateKnowledgeCount() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val json = prefs.getString(KEY, "{}") ?: "{}"
        val count = JSONObject(json).length()
        findViewById<TextView>(R.id.knowledgeCount).text = "$count questions stored"
    }
}
