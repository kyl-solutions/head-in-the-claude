package com.kylsolutions.hitc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/**
 * Translation screen for hit(C).
 * Supports English ↔ Zulu and English ↔ Sesotho translation.
 * Uses Claude API via relay or direct connection.
 */
class TranslateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET_LANGUAGE = "target_language"
    }

    enum class Language(val code: String, val displayName: String) {
        ENGLISH("en", "English"),
        ZULU("zu", "isiZulu"),
        SESOTHO("st", "Sesotho")
    }

    // Views
    private lateinit var backButton: ImageView
    private lateinit var statusIndicator: TextView
    private lateinit var sourceLangLabel: TextView
    private lateinit var targetLangLabel: TextView
    private lateinit var swapButton: ImageView
    private lateinit var sourceText: EditText
    private lateinit var outputText: TextView
    private lateinit var outputScroll: ScrollView
    private lateinit var translateButton: Button
    private lateinit var pasteButton: TextView
    private lateinit var clearButton: TextView
    private lateinit var copyButton: TextView

    // State
    private var sourceLanguage = Language.ENGLISH
    private var targetLanguage = Language.ZULU
    private var isTranslating = false
    private var translationJob: Job? = null

    // API client (reuse from main)
    private lateinit var apiKeyManager: ApiKeyManager
    private var claudeClient: AnthropicClient? = null

    // Clipboard
    private lateinit var clipboard: ClipboardManager

    // Colors
    private var colorOrange = 0
    private var colorGreen = 0
    private var colorRed = 0
    private var colorTextDim = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translate)

        initColors()
        initViews()
        initClickListeners()
        initClaudeClient()

        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // Check if launched with a specific target language
        val targetLang = intent.getStringExtra(EXTRA_TARGET_LANGUAGE)
        if (targetLang == "ZULU") {
            targetLanguage = Language.ZULU
        } else if (targetLang == "SESOTHO") {
            targetLanguage = Language.SESOTHO
        }
        updateLanguageLabels()
    }

    override fun onDestroy() {
        translationJob?.cancel()
        super.onDestroy()
    }

    private fun initColors() {
        colorOrange = ContextCompat.getColor(this, R.color.claude_orange)
        colorGreen = ContextCompat.getColor(this, R.color.success_green)
        colorRed = ContextCompat.getColor(this, R.color.error_red)
        colorTextDim = ContextCompat.getColor(this, R.color.text_dim)
    }

    private fun initViews() {
        backButton = findViewById(R.id.backButton)
        statusIndicator = findViewById(R.id.statusIndicator)
        sourceLangLabel = findViewById(R.id.sourceLangLabel)
        targetLangLabel = findViewById(R.id.targetLangLabel)
        swapButton = findViewById(R.id.swapButton)
        sourceText = findViewById(R.id.sourceText)
        outputText = findViewById(R.id.outputText)
        outputScroll = findViewById(R.id.outputScroll)
        translateButton = findViewById(R.id.translateButton)
        pasteButton = findViewById(R.id.pasteButton)
        clearButton = findViewById(R.id.clearButton)
        copyButton = findViewById(R.id.copyButton)
    }

    private fun initClickListeners() {
        backButton.setOnClickListener {
            hapticFeedback(it)
            finish()
        }

        swapButton.setOnClickListener {
            hapticFeedback(it)
            swapLanguages()
        }

        targetLangLabel.setOnClickListener {
            hapticFeedback(it)
            showLanguagePicker()
        }

        translateButton.setOnClickListener {
            hapticFeedback(it)
            performTranslation()
        }

        pasteButton.setOnClickListener {
            hapticFeedback(it)
            pasteFromClipboard()
        }

        clearButton.setOnClickListener {
            hapticFeedback(it)
            sourceText.text.clear()
            outputText.text = ""
        }

        copyButton.setOnClickListener {
            hapticFeedback(it)
            copyToClipboard()
        }
    }

    private fun initClaudeClient() {
        apiKeyManager = ApiKeyManager(this)
        if (apiKeyManager.hasApiKey()) {
            val apiKey = apiKeyManager.getApiKey()!!
            val model = apiKeyManager.getModel()
            claudeClient = AnthropicClient(apiKey, model)
            statusIndicator.text = "Ready"
            statusIndicator.setTextColor(colorGreen)
        } else {
            statusIndicator.text = "No API key"
            statusIndicator.setTextColor(colorRed)
            translateButton.isEnabled = false
        }
    }

    private fun updateLanguageLabels() {
        sourceLangLabel.text = when (sourceLanguage) {
            Language.ENGLISH -> getString(R.string.lang_english)
            Language.ZULU -> getString(R.string.lang_zulu)
            Language.SESOTHO -> getString(R.string.lang_sesotho)
        }

        targetLangLabel.text = when (targetLanguage) {
            Language.ENGLISH -> getString(R.string.lang_english)
            Language.ZULU -> getString(R.string.lang_zulu)
            Language.SESOTHO -> getString(R.string.lang_sesotho)
        }
    }

    private fun swapLanguages() {
        val temp = sourceLanguage
        sourceLanguage = targetLanguage
        targetLanguage = temp
        updateLanguageLabels()

        // Swap text content if there's output
        val currentOutput = outputText.text.toString()
        if (currentOutput.isNotBlank() && currentOutput != getString(R.string.translate_hint_output)) {
            val currentSource = sourceText.text.toString()
            sourceText.setText(currentOutput)
            outputText.text = currentSource
        }
    }

    private fun showLanguagePicker() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_language_picker, null)

        val zuluOption = view.findViewById<TextView>(R.id.optionZulu)
        val sesothoOption = view.findViewById<TextView>(R.id.optionSesotho)
        val englishOption = view.findViewById<TextView>(R.id.optionEnglish)

        // Hide English if source is English
        if (sourceLanguage == Language.ENGLISH) {
            englishOption.visibility = View.GONE
        }

        // Highlight current
        when (targetLanguage) {
            Language.ZULU -> zuluOption.setTextColor(colorOrange)
            Language.SESOTHO -> sesothoOption.setTextColor(colorOrange)
            Language.ENGLISH -> englishOption.setTextColor(colorOrange)
        }

        zuluOption.setOnClickListener {
            targetLanguage = Language.ZULU
            if (sourceLanguage == Language.ZULU) sourceLanguage = Language.ENGLISH
            updateLanguageLabels()
            dialog.dismiss()
        }

        sesothoOption.setOnClickListener {
            targetLanguage = Language.SESOTHO
            if (sourceLanguage == Language.SESOTHO) sourceLanguage = Language.ENGLISH
            updateLanguageLabels()
            dialog.dismiss()
        }

        englishOption.setOnClickListener {
            targetLanguage = Language.ENGLISH
            if (sourceLanguage == Language.ENGLISH) sourceLanguage = Language.ZULU
            updateLanguageLabels()
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun performTranslation() {
        val text = sourceText.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Enter text to translate", Toast.LENGTH_SHORT).show()
            return
        }

        val client = claudeClient
        if (client == null) {
            Toast.makeText(this, "API not configured", Toast.LENGTH_SHORT).show()
            return
        }

        if (isTranslating) {
            translationJob?.cancel()
            isTranslating = false
            translateButton.text = getString(R.string.action_translate)
            statusIndicator.text = "Cancelled"
            statusIndicator.setTextColor(colorTextDim)
            return
        }

        isTranslating = true
        translateButton.text = "Stop"
        statusIndicator.text = getString(R.string.translating)
        statusIndicator.setTextColor(ContextCompat.getColor(this, R.color.claude_orange_light))
        outputText.text = ""

        val targetName = when (targetLanguage) {
            Language.ZULU -> "isiZulu (Zulu)"
            Language.SESOTHO -> "Sesotho (Southern Sotho)"
            Language.ENGLISH -> "English"
        }

        val prompt = """Translate the following text to $targetName.

RULES:
- Output ONLY the translation, nothing else
- Preserve the meaning and tone
- Use modern, everyday language
- Keep proper nouns unchanged

Text to translate:
$text"""

        val messages = listOf(
            ApiMessage("user", prompt)
        )

        translationJob = lifecycleScope.launch {
            try {
                client.sendMessage(messages)
                    .flowOn(Dispatchers.IO)
                    .onCompletion {
                        runOnUiThread {
                            isTranslating = false
                            translateButton.text = getString(R.string.action_translate)
                            statusIndicator.text = getString(R.string.translation_complete)
                            statusIndicator.setTextColor(colorGreen)
                        }
                    }
                    .catch { e ->
                        runOnUiThread {
                            isTranslating = false
                            translateButton.text = getString(R.string.action_translate)
                            statusIndicator.text = getString(R.string.translation_error)
                            statusIndicator.setTextColor(colorRed)
                            outputText.text = "Error: ${e.message}"
                        }
                    }
                    .collect { event ->
                        when (event) {
                            is ClaudeEvent.TextDelta -> {
                                runOnUiThread {
                                    outputText.append(event.text)
                                    outputScroll.post {
                                        outputScroll.fullScroll(View.FOCUS_DOWN)
                                    }
                                }
                            }
                            else -> { /* ignore other events */ }
                        }
                    }
            } catch (e: Exception) {
                runOnUiThread {
                    isTranslating = false
                    translateButton.text = getString(R.string.action_translate)
                    statusIndicator.text = getString(R.string.translation_error)
                    statusIndicator.setTextColor(colorRed)
                    outputText.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun pasteFromClipboard() {
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val pastedText = clip.getItemAt(0).text?.toString() ?: ""
            if (pastedText.isNotEmpty()) {
                sourceText.setText(pastedText)
                sourceText.setSelection(pastedText.length)
                Toast.makeText(this, getString(R.string.pasted_from_clipboard), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.clipboard_empty), Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.clipboard_empty), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard() {
        val textToCopy = outputText.text.toString()
        if (textToCopy.isBlank() || textToCopy == getString(R.string.translate_hint_output)) {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
            return
        }

        val clip = ClipData.newPlainText("Translation", textToCopy)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }

    private fun hapticFeedback(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
}
