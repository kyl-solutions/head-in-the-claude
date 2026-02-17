package com.kylsolutions.hitc

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kylsolutions.hitc.database.Conversation
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
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HitC"
    }

    // Views — Main
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var terminalOutput: TextView
    private lateinit var commandInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var scrollView: ScrollView
    private lateinit var screenshotButton: ImageButton
    private lateinit var connectionBadge: TextView
    private lateinit var shortcutScroll: HorizontalScrollView
    private lateinit var shortcutContainer: LinearLayout
    private lateinit var menuButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var bottomTabLayout: TabLayout

    // Views — Drawer
    private lateinit var projectsList: RecyclerView
    private lateinit var projectsEmptyText: TextView
    private lateinit var sessionsList: RecyclerView
    private lateinit var sessionsEmptyText: TextView
    private lateinit var newSessionButton: ImageButton
    private lateinit var drawerShortcutsContainer: LinearLayout
    private lateinit var settingsModelValue: TextView
    private lateinit var settingsRelayValue: TextView

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
    private var colorCoral = 0
    private var colorBlue = 0
    private var colorRed = 0
    private var colorGreen = 0

    // Adapters
    private val projectsAdapter = ProjectsAdapter { project -> onProjectTap(project) }
    private val sessionsAdapter = SessionsAdapter(
        onTap = { conversation -> onSessionTap(conversation) },
        onLongPress = { conversation -> onSessionLongPress(conversation) }
    )

    // Shortcuts: label → full prompt
    private val shortcuts = listOf(
        "ls" to "List the files in the current directory",
        "git status" to "Run git status and show me the output",
        "read file" to "Read the contents of ",
        "run tests" to "Run the test suite and show results",
        "explain" to "Explain the code I'm looking at",
        "fix" to "Fix the issue in the current code",
        "refactor" to "Refactor this code for clarity and performance",
        "summarize" to "Summarize what this project does",
        "Zulu" to "TRANSLATE_ZULU:",
        "Sotho" to "TRANSLATE_SESOTHO:"
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
        colorCoral = ContextCompat.getColor(this, R.color.coral_light)
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
        setupBottomTabs()
        setupDrawer()
        buildShortcutBar()
        buildDrawerShortcuts()
        setupScreenshotHelper()
        setupListeners()
        connectToRelay()
    }

    // ─── Init ────────────────────────────────────────────────────

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        terminalOutput = findViewById(R.id.terminalOutput)
        commandInput = findViewById(R.id.commandInput)
        sendButton = findViewById(R.id.sendButton)
        scrollView = findViewById(R.id.scrollView)
        screenshotButton = findViewById(R.id.screenshotFab)
        connectionBadge = findViewById(R.id.connectionBadge)
        shortcutScroll = findViewById(R.id.shortcutScroll)
        shortcutContainer = findViewById(R.id.shortcutContainer)
        menuButton = findViewById(R.id.menuButton)
        settingsButton = findViewById(R.id.settingsButton)
        bottomTabLayout = findViewById(R.id.bottomTabLayout)

        // Drawer views
        projectsList = findViewById(R.id.projectsList)
        projectsEmptyText = findViewById(R.id.projectsEmptyText)
        sessionsList = findViewById(R.id.sessionsList)
        sessionsEmptyText = findViewById(R.id.sessionsEmptyText)
        newSessionButton = findViewById(R.id.newSessionButton)
        drawerShortcutsContainer = findViewById(R.id.drawerShortcutsContainer)
        settingsModelValue = findViewById(R.id.settingsModelValue)
        settingsRelayValue = findViewById(R.id.settingsRelayValue)
    }

    private fun setupBottomTabs() {
        bottomTabLayout.addTab(bottomTabLayout.newTab().setText("Chat"))
        bottomTabLayout.addTab(bottomTabLayout.newTab().setText("Commands"))

        bottomTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> { // Chat tab
                        scrollView.visibility = View.VISIBLE
                        shortcutScroll.visibility = View.GONE
                    }
                    1 -> { // Commands tab
                        scrollView.visibility = View.VISIBLE
                        shortcutScroll.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupDrawer() {
        // Projects RecyclerView
        projectsList.layoutManager = LinearLayoutManager(this)
        projectsList.adapter = projectsAdapter

        // Sessions RecyclerView
        sessionsList.layoutManager = LinearLayoutManager(this)
        sessionsList.adapter = sessionsAdapter

        // Observe conversations from Room
        lifecycleScope.launch {
            sessionRepo.getAllConversations().collect { conversations ->
                if (conversations.isEmpty()) {
                    sessionsEmptyText.visibility = View.VISIBLE
                    sessionsList.visibility = View.GONE
                } else {
                    sessionsEmptyText.visibility = View.GONE
                    sessionsList.visibility = View.VISIBLE
                    sessionsAdapter.submitList(conversations)
                }
            }
        }

        // Settings values
        updateSettingsDisplay()
    }

    private fun updateSettingsDisplay() {
        val model = apiKeyManager.getModel()
        settingsModelValue.text = when {
            model.contains("opus") -> "Opus"
            model.contains("haiku") -> "Haiku"
            else -> "Sonnet"
        }
        val url = relaySessionMgr.relayUrl
        settingsRelayValue.text = url.removePrefix("http://").removePrefix("https://")
    }

    private fun buildShortcutBar() {
        val metropolis = ResourcesCompat.getFont(this, R.font.metropolis)
        val textColor = ContextCompat.getColor(this, R.color.shortcut_text)
        val dp6 = (6 * resources.displayMetrics.density).toInt()

        for ((label, prompt) in shortcuts) {
            val btn = TextView(this).apply {
                text = label
                typeface = metropolis
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

    private fun buildDrawerShortcuts() {
        for ((label, prompt) in shortcuts) {
            val view = LayoutInflater.from(this).inflate(R.layout.item_shortcut, drawerShortcutsContainer, false)
            view.findViewById<TextView>(R.id.shortcutLabel).text = label
            view.findViewById<TextView>(R.id.shortcutDescription).text = when {
                prompt.startsWith("TRANSLATE_") -> "Launch translator"
                prompt.endsWith(" ") -> "Type a path to read"
                else -> prompt.take(40)
            }
            view.setOnClickListener {
                handleShortcut(label, prompt)
                drawerLayout.closeDrawer(GravityCompat.START)
            }
            drawerShortcutsContainer.addView(view)
        }
    }

    private fun handleShortcut(label: String, prompt: String) {
        if (isStreaming) return

        if (prompt.startsWith("TRANSLATE_")) {
            val targetLang = prompt.removePrefix("TRANSLATE_").removeSuffix(":")
            val intent = Intent(this, TranslateActivity::class.java).apply {
                putExtra(TranslateActivity.EXTRA_TARGET_LANGUAGE, targetLang)
            }
            startActivity(intent)
            return
        }

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
        menuButton.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        settingsButton.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        sendButton.setOnClickListener {
            sendMessage()
        }

        newSessionButton.setOnClickListener {
            startNewSession()
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        screenshotButton.setOnClickListener {
            screenshotHelper.captureScreenshot()
        }

        commandInput.setOnEditorActionListener { _, _, _ ->
            if (!isStreaming) sendMessage()
            true
        }

        connectionBadge.setOnClickListener { v ->
            if (isStreaming) {
                Toast.makeText(this, "Cannot switch mode while streaming", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            toggleConnectionMode()
        }

        // Settings rows
        findViewById<View>(R.id.settingsModelRow).setOnClickListener { showModelPicker() }
        findViewById<View>(R.id.settingsRelayRow).setOnClickListener { showRelayUrlEditor() }
        findViewById<View>(R.id.settingsApiKeyRow).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        findViewById<View>(R.id.settingsAboutRow).setOnClickListener {
            Toast.makeText(this, "Head-In-The-Claude v0.3.0\nby KYL Solutions", Toast.LENGTH_LONG).show()
        }
    }

    // ─── Drawer Actions ─────────────────────────────────────────

    private fun onProjectTap(project: ProjectInfo) {
        val contextPrefix = "Working in ${project.path} — "
        commandInput.setText(contextPrefix)
        commandInput.setSelection(contextPrefix.length)
        commandInput.requestFocus()
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun onSessionTap(conversation: Conversation) {
        currentConversationId = conversation.id
        spannableOutput.clear()
        assistantResponseBuffer.clear()
        responseStartPos = -1
        terminalOutput.text = ""
        appendColoredOutput("${conversation.title}\n", colorCoral)
        appendColoredOutput("${conversation.messageCount} messages\n\n", colorPrimary)
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun onSessionLongPress(conversation: Conversation) {
        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog_Alert)
            .setTitle("Delete conversation?")
            .setMessage(conversation.title)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    sessionRepo.deleteConversation(conversation.id)
                    if (currentConversationId == conversation.id) {
                        startNewSession()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadProjects() {
        val client = relayClient ?: return
        lifecycleScope.launch {
            try {
                val projects = client.getProjects()
                if (projects.isEmpty()) {
                    projectsEmptyText.text = getString(R.string.drawer_projects_empty)
                    projectsEmptyText.visibility = View.VISIBLE
                    projectsList.visibility = View.GONE
                } else {
                    projectsEmptyText.visibility = View.GONE
                    projectsList.visibility = View.VISIBLE
                    projectsAdapter.submitList(projects)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load projects: ${e.message}")
            }
        }
    }

    private fun showModelPicker() {
        val models = arrayOf("Sonnet", "Opus", "Haiku")
        val modelIds = arrayOf(
            "claude-sonnet-4-5-20250929",
            "claude-opus-4-6",
            "claude-haiku-4-5-20251001"
        )
        val currentModel = apiKeyManager.getModel()
        val currentIdx = modelIds.indexOfFirst { currentModel.contains(it) }.coerceAtLeast(0)

        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog_Alert)
            .setTitle("Select Model")
            .setSingleChoiceItems(models, currentIdx) { dialog, which ->
                apiKeyManager.saveModel(modelIds[which])
                claudeClient = AnthropicClient(apiKeyManager.getApiKey()!!, modelIds[which])
                settingsModelValue.text = models[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun showRelayUrlEditor() {
        val input = EditText(this).apply {
            setText(relaySessionMgr.relayUrl)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog_Alert)
            .setTitle("Relay URL")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    relaySessionMgr.relayUrl = url
                    updateSettingsDisplay()
                    Toast.makeText(this, "Relay URL updated. Reconnecting...", Toast.LENGTH_SHORT).show()
                    connectToRelay()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Connect ────────────────────────────────────────────────

    private fun toggleConnectionMode() {
        if (useRelay) {
            useRelay = false
            showBadge(relay = false)
            appendColoredOutput("\nSwitched to DIRECT mode (chat only, no Mac tools)\n\n", colorCoral)
            if (currentConversationId == null) loadOrCreateConversation()
        } else {
            appendColoredOutput("\nAttempting RELAY mode...\n", colorCoral)
            lifecycleScope.launch {
                try {
                    val client = RelayClient(relaySessionMgr.relayUrl, relaySessionMgr.authToken)
                    if (client.healthCheck()) {
                        relayClient = client
                        useRelay = true
                        showBadge(relay = true)
                        appendColoredOutput("Switched to RELAY mode (full tool access)\n\n", colorGreen)
                        loadProjects()
                    } else {
                        appendColoredOutput("Relay not available — staying in DIRECT mode\n\n", colorRed)
                    }
                } catch (e: Exception) {
                    appendColoredOutput("Relay error: ${e.message}\n  Staying in DIRECT mode\n\n", colorRed)
                }
            }
        }
    }

    private fun connectToRelay() {
        lifecycleScope.launch {
            Log.i(TAG, "Connecting to relay at ${relaySessionMgr.relayUrl}")

            try {
                val client = RelayClient(relaySessionMgr.relayUrl, relaySessionMgr.authToken)
                val healthy = client.healthCheck()

                if (healthy) {
                    relayClient = client
                    useRelay = true
                    Log.i(TAG, "Relay connection successful")
                    showBadge(relay = true)
                    loadProjects()
                } else {
                    fallbackToDirect("Relay not reachable")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Relay connection failed: ${e.message}", e)
                fallbackToDirect(e.message ?: "Connection failed")
            }

            sendButton.isEnabled = true
            screenshotButton.isEnabled = true
            commandInput.requestFocus()

            if (!useRelay) loadOrCreateConversation()
        }
    }

    private fun fallbackToDirect(reason: String) {
        Log.w(TAG, "Falling back to direct API mode: $reason")
        useRelay = false
        showBadge(relay = false)
    }

    private fun showBadge(relay: Boolean) {
        connectionBadge.visibility = View.VISIBLE
        if (relay) {
            connectionBadge.text = "✕ ${getString(R.string.badge_relay)}"
            connectionBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_relay))
            connectionBadge.setBackgroundResource(R.drawable.bg_connection_badge)
        } else {
            connectionBadge.text = "◆ ${getString(R.string.badge_direct)}"
            connectionBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_direct))
            connectionBadge.setBackgroundResource(R.drawable.bg_connection_badge)
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
                } else {
                    val newConversation = sessionRepo.createConversation()
                    currentConversationId = newConversation.id
                }
            } catch (e: Exception) {
                appendColoredOutput("Failed to initialize: ${e.message}\n", colorRed)
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

    private fun sendMessageDirect(prompt: String, displayAs: String?) {
        if (isStreaming) return
        setStreamingState(true)
        // User message with coral "User:" label
        val display = displayAs ?: prompt
        appendColoredOutput("\n🧑 User: ", colorCoral)
        appendColoredOutput("$display\n\n", colorPrimary)
        // Claude response prefix
        appendOutput("Claude: ")
        markResponseStart()
        if (useRelay) sendViaRelay(prompt) else sendViaDirect(prompt)
    }

    private fun sendViaRelay(message: String) {
        val client = relayClient ?: run {
            appendColoredOutput("Relay client not initialized\n", colorRed)
            setStreamingState(false)
            return
        }
        activeJob = lifecycleScope.launch {
            client.sendMessage(relaySessionMgr.currentSessionId, message)
                .flowOn(Dispatchers.IO)
                .onCompletion { finalizeResponse(); setStreamingState(false) }
                .catch { e -> appendColoredOutput("\nError: ${e.message}\n", colorRed); setStreamingState(false) }
                .collect { event -> handleRelayEvent(event) }
        }
    }

    private fun sendViaDirect(message: String) {
        val client = claudeClient ?: run {
            Toast.makeText(this, "Claude client not initialized", Toast.LENGTH_SHORT).show()
            setStreamingState(false)
            return
        }
        val conversationId = currentConversationId ?: run {
            Toast.makeText(this, "No active conversation", Toast.LENGTH_SHORT).show()
            setStreamingState(false)
            return
        }
        activeJob = lifecycleScope.launch {
            try {
                sessionRepo.addUserMessage(conversationId, message)
                val history = sessionRepo.getMessageHistory(conversationId)
                assistantResponseBuffer.clear()
                client.sendMessage(history)
                    .flowOn(Dispatchers.IO)
                    .onCompletion {
                        if (assistantResponseBuffer.isNotEmpty()) {
                            sessionRepo.addAssistantMessage(conversationId, assistantResponseBuffer.toString())
                        }
                        finalizeResponse(); setStreamingState(false)
                    }
                    .catch { e -> appendColoredOutput("\nError: ${e.message}\n", colorRed); setStreamingState(false) }
                    .collect { event -> handleClaudeEvent(event) }
            } catch (e: Exception) {
                appendColoredOutput("\nError: ${e.message}\n", colorRed); setStreamingState(false)
            }
        }
    }

    private fun sendMessageWithImage(file: File, caption: String) {
        setStreamingState(true)
        appendColoredOutput("\n> [Image: ${file.name}] $caption\n\n", colorCoral)
        markResponseStart()
        if (useRelay) sendImageViaRelay(file, caption) else sendImageViaDirect(file, caption)
    }

    private fun sendImageViaRelay(file: File, caption: String) {
        val client = relayClient ?: return
        activeJob = lifecycleScope.launch {
            client.sendMessageWithImage(relaySessionMgr.currentSessionId, caption, file)
                .flowOn(Dispatchers.IO)
                .onCompletion { finalizeResponse(); setStreamingState(false) }
                .catch { e -> appendColoredOutput("\nError: ${e.message}\n", colorRed); setStreamingState(false) }
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
                val imageBase64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                assistantResponseBuffer.clear()
                client.sendMessageWithImage(history, imageBase64, "image/jpeg", caption)
                    .flowOn(Dispatchers.IO)
                    .onCompletion {
                        if (assistantResponseBuffer.isNotEmpty()) {
                            sessionRepo.addAssistantMessage(conversationId, assistantResponseBuffer.toString())
                        }
                        finalizeResponse(); setStreamingState(false)
                    }
                    .catch { e -> appendColoredOutput("\nError: ${e.message}\n", colorRed); setStreamingState(false) }
                    .collect { event -> handleClaudeEvent(event) }
            } catch (e: Exception) {
                appendColoredOutput("\nError: ${e.message}\n", colorRed); setStreamingState(false)
            }
        }
    }

    // ─── Handle Events ──────────────────────────────────────────

    private fun handleRelayEvent(event: RelayEvent) {
        runOnUiThread {
            when (event) {
                is RelayEvent.Session -> relaySessionMgr.currentSessionId = event.sessionId
                is RelayEvent.TextChunk -> appendOutput(event.text)
                is RelayEvent.ToolUse -> appendColoredOutput("  ${event.name}\n", colorBlue)
                is RelayEvent.Error -> appendColoredOutput("\n${event.message}\n", colorRed)
                is RelayEvent.Done -> appendOutput("\n")
            }
        }
    }

    private fun handleClaudeEvent(event: ClaudeEvent) {
        runOnUiThread {
            when (event) {
                is ClaudeEvent.TextDelta -> {
                    appendOutput(event.text)
                    assistantResponseBuffer.append(event.text)
                }
                is ClaudeEvent.ContentBlockStart -> {
                    if (event.type == "tool_use") appendColoredOutput("  Tool: ", colorBlue)
                }
                is ClaudeEvent.ToolUseStart -> appendColoredOutput("${event.name}\n", colorBlue)
                is ClaudeEvent.MessageStop -> appendOutput("\n")
                is ClaudeEvent.Error -> appendColoredOutput("\n${event.message}\n", colorRed)
                else -> { }
            }
        }
    }

    // ─── Session Management ─────────────────────────────────────

    private fun startNewSession() {
        activeJob?.cancel()
        activeJob = null
        if (useRelay) {
            val sid = relaySessionMgr.currentSessionId
            if (sid != null) lifecycleScope.launch { relayClient?.abort(sid) }
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
                terminalOutput.text = ""
                val mode = if (useRelay) "relay" else "direct"
                appendColoredOutput("New session ($mode)\n\n", colorGreen)
                setStreamingState(false)
                commandInput.requestFocus()
            } catch (e: Exception) {
                appendColoredOutput("\nFailed to create new session: ${e.message}\n", colorRed)
            }
        }
    }

    // ─── Screenshot → Claude ────────────────────────────────────

    private fun handleScreenshot(file: File) {
        if (relayClient == null && claudeClient == null) {
            Toast.makeText(this, getString(R.string.screenshot_saved, file.name), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.screenshot_sent), Toast.LENGTH_SHORT).show()
        sendMessageWithImage(file, "What do you see in this screenshot? Describe it and suggest any relevant actions.")
    }

    // ─── Output Engine ──────────────────────────────────────────

    private fun appendOutput(text: String) {
        spannableOutput.append(text)
        trimIfNeeded()
        terminalOutput.text = spannableOutput
        scrollToBottom()
    }

    private fun appendColoredOutput(text: String, color: Int) {
        val start = spannableOutput.length
        spannableOutput.append(text)
        spannableOutput.setSpan(
            ForegroundColorSpan(color), start, spannableOutput.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        trimIfNeeded()
        terminalOutput.text = spannableOutput
        scrollToBottom()
    }

    private fun markResponseStart() { responseStartPos = spannableOutput.length }

    private fun finalizeResponse() {
        runOnUiThread {
            if (responseStartPos < 0 || responseStartPos >= spannableOutput.length) {
                responseStartPos = -1; return@runOnUiThread
            }
            try {
                val rawText = spannableOutput.subSequence(responseStartPos, spannableOutput.length).toString()
                if (rawText.isBlank()) { responseStartPos = -1; return@runOnUiThread }
                val rendered = markwon.toMarkdown(rawText)
                spannableOutput.replace(responseStartPos, spannableOutput.length, rendered)
                terminalOutput.text = spannableOutput
                scrollToBottom()
            } catch (_: Exception) { }
            responseStartPos = -1
        }
    }

    private fun trimIfNeeded() {
        if (spannableOutput.length > 50_000) {
            val cutAt = spannableOutput.length - 40_000
            spannableOutput.delete(0, cutAt)
            spannableOutput.insert(0, "...\n")
            responseStartPos = -1
        }
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
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

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeJob?.cancel()
    }
}

// ─── RecyclerView Adapters ──────────────────────────────────────

class ProjectsAdapter(
    private val onTap: (ProjectInfo) -> Unit
) : RecyclerView.Adapter<ProjectsAdapter.ViewHolder>() {

    private var items = listOf<ProjectInfo>()

    fun submitList(list: List<ProjectInfo>) {
        items = list
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.projectName)
        val type: TextView = view.findViewById(R.id.projectType)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val project = items[position]
        holder.name.text = project.name
        holder.type.text = project.type
        holder.itemView.setOnClickListener { onTap(project) }
    }

    override fun getItemCount() = items.size
}

class SessionsAdapter(
    private val onTap: (Conversation) -> Unit,
    private val onLongPress: (Conversation) -> Unit
) : RecyclerView.Adapter<SessionsAdapter.ViewHolder>() {

    private var items = listOf<Conversation>()
    private val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    fun submitList(list: List<Conversation>) {
        items = list
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.sessionTitle)
        val timestamp: TextView = view.findViewById(R.id.sessionTimestamp)
        val messageCount: TextView = view.findViewById(R.id.sessionMessageCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conversation = items[position]
        holder.title.text = conversation.title
        holder.timestamp.text = dateFormat.format(Date(conversation.updatedAt))
        holder.messageCount.text = "${conversation.messageCount} msgs"
        holder.itemView.setOnClickListener { onTap(conversation) }
        holder.itemView.setOnLongClickListener { onLongPress(conversation); true }
    }

    override fun getItemCount() = items.size
}
