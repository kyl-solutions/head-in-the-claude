package com.kylsolutions.kylidescope.ui.files

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kylsolutions.kylidescope.KylidescopeSessionManager
import com.kylsolutions.kylidescope.R
import com.kylsolutions.kylidescope.RelayClient
import kotlinx.coroutines.launch

/**
 * File content viewer — displays file with line numbers and monospace text.
 * "Ask Claude" button returns the file path to ChatActivity.
 */
class FileViewerActivity : AppCompatActivity() {

    private lateinit var backButton: ImageButton
    private lateinit var fileNameTitle: TextView
    private lateinit var askClaudeButton: ImageButton
    private lateinit var filePathSubtitle: TextView
    private lateinit var lineNumbers: TextView
    private lateinit var fileContent: TextView

    private var filePath: String = ""
    private var fileName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_viewer)

        filePath = intent.getStringExtra("filePath") ?: run { finish(); return }
        fileName = intent.getStringExtra("fileName") ?: filePath.substringAfterLast("/")

        initViews()
        loadFileContent()
    }

    private fun initViews() {
        backButton = findViewById(R.id.backButton)
        fileNameTitle = findViewById(R.id.fileNameTitle)
        askClaudeButton = findViewById(R.id.askClaudeButton)
        filePathSubtitle = findViewById(R.id.filePathSubtitle)
        lineNumbers = findViewById(R.id.lineNumbers)
        fileContent = findViewById(R.id.fileContent)

        fileNameTitle.text = fileName
        filePathSubtitle.text = filePath.replace("/Users/kylsolutions/", "~/")

        backButton.setOnClickListener { finish() }

        askClaudeButton.setOnClickListener {
            val result = Intent()
            result.putExtra("askClaudePath", filePath)
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun loadFileContent() {
        val sessionMgr = KylidescopeSessionManager(this)
        val client = RelayClient(sessionMgr.relayUrl, sessionMgr.authToken)

        // Show loading state
        fileContent.text = "Loading…"
        fileContent.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        lineNumbers.text = ""

        lifecycleScope.launch {
            val content = client.readFile(filePath)
            runOnUiThread {
                if (content != null) {
                    displayFile(content.content)
                } else {
                    fileContent.text = "Failed to load file"
                    fileContent.setTextColor(ContextCompat.getColor(this@FileViewerActivity, R.color.error_red))
                }
            }
        }
    }

    private fun displayFile(text: String) {
        val lines = text.lines()
        val lineCount = lines.size
        val lineNumWidth = lineCount.toString().length

        // Build line numbers (right-aligned, padded)
        val nums = StringBuilder()
        for (i in 1..lineCount) {
            nums.append(i.toString().padStart(lineNumWidth))
            if (i < lineCount) nums.append("\n")
        }

        lineNumbers.text = nums.toString()
        fileContent.text = text
        fileContent.setTextColor(ContextCompat.getColor(this, R.color.text_primary))

        // Show the subtitle with file size
        val sizeStr = when {
            text.length > 1024 * 1024 -> "${text.length / (1024 * 1024)}MB"
            text.length > 1024 -> "${text.length / 1024}KB"
            else -> "${text.length}B"
        }
        filePathSubtitle.text = "${filePath.replace("/Users/kylsolutions/", "~/")}  ($lineCount lines, $sizeStr)"
    }
}
