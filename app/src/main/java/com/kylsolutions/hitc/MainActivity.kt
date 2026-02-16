package com.kylsolutions.hitc

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.kylsolutions.hitc.database.ConversationDatabase
import com.kylsolutions.hitc.repository.SessionRepository
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import android.util.Log
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HitC"
    }

    // Views
    private lateinit var terminalOutput: TextView
    private lateinit var commandInput: EditText
    private lateinit var connectButton: Button
    private lateinit var sendButton: Button
    private lateinit var newSessionButton: Button
    private lateinit var scrollView: ScrollView
    private lateinit var screenshotButton: ImageButton
    private lateinit var connectionBadge: TextView
    private lateinit var shortcutScroll: HorizontalScrollView
    private lateinit var shortcutContainer: LinearLayout

    // Mode: relay (tool access on Mac) or direct API (anywhere, no tools)
    private var useRelay = true
    private lateinit var relaySessionMgr: HitcSessionManager
    private var relayClient: RelayClient? = null

    // Direct API fallback
    private lateinit var apiKeyManager: ApiKeyManager
    private var claudeClient: AnthropicClient? = null
    private lateinit var sessionRepo: SessionRepository
    private var currentConversationId: String? = null

    // Shared
    private var activeJob: Job? = null
    private var isStreaming = false
    private val spannableOutput = SpannableStringBuilder()
    private val assistantResponseBuffer = StringBuilder()
    private lateinit var screenshotHelper: ScreenshotHelper

    // Markdown + colors
    private lateinit var markwon: Markwon
    private var responseStartPos = -1
    private var colorPrimary = 0
    private var colorOrange = 0
    private var colorBlue = 0
    private var colorRed = 0
    private var colorGreen = 0

    // Shortcuts: label → full prompt
    private val shortcuts = listOf(
        "ls" to "List the files in the current directory",
        "git status" to "Run git status and show me the output",
        "read file" to "Read the contents of ",
        "run tests" to "Run the test suite and show results",
        "explain" to "Explain the code I'm looking at",
        "fix" to "Fix the issue in the current code",
        "refactor" to "Refactor this code for clarity and performance",
        "summarize" to "Summarize what this project does"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // API key needed for direct mode fallback
        apiKeyManager = ApiKeyManager(this)
        if (!apiKeyManager.hasApiKey()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        // Initialize colors
        colorPrimary = ContextCompat.getColor(this, R.color.text_primary)
        colorOrange = ContextCompat.getColor(this, R.color.claude_orange_light)
        colorBlue = ContextCompat.getColor(this, R.color.tool_blue)
        colorRed = ContextCompat.getColor(this, R.color.error_red)
        colorGreen = ContextCompat.getColor(this, R.color.success_green)

        // Initialize Markwon
        markwon = Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .build()

        // Initialize persistence
        val db = ConversationDatabase.getInstance(this)
        sessionRepo = SessionRepository(db)

        // Initialize direct API client (fallback)
        val apiKey = apiKeyManager.getApiKey()!!
        val model = apiKeyManager.getModel()
        claudeClient = AnthropicClient(apiKey, model)

        // Initialize relay
        relaySessionMgr = HitcSessionManager(this)

        initViews()
        buildShortcutBar()
        setupScreenshotHelper()
        setupListeners()
        connectToRelay()
    }

    private fun initViews() {
        terminalOutput = findViewById(R.id.terminalOutput)
        commandInput = findViewById(R.id.commandInput)
        connectButton = findViewById(R.id.connectButton)
        sendButton = findViewById(R.id.sendButton)
        newSessionButton = findViewById(R.id.newSessionButton)
        scrollView = findViewById(R.id.scrollView)
        screenshotButton = findViewById(R.id.screenshotFab)
        connectionBadge = findViewById(R.id.connectionBadge)
        shortcutScroll = findViewById(R.id.shortcutScroll)
        shortcutContainer = findViewById(R.id.shortcutContainer)
    }

    private fun buildShortcutBar() {
        val mono = ResourcesCompat.getFont(this, R.font.jetbrains_mono)
        val textColor = ContextCompat.getColor(this, R.color.shortcut_text)
        val dp6 = (6 * resources.displayMetrics.density).toInt()

        for ((label, prompt) in shortcuts) {
            val btn = TextView(this).apply {
                text = label
                typeface = mono
                textSize = 12f
                setTextColor(textColor)
                setBackgroundResource(R.drawable.bg_shortcut_button)
                setPadding(
                    (14 * resources.displayMetrics.density).toInt(),
                    dp6,
                    (14 * resources.displayMetrics.density).toInt(),
                    dp6
                )
                isClickable = true
                isFocusable = true
            }

            btn.setOnClickListener { v ->
                // Haptic feedback
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                handleShortcut(label, prompt)
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp6
            }
            shortcutContainer.addView(btn, lp)
        }
    }

    private fun handleShortcut(label: String, prompt: String) {
        if (isStreaming) return

        // "read file" is a prefix — user types the path
        if (prompt.endsWith(" ")) {
            commandInput.setText(prompt)
            commandInput.setSelection(prompt.length)
            commandInput.requestFocus()
            return
        }

        sendMessageDirect(prompt, "> /$label")
    }

    private fun setupScreenshotHelper() {
        screenshotHelper = ScreenshotHelper(this) { file ->
            handleScreenshot(file)
        }
        screenshotHelper.initialize()
    }

    private fun setupListeners() {
        connectButton.setOnClickListener {
            connectToRelay()
        }

        sendButton.setOnClickListener {
            sendMessage()
        }

        newSessionButton.setOnClickListener {
            startNewSession()
        }

        screenshotButton.setOnClickListener {
            screenshotHelper.captureScreenshot()
        }

        commandInput.setOnEditorActionListener { _, _, _ ->
            if (!isStreaming) sendMessage()
            true
        }

        // Long-press on connection badge to toggle relay/direct mode
        connectionBadge.setOnLongClickListener { v ->
            if (isStreaming) {
                Log.w(TAG, "Mode toggle blocked: streaming in progress")
                Toast.makeText(this, "Cannot switch mode while streaming", Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            } else {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }

            toggleConnectionMode()
            true
        }
    }

    private fun toggleConnectionMode() {
        val previousMode = if (useRelay) "relay" else "direct"
        val newMode = if (useRelay) "direct" else "relay"

        Log.i(TAG, "Toggling connection mode: $previousMode → $newMode")

        if (useRelay) {
            // Switching to direct mode
            Log.d(TAG, "Switching to direct API mode")
            useRelay = false
            showBadge(relay = false)
            appendColoredOutput("\n⚡ Switched to DIRECT mode (chat only, no Mac tools)\n\n", colorOrange)

            // Initialize direct conversation if needed
            if (currentConversationId == null) {
                Log.d(TAG, "No active conversation, creating new one for direct mode")
                loadOrCreateConversation()
            }
        } else {
            // Switching to relay mode - attempt connection
            Log.d(TAG, "Attempting to switch to relay mode")
            appendColoredOutput("\n⚡ Attempting to switch to RELAY mode...\n", colorOrange)

            lifecycleScope.launch {
                try {
                    val client = RelayClient(relaySessionMgr.relayUrl, relaySessionMgr.authToken)
                    val healthy = client.healthCheck()

                    if (healthy) {
                        relayClient = client
                        useRelay = true
                        showBadge(relay = true)
                        Log.i(TAG, "Successfully switched to relay mode")
                        appendColoredOutput("✓ Switched to RELAY mode (full tool access)\n\n", colorGreen)
                    } else {
                        Log.e(TAG, "Relay health check failed - staying in direct mode")
                        appendColoredOutput("✗ Relay not available - staying in DIRECT mode\n\n", colorRed)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect to relay: ${e.message}", e)
                    appendColoredOutput("✗ Relay error: ${e.message}\n  Staying in DIRECT mode\n\n", colorRed)
                }
            }
        }
    }

    // ─── Connect ────────────────────────────────────────────────

    private fun connectToRelay() {
        lifecycleScope.launch {
            Log.i(TAG, "Initiating connection to relay at ${relaySessionMgr.relayUrl}")
            appendColoredOutput("Connecting to Claude relay...\n", colorPrimary)
            connectButton.isEnabled = false

            try {
                val client = RelayClient(relaySessionMgr.relayUrl, relaySessionMgr.authToken)
                Log.d(TAG, "RelayClient created, performing health check...")
                val healthy = client.healthCheck()

                if (healthy) {
                    relayClient = client
                    useRelay = true
                    Log.i(TAG, "Relay connection successful")
                    appendColoredOutput("✓ Connected to Claude (relay — full tool access)\n\n", colorGreen)
                    showBadge(relay = true)
                } else {
                    Log.w(TAG, "Relay health check returned false")
                    fallbackToDirect("Relay not reachable")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Relay connection failed: ${e.message}", e)
                fallbackToDirect(e.message ?: "Connection failed")
            }

            // Enable chat UI regardless of mode
            connectButton.visibility = View.GONE
            sendButton.isEnabled = true
            newSessionButton.visibility = View.VISIBLE
            shortcutScroll.visibility = View.VISIBLE
            screenshotButton.isEnabled = true
            commandInput.requestFocus()

            if (!useRelay) {
                loadOrCreateConversation()
            }
        }
    }

    private fun fallbackToDirect(reason: String) {
        Log.w(TAG, "Falling back to direct API mode: $reason")
        appendColoredOutput("  ⚠ Relay unavailable ($reason)\n", colorOrange)
        appendColoredOutput("  → Using direct API (chat only, no Mac tools)\n\n", colorPrimary)
        useRelay = false
        showBadge(relay = false)
    }

    private fun showBadge(relay: Boolean) {
        connectionBadge.visibility = View.VISIBLE
        if (relay) {
            connectionBadge.text = getString(R.string.badge_relay)
            connectionBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_relay))
            connectionBadge.setBackgroundResource(R.drawable.bg_connection_badge)
        } else {
            connectionBadge.text = getString(R.string.badge_direct)
            connectionBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_direct))
            connectionBadge.background.setTint(ContextCompat.getColor(this, R.color.badge_direct_bg))
        }
    }

    // ─── Load/Create for direct API mode ────────────────────────

    private fun loadOrCreateConversation() {
        lifecycleScope.launch {
            try {
                val recent = sessionRepo.getMostRecentConversation()
                if (recent != null) {
                    currentConversationId = recent.id
                    val messageCount = sessionRepo.getMessageCount(recent.id)
                    if (messageCount > 0) {
                        appendColoredOutput("  ($messageCount messages in history)\n\n", colorPrimary)
                    }
                } else {
                    val newConversation = sessionRepo.createConversation()
                    currentConversationId = newConversation.id
                }
            } catch (e: Exception) {
                appendColoredOutput("✗ Failed to initialize: ${e.message}\n", colorRed)
            }
        }
    }

    // ─── Send Message ───────────────────────────────────────────

    private fun sendMessage() {
        val message = commandInput.text.toString().trim()
        if (message.isEmpty() || isStreaming) return

        commandInput.setText("")
        sendMessageDirect(message, null)
    }

    /**
     * Send a prompt. If displayAs is non-null, show that instead of the raw prompt.
     */
    private fun sendMessageDirect(prompt: String, displayAs: String?) {
        if (isStreaming) return

        setStreamingState(true)
        val display = displayAs ?: "> $prompt"
        appendColoredOutput("\n$display\n\n", colorOrange)

        // Mark where assistant response begins (for markdown finalization)
        markResponseStart()

        if (useRelay) {
            sendViaRelay(prompt)
        } else {
            sendViaDirect(prompt)
        }
    }

    private fun sendViaRelay(message: String) {
        val client = relayClient ?: run {
            Log.e(TAG, "sendViaRelay called but relayClient is null")
            appendColoredOutput("✗ Relay client not initialized\n", colorRed)
            setStreamingState(false)
            return
        }

        Log.d(TAG, "Sending message via relay, session=${relaySessionMgr.currentSessionId}")
        activeJob = lifecycleScope.launch {
            client.sendMessage(relaySessionMgr.currentSessionId, message)
                .flowOn(Dispatchers.IO)
                .onCompletion {
                    Log.d(TAG, "Relay message stream completed")
                    finalizeResponse()
                    setStreamingState(false)
                }
                .catch { e ->
                    Log.e(TAG, "Relay message error: ${e.message}", e)
                    appendColoredOutput("\n✗ Error: ${e.message}\n", colorRed)
                    setStreamingState(false)
                }
                .collect { event -> handleRelayEvent(event) }
        }
    }

    private fun sendViaDirect(message: String) {
        val client = claudeClient ?: run {
            Log.e(TAG, "sendViaDirect called but claudeClient is null")
            Toast.makeText(this, "Claude client not initialized", Toast.LENGTH_SHORT).show()
            setStreamingState(false)
            return
        }

        val conversationId = currentConversationId ?: run {
            Log.e(TAG, "sendViaDirect called but currentConversationId is null")
            Toast.makeText(this, "No active conversation", Toast.LENGTH_SHORT).show()
            setStreamingState(false)
            return
        }

        Log.d(TAG, "Sending message via direct API, conversation=$conversationId")
        activeJob = lifecycleScope.launch {
            try {
                sessionRepo.addUserMessage(conversationId, message)
                val history = sessionRepo.getMessageHistory(conversationId)
                Log.d(TAG, "Message history count: ${history.size}")
                assistantResponseBuffer.clear()

                client.sendMessage(history)
                    .flowOn(Dispatchers.IO)
                    .onCompletion {
                        Log.d(TAG, "Direct API message stream completed")
                        if (assistantResponseBuffer.isNotEmpty()) {
                            sessionRepo.addAssistantMessage(
                                conversationId,
                                assistantResponseBuffer.toString()
                            )
                        }
                        finalizeResponse()
                        setStreamingState(false)
                    }
                    .catch { e ->
                        Log.e(TAG, "Direct API message error: ${e.message}", e)
                        appendColoredOutput("\n✗ Error: ${e.message}\n", colorRed)
                        setStreamingState(false)
                    }
                    .collect { event -> handleClaudeEvent(event) }
            } catch (e: Exception) {
                Log.e(TAG, "Direct API exception: ${e.message}", e)
                appendColoredOutput("\n✗ Error: ${e.message}\n", colorRed)
                setStreamingState(false)
            }
        }
    }

    private fun sendMessageWithImage(file: File, caption: String) {
        setStreamingState(true)
        appendColoredOutput("\n> [Image: ${file.name}] $caption\n\n", colorOrange)
        markResponseStart()

        if (useRelay) {
            sendImageViaRelay(file, caption)
        } else {
            sendImageViaDirect(file, caption)
        }
    }

    private fun sendImageViaRelay(file: File, caption: String) {
        val client = relayClient ?: return

        activeJob = lifecycleScope.launch {
            client.sendMessageWithImage(relaySessionMgr.currentSessionId, caption, file)
                .flowOn(Dispatchers.IO)
                .onCompletion {
                    finalizeResponse()
                    setStreamingState(false)
                }
                .catch { e ->
                    appendColoredOutput("\n✗ Error: ${e.message}\n", colorRed)
                    setStreamingState(false)
                }
                .collect { event -> handleRelayEvent(event) }
        }
    }

    private fun sendImageViaDirect(file: File, caption: String) {
        val client = claudeClient ?: return
        val conversationId = currentConversationId ?: return

        activeJob = lifecycleScope.launch {
            try {
                sessionRepo.addUserMessage(conversationId, caption, file.absolutePath)
                val history = sessionRepo.getMessageHistory(conversationId)
                val imageBytes = file.readBytes()
                val imageBase64 = android.util.Base64.encodeToString(
                    imageBytes, android.util.Base64.NO_WRAP
                )
                assistantResponseBuffer.clear()

                client.sendMessageWithImage(history, imageBase64, "image/jpeg", caption)
                    .flowOn(Dispatchers.IO)
                    .onCompletion {
                        if (assistantResponseBuffer.isNotEmpty()) {
                            sessionRepo.addAssistantMessage(
                                conversationId, assistantResponseBuffer.toString()
                            )
                        }
                        finalizeResponse()
                        setStreamingState(false)
                    }
                    .catch { e ->
                        appendColoredOutput("\n✗ Error: ${e.message}\n", colorRed)
                        setStreamingState(false)
                    }
                    .collect { event -> handleClaudeEvent(event) }
            } catch (e: Exception) {
                appendColoredOutput("\n✗ Error: ${e.message}\n", colorRed)
                setStreamingState(false)
            }
        }
    }

    // ─── Handle Relay Events ────────────────────────────────────

    private fun handleRelayEvent(event: RelayEvent) {
        runOnUiThread {
            when (event) {
                is RelayEvent.Session -> {
                    relaySessionMgr.currentSessionId = event.sessionId
                }
                is RelayEvent.TextChunk -> {
                    appendOutput(event.text)
                }
                is RelayEvent.ToolUse -> {
                    appendColoredOutput("  ⚡ ${event.name}\n", colorBlue)
                }
                is RelayEvent.Error -> {
                    appendColoredOutput("\n✗ ${event.message}\n", colorRed)
                }
                is RelayEvent.Done -> {
                    appendOutput("\n")
                }
            }
        }
    }

    // ─── Handle Direct API Events ───────────────────────────────

    private fun handleClaudeEvent(event: ClaudeEvent) {
        runOnUiThread {
            when (event) {
                is ClaudeEvent.TextDelta -> {
                    appendOutput(event.text)
                    assistantResponseBuffer.append(event.text)
                }
                is ClaudeEvent.ContentBlockStart -> {
                    if (event.type == "tool_use") {
                        appendColoredOutput("  ⚡ Tool: ", colorBlue)
                    }
                }
                is ClaudeEvent.ToolUseStart -> {
                    appendColoredOutput("${event.name}\n", colorBlue)
                }
                is ClaudeEvent.MessageStop -> {
                    appendOutput("\n")
                }
                is ClaudeEvent.Error -> {
                    appendColoredOutput("\n✗ ${event.message}\n", colorRed)
                }
                else -> { /* Silent: Ping, MessageStart, MessageDelta, etc. */ }
            }
        }
    }

    // ─── Session Management ─────────────────────────────────────

    private fun startNewSession() {
        activeJob?.cancel()
        activeJob = null

        if (useRelay) {
            val sid = relaySessionMgr.currentSessionId
            if (sid != null) {
                lifecycleScope.launch { relayClient?.abort(sid) }
            }
            relaySessionMgr.newSession()
        } else {
            claudeClient?.abort()
        }

        lifecycleScope.launch {
            try {
                if (!useRelay) {
                    val newConversation = sessionRepo.createConversation()
                    currentConversationId = newConversation.id
                }
                spannableOutput.clear()
                assistantResponseBuffer.clear()
                responseStartPos = -1
                terminalOutput.text = getString(R.string.terminal_welcome)
                val mode = if (useRelay) "relay — full tool access" else "direct API"
                appendColoredOutput("✓ New session ($mode)\n\n", colorGreen)
                appendColoredOutput("  [Ask anything]\n\n", colorPrimary)
                setStreamingState(false)
                commandInput.requestFocus()
            } catch (e: Exception) {
                appendColoredOutput("\n✗ Failed to create new session: ${e.message}\n", colorRed)
            }
        }
    }

    // ─── Screenshot → Claude ────────────────────────────────────

    private fun handleScreenshot(file: File) {
        if (relayClient == null && claudeClient == null) {
            appendColoredOutput("\n📸 Screenshot saved: ${file.name}\n", colorPrimary)
            appendColoredOutput("   Not connected.\n\n", colorRed)
            Toast.makeText(this, getString(R.string.screenshot_saved, file.name), Toast.LENGTH_SHORT).show()
            return
        }

        appendColoredOutput("\n📸 Sending screenshot to Claude...\n", colorOrange)
        Toast.makeText(this, getString(R.string.screenshot_sent), Toast.LENGTH_SHORT).show()
        sendMessageWithImage(file, "What do you see in this screenshot? Describe it and suggest any relevant actions.")
    }

    // ─── Output Engine ──────────────────────────────────────────

    /**
     * Append plain text (fast, for streaming).
     */
    private fun appendOutput(text: String) {
        spannableOutput.append(text)
        trimIfNeeded()
        terminalOutput.text = spannableOutput
        scrollToBottom()
    }

    /**
     * Append colored text.
     */
    private fun appendColoredOutput(text: String, color: Int) {
        val start = spannableOutput.length
        spannableOutput.append(text)
        spannableOutput.setSpan(
            ForegroundColorSpan(color),
            start,
            spannableOutput.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        trimIfNeeded()
        terminalOutput.text = spannableOutput
        scrollToBottom()
    }

    /**
     * Record start of assistant response for markdown finalization.
     */
    private fun markResponseStart() {
        responseStartPos = spannableOutput.length
    }

    /**
     * Replace the raw streamed text with Markwon-rendered markdown.
     */
    private fun finalizeResponse() {
        runOnUiThread {
            if (responseStartPos < 0 || responseStartPos >= spannableOutput.length) {
                responseStartPos = -1
                return@runOnUiThread
            }

            try {
                val rawText = spannableOutput.subSequence(responseStartPos, spannableOutput.length).toString()
                if (rawText.isBlank()) {
                    responseStartPos = -1
                    return@runOnUiThread
                }

                // Render markdown
                val rendered = markwon.toMarkdown(rawText)

                // Replace raw section with rendered markdown
                spannableOutput.replace(responseStartPos, spannableOutput.length, rendered)
                terminalOutput.text = spannableOutput
                scrollToBottom()
            } catch (_: Exception) {
                // If markdown rendering fails, keep raw text
            }
            responseStartPos = -1
        }
    }

    private fun trimIfNeeded() {
        if (spannableOutput.length > 50_000) {
            val cutAt = spannableOutput.length - 40_000
            spannableOutput.delete(0, cutAt)
            spannableOutput.insert(0, "...\n")
            responseStartPos = -1 // invalidate — partial response lost
        }
    }

    private fun scrollToBottom() {
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    // ─── UI Helpers ─────────────────────────────────────────────

    private fun setStreamingState(streaming: Boolean) {
        isStreaming = streaming
        runOnUiThread {
            sendButton.isEnabled = !streaming
            screenshotButton.isEnabled = !streaming
            commandInput.isEnabled = !streaming
            sendButton.alpha = if (streaming) 0.5f else 1.0f
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeJob?.cancel()
    }
}
