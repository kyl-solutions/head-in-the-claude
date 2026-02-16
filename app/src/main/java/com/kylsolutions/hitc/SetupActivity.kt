package com.kylsolutions.hitc

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class SetupActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var modelSpinner: Spinner
    private lateinit var saveButton: Button
    private lateinit var errorText: TextView
    private lateinit var apiKeyManager: ApiKeyManager

    private val models = listOf(
        "claude-sonnet-4-5-20250929" to "Claude Sonnet 4.5 (Recommended)",
        "claude-opus-4-5-20251101" to "Claude Opus 4.5 (Most Capable)",
        "claude-haiku-4-20250131" to "Claude Haiku 4 (Fastest)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        apiKeyManager = ApiKeyManager(this)

        // If API key already exists, skip to main activity
        if (apiKeyManager.hasApiKey()) {
            navigateToMain()
            return
        }

        initViews()
        setupModelSpinner()
        setupListeners()
    }

    private fun initViews() {
        apiKeyInput = findViewById(R.id.apiKeyInput)
        modelSpinner = findViewById(R.id.modelSpinner)
        saveButton = findViewById(R.id.saveButton)
        errorText = findViewById(R.id.errorText)
    }

    private fun setupModelSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            models.map { it.second }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = adapter
        modelSpinner.setSelection(0) // Default to Sonnet
    }

    private fun setupListeners() {
        saveButton.setOnClickListener {
            validateAndSave()
        }
    }

    private fun validateAndSave() {
        val apiKey = apiKeyInput.text.toString().trim()

        // Validate API key
        if (apiKey.isEmpty()) {
            showError("Please enter your API key")
            return
        }

        if (!apiKeyManager.isValidApiKeyFormat(apiKey)) {
            showError("Invalid API key format. Should start with 'sk-ant-api'")
            return
        }

        // Save API key and model
        apiKeyManager.saveApiKey(apiKey)
        val selectedModel = models[modelSpinner.selectedItemPosition].first
        apiKeyManager.saveModel(selectedModel)

        // Navigate to main activity
        navigateToMain()
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onBackPressed() {
        // Prevent going back - user must enter API key
        // Do nothing
    }
}
