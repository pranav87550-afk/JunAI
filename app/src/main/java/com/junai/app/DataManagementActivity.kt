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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DataManagementActivity : AppCompatActivity() {

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> importJsonFile(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_management)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        updateKnowledgeCount()

        findViewById<Button>(R.id.importFileButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            filePicker.launch(intent)
        }
    }

    private fun importJsonFile(uri: Uri) {
        val resultText = findViewById<TextView>(R.id.importResult)
        resultText.text = "Importing..."
        resultText.setTextColor(android.graphics.Color.WHITE)
        resultText.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val jsonString = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                inputStream?.close()

                val jsonObj = JSONObject(jsonString)
                val db = AppDatabase.getInstance(this@DataManagementActivity)
                val dao = db.knowledgeDao()

                val list = mutableListOf<KnowledgeEntity>()
                val keys = jsonObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = jsonObj.getString(key)
                    list.add(KnowledgeEntity(key.lowercase().trim(), value))
                }

                dao.insertAll(list)
                val count = dao.getCount()

                withContext(Dispatchers.Main) {
                    resultText.text = "✅ Successfully imported ${list.size} questions!\nTotal: $count questions in Jun's memory"
                    resultText.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
                    updateKnowledgeCount()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    resultText.text = "❌ Import failed! Check JSON format.\n${e.message}"
                    resultText.setTextColor(android.graphics.Color.parseColor("#E53935"))
                }
            }
        }
    }

    private fun updateKnowledgeCount() {
        CoroutineScope(Dispatchers.IO).launch {
            val count = AppDatabase.getInstance(this@DataManagementActivity)
                .knowledgeDao().getCount()
            withContext(Dispatchers.Main) {
                findViewById<TextView>(R.id.knowledgeCount).text = "$count questions stored"
            }
        }
    }
}
