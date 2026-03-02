package com.kylsolutions.kylidescope.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kylsolutions.kylidescope.AttachmentResult
import com.kylsolutions.kylidescope.FsEntry
import com.kylsolutions.kylidescope.ScreenshotHelper
import com.kylsolutions.kylidescope.KylidescopeSessionManager
import com.kylsolutions.kylidescope.R
import com.kylsolutions.kylidescope.RelayClient
import com.kylsolutions.kylidescope.RelayEvent
import com.kylsolutions.kylidescope.SessionInfo
import com.kylsolutions.kylidescope.SessionMessage
import com.kylsolutions.kylidescope.ui.files.FileAdapter
import com.kylsolutions.kylidescope.ui.files.FileViewerActivity
import com.kylsolutions.kylidescope.ui.sessions.SessionAdapter
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var sessionMgr: KylidescopeSessionManager
    private var relayClient: RelayClient? = null

    // Chat views
    private lateinit var terminalOutput: TextView
    private lateinit var commandInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var attachButton: ImageButton
    private lateinit var scrollView: ScrollView
    private lateinit var statusBar: TextView
    private lateinit var thinkingIndicator: LinearLayout
    private lateinit var chatContainer: LinearLayout
    private lateinit var screenshotHelper: ScreenshotHelper

    // Files views
    private lateinit var filesContainer: LinearLayout
    private lateinit var filesPathBar: TextView
    private lateinit var filesBackButton: ImageButton
    private lateinit var filesRecyclerView: RecyclerView
    private lateinit var filesEmptyText: TextView
    private lateinit var fileAdapter: FileAdapter

    // Sessions views
    private lateinit var sessionsContainer: LinearLayout
    private lateinit var sessionsRecyclerView: RecyclerView
    private lateinit var sessionsEmptyText: TextView
    private lateinit var newSessionButton: ImageButton
    private lateinit var sessionAdapter: SessionAdapter

    // Bottom nav
    private lateinit var bottomNav: BottomNavigationView

    // Attachment queue
    private lateinit var attachIndicator: TextView
    private val pendingAttachments = mutableListOf<AttachmentResult>()

    // Chat state
    private lateinit var markwon: Markwon
    private val spannableOutput = SpannableStringBuilder()
    private val responseBuffer = StringBuilder()
    private var responseStartPos = -1
    private var isStreaming = false
    private var activeJob: Job? = null

    // Files state
    private val pathStack = mutableListOf<String>()
    private var filesLoaded = false

    // Sessions state
    private var sessionsLoaded = false

    // Connection state
    private var relayReachable = false
    private var heartbeatJob: Job? = null

    // Colors
    private var colorPrimary = 0
    private var colorGreen = 0
    private var colorBlue = 0
    private var colorRed = 0
    private var colorDim = 0
    private var colorToolName = 0
    private var colorToolInput = 0
    private var colorToolOk = 0
    private var colorToolErr = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        sessionMgr = KylidescopeSessionManager(this)
        markwon = Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .build()

        initColors()
        initChatViews()
        initFilesViews()
        initSessionsViews()
        initBottomNav()
        connectToRelay()
    }

    private fun initColors() {
        colorPrimary = ContextCompat.getColor(this, R.color.text_primary)
        colorGreen = ContextCompat.getColor(this, R.color.signal_green)
        colorBlue = ContextCompat.getColor(this, R.color.tool_blue)
        colorRed = ContextCompat.getColor(this, R.color.error_red)
        colorDim = ContextCompat.getColor(this, R.color.text_dim)
        colorToolName = ContextCompat.getColor(this, R.color.tool_name)
        colorToolInput = ContextCompat.getColor(this, R.color.tool_input)
        colorToolOk = ContextCompat.getColor(this, R.color.tool_result_ok)
        colorToolErr = ContextCompat.getColor(this, R.color.tool_result_err)
    }

    private fun initChatViews() {
        chatContainer = findViewById(R.id.chatContainer)
        terminalOutput = findViewById(R.id.terminalOutput)
        commandInput = findViewById(R.id.commandInput)
        sendButton = findViewById(R.id.sendButton)
        attachButton = findViewById(R.id.attachButton)
        attachIndicator = findViewById(R.id.attachIndicator)
        scrollView = findViewById(R.id.scrollView)
        statusBar = findViewById(R.id.statusBar)
        thinkingIndicator = findViewById(R.id.thinkingIndicator)

        sendButton.setOnClickListener {
            if (isStreaming) abortStream() else sendMessage()
        }
        commandInput.setOnEditorActionListener { _, _, _ ->
            if (!isStreaming) sendMessage()
            true
        }

        // Long-press attach indicator to clear pending attachments
        attachIndicator.setOnClickListener { clearPendingAttachments() }

        // Image attachment — queue instead of send immediately
        screenshotHelper = ScreenshotHelper(
            activity = this,
            onScreenshotCaptured = { file ->
                runOnUiThread { queueAttachment(AttachmentResult(file, "image/jpeg")) }
            },
            onFileAttached = { attachment ->
                runOnUiThread { queueAttachment(attachment) }
            }
        )
        screenshotHelper.initialize()
        attachButton.setOnClickListener { screenshotHelper.showAttachOptions() }
    }

    private fun initFilesViews() {
        filesContainer = findViewById(R.id.filesContainer)
        filesPathBar = findViewById(R.id.filesPathBar)
        filesBackButton = findViewById(R.id.filesBackButton)
        filesRecyclerView = findViewById(R.id.filesRecyclerView)
        filesEmptyText = findViewById(R.id.filesEmptyText)

        fileAdapter = FileAdapter(
            onTap = { entry -> onFileEntryTap(entry) },
            onLongPress = { entry -> onFileEntryLongPress(entry) }
        )

        filesRecyclerView.layoutManager = LinearLayoutManager(this)
        filesRecyclerView.adapter = fileAdapter

        filesBackButton.setOnClickListener { navigateFilesBack() }
    }

    private fun initBottomNav() {
        bottomNav = findViewById(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chat -> { showTab(chatContainer); true }
                R.id.nav_files -> {
                    showTab(filesContainer)
                    if (!filesLoaded) loadDirectory("")
                    true
                }
                R.id.nav_sessions -> {
                    showTab(sessionsContainer)
                    loadSessions()
                    true
                }
                else -> false
            }
        }
    }

    private fun showTab(target: View) {
        chatContainer.visibility = if (target == chatContainer) View.VISIBLE else View.GONE
        filesContainer.visibility = if (target == filesContainer) View.VISIBLE else View.GONE
        sessionsContainer.visibility = if (target == sessionsContainer) View.VISIBLE else View.GONE
    }

    // ═══ RELAY CONNECTION ═══════════════════════════════════════════════════

    private fun connectToRelay() {
        appendColored("KYLIDESCOPE Mobile v1.0\n", colorGreen)
        appendColored("Connecting to relay...\n\n", colorDim)

        lifecycleScope.launch {
            val ok = tryConnect()
            if (ok) {
                runOnUiThread {
                    appendColored("Connected to KYLIDESCOPE server\n\n", colorGreen)
                    commandInput.requestFocus()
                }
                startHeartbeat()
            }
        }
    }

    private suspend fun tryConnect(): Boolean {
        return try {
            val client = RelayClient(sessionMgr.relayUrl, sessionMgr.authToken)
            if (client.healthCheck()) {
                relayClient = client
                relayReachable = true
                runOnUiThread { updateConnectionUI(true) }
                true
            } else {
                relayReachable = false
                runOnUiThread { updateConnectionUI(false, "Relay not reachable at ${sessionMgr.relayUrl}") }
                false
            }
        } catch (e: Exception) {
            relayReachable = false
            runOnUiThread { updateConnectionUI(false, "Connection failed: ${e.message}") }
            false
        }
    }

    private fun updateConnectionUI(connected: Boolean, errorMsg: String? = null) {
        if (connected) {
            statusBar.text = "● relay"
            statusBar.setTextColor(colorGreen)
            sendButton.isEnabled = true
            commandInput.isEnabled = true
        } else {
            statusBar.text = "✕ disconnected"
            statusBar.setTextColor(colorRed)
            sendButton.isEnabled = false
            commandInput.isEnabled = false
            if (errorMsg != null) appendColored("$errorMsg\n", colorRed)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                val wasReachable = relayReachable
                val ok = try { relayClient?.healthCheck() ?: false } catch (_: Exception) { false }
                relayReachable = ok

                if (ok != wasReachable) {
                    runOnUiThread { updateConnectionUI(ok) }
                    if (ok && !wasReachable) {
                        // Came back online
                        runOnUiThread { appendColored("Relay reconnected\n", colorGreen) }
                    } else if (!ok && wasReachable) {
                        runOnUiThread { appendColored("Relay connection lost\n", colorRed) }
                    }
                }

                // If unreachable, try rebuilding the client
                if (!ok) {
                    try {
                        val client = RelayClient(sessionMgr.relayUrl, sessionMgr.authToken)
                        if (client.healthCheck()) {
                            relayClient = client
                            relayReachable = true
                            runOnUiThread {
                                updateConnectionUI(true)
                                appendColored("Relay auto-reconnected\n", colorGreen)
                            }
                        }
                    } catch (_: Exception) { /* will retry next cycle */ }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check connectivity when app returns from background
        lifecycleScope.launch {
            val ok = try { relayClient?.healthCheck() ?: false } catch (_: Exception) { false }
            val wasReachable = relayReachable
            relayReachable = ok
            runOnUiThread { updateConnectionUI(ok) }

            if (!ok && wasReachable) {
                runOnUiThread { appendColored("Relay connection lost\n", colorRed) }
            }

            // Try to reconnect if needed
            if (!ok) {
                val reconnected = tryConnect()
                if (reconnected) {
                    runOnUiThread { appendColored("Relay reconnected\n", colorGreen) }
                }
            }
        }
        startHeartbeat()
    }

    override fun onPause() {
        super.onPause()
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private suspend fun tryReconnectRelay(): Boolean {
        return try {
            val client = RelayClient(sessionMgr.relayUrl, sessionMgr.authToken)
            if (client.healthCheck()) {
                relayClient = client
                relayReachable = true
                runOnUiThread { updateConnectionUI(true) }
                true
            } else false
        } catch (_: Exception) { false }
    }

    // ═══ CHAT ═══════════════════════════════════════════════════════════════

    private fun sendMessage(prefill: String? = null, isRetry: Boolean = false) {
        val text = prefill ?: commandInput.text.toString().trim()

        // If there are pending attachments, send with them
        if (!isRetry && pendingAttachments.isNotEmpty()) {
            if (text.isEmpty() && pendingAttachments.isEmpty()) return
            if (isStreaming) return
            commandInput.setText("")
            val attachments = pendingAttachments.toList()
            clearPendingAttachments()
            sendWithAttachments(text, attachments)
            return
        }

        if (text.isEmpty() || isStreaming) return
        if (!isRetry) commandInput.setText("")
        setStreaming(true)

        if (!isRetry) {
            appendColored("\nYOU: ", colorGreen)
            append("$text\n\n")
        }
        appendColored("MODEL: ", colorBlue)
        responseStartPos = spannableOutput.length
        responseBuffer.clear()

        val client = relayClient ?: run {
            appendColored("Not connected\n", colorRed)
            setStreaming(false)
            return
        }

        activeJob = lifecycleScope.launch {
            client.sendMessage(sessionMgr.currentSessionId, text)
                .flowOn(Dispatchers.IO)
                .onCompletion { finalizeResponse(); setStreaming(false) }
                .catch { e ->
                    val msg = e.message ?: ""
                    if (!isRetry && (msg.contains("Connection") || msg.contains("timed out") || msg.contains("unreachable"))) {
                        // Network failure — try reconnecting once
                        runOnUiThread {
                            appendColored("\nConnection lost, reconnecting...\n", colorRed)
                            relayReachable = false
                            updateConnectionUI(false)
                        }
                        val reconnected = tryReconnectRelay()
                        if (reconnected) {
                            runOnUiThread { appendColored("Reconnected. Retrying...\n", colorGreen) }
                            setStreaming(false)
                            runOnUiThread { sendMessage(text, isRetry = true) }
                        } else {
                            runOnUiThread { appendColored("Relay unreachable. Try again later.\n", colorRed) }
                            setStreaming(false)
                        }
                    } else {
                        runOnUiThread { appendColored("\nError: $msg\n", colorRed) }
                        setStreaming(false)
                    }
                }
                .collect { event -> handleEvent(event) }
        }
    }

    private fun queueAttachment(attachment: AttachmentResult) {
        pendingAttachments.add(attachment)
        updateAttachIndicator()
    }

    private fun clearPendingAttachments() {
        pendingAttachments.clear()
        updateAttachIndicator()
    }

    private fun updateAttachIndicator() {
        val count = pendingAttachments.size
        if (count == 0) {
            attachIndicator.visibility = View.GONE
        } else {
            val types = pendingAttachments.joinToString(", ") { att ->
                when {
                    att.mediaType == "application/pdf" -> "PDF"
                    att.mediaType.startsWith("image/") -> "image"
                    else -> "file"
                }
            }
            attachIndicator.text = "\uD83D\uDCCE $count attached ($types)  \u2715 tap to clear"
            attachIndicator.visibility = View.VISIBLE
        }
    }

    private fun sendWithAttachments(text: String, attachments: List<AttachmentResult>) {
        setStreaming(true)

        val label = if (attachments.size == 1) "[1 file]" else "[${attachments.size} files]"
        appendColored("\nYOU: ", colorGreen)
        if (text.isNotEmpty()) append("$label $text\n\n") else append("$label\n\n")
        appendColored("MODEL: ", colorBlue)
        responseStartPos = spannableOutput.length
        responseBuffer.clear()

        val client = relayClient ?: run {
            appendColored("Not connected\n", colorRed)
            setStreaming(false)
            return
        }

        val caption = if (text.isNotEmpty()) text else when {
            attachments.all { it.mediaType == "application/pdf" } ->
                "I've attached ${attachments.size} PDF(s). Please read and summarize."
            attachments.size == 1 -> "What do you see in this image?"
            else -> "I've attached ${attachments.size} images. Please analyze them."
        }

        activeJob = lifecycleScope.launch {
            client.sendMessageWithImages(sessionMgr.currentSessionId, caption, attachments)
                .flowOn(Dispatchers.IO)
                .onCompletion { finalizeResponse(); setStreaming(false) }
                .catch { e ->
                    runOnUiThread { appendColored("\nError: ${e.message}\n", colorRed) }
                    setStreaming(false)
                }
                .collect { event -> handleEvent(event) }
        }
    }

    private fun handleEvent(event: RelayEvent) {
        runOnUiThread {
            when (event) {
                is RelayEvent.Session -> sessionMgr.currentSessionId = event.sessionId
                is RelayEvent.Thinking -> updateThinking(event.activity)
                is RelayEvent.ToolUse -> {
                    val inputSummary = formatToolInput(event.name, event.input)
                    appendColored("  ┌─ ", colorDim)
                    appendColored(event.name, colorToolName)
                    if (inputSummary.isNotEmpty()) {
                        appendColored(" → ", colorDim)
                        appendColored(inputSummary, colorToolInput)
                    }
                    append("\n")
                    updateThinking("${friendlyToolName(event.name)}...")
                }
                is RelayEvent.ToolResult -> {
                    val statusIcon = if (event.isError) "✗" else "✓"
                    val statusColor = if (event.isError) colorToolErr else colorToolOk
                    val summary = summarizeToolResult(event.content, event.isError)
                    appendColored("  └─ ", colorDim)
                    appendColored(statusIcon, statusColor)
                    appendColored(" $summary\n", statusColor)
                }
                is RelayEvent.TextChunk -> responseBuffer.append(event.text)
                is RelayEvent.Error -> appendColored("\n${event.message}\n", colorRed)
                is RelayEvent.Done -> {
                    if (responseBuffer.isNotEmpty()) {
                        append(responseBuffer.toString())
                        responseBuffer.clear()
                    }
                    append("\n")
                }
            }
        }
    }

    // ═══ FILE BROWSER ═══════════════════════════════════════════════════════

    private fun loadDirectory(dirPath: String) {
        val client = relayClient ?: return
        filesLoaded = true

        lifecycleScope.launch {
            val entries = client.listDirectory(dirPath)
            runOnUiThread {
                fileAdapter.submitList(entries)
                filesRecyclerView.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
                filesEmptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                updateFilesPathBar()
            }
        }
    }

    private fun onFileEntryTap(entry: FsEntry) {
        if (entry.type == "directory") {
            pathStack.add(entry.path)
            loadDirectory(entry.path)
        } else {
            // Open file viewer
            val intent = Intent(this, FileViewerActivity::class.java)
            intent.putExtra("filePath", entry.path)
            intent.putExtra("fileName", entry.name)
            startActivityForResult(intent, REQUEST_ASK_CLAUDE)
        }
    }

    private fun onFileEntryLongPress(entry: FsEntry) {
        // Copy @path to clipboard with haptic feedback
        val atPath = "@${entry.path}"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("path", atPath))
        hapticTick()
        Toast.makeText(this, getString(R.string.path_copied, entry.path), Toast.LENGTH_SHORT).show()
    }

    private fun navigateFilesBack() {
        if (pathStack.isNotEmpty()) {
            pathStack.removeAt(pathStack.lastIndex)
            val parentPath = pathStack.lastOrNull() ?: ""
            loadDirectory(parentPath)
        }
    }

    private fun updateFilesPathBar() {
        val currentPath = pathStack.lastOrNull() ?: ""
        if (currentPath.isEmpty()) {
            filesPathBar.text = getString(R.string.files_title)
            filesBackButton.visibility = View.GONE
        } else {
            filesPathBar.text = currentPath.replace("/Users/kylsolutions/", "~/")
            filesBackButton.visibility = View.VISIBLE
        }
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ASK_CLAUDE && resultCode == RESULT_OK) {
            val filePath = data?.getStringExtra("askClaudePath") ?: return
            // Switch to chat tab and send message about the file
            bottomNav.selectedItemId = R.id.nav_chat
            sendMessage("Read and explain @$filePath")
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // If on files tab with path stack, navigate back in files
        if (filesContainer.visibility == View.VISIBLE && pathStack.isNotEmpty()) {
            navigateFilesBack()
            return
        }
        // If on non-chat tab, switch to chat
        if (chatContainer.visibility != View.VISIBLE) {
            bottomNav.selectedItemId = R.id.nav_chat
            return
        }
        super.onBackPressed()
    }

    // ═══ SESSIONS ═════════════════════════════════════════════════════════

    private fun initSessionsViews() {
        sessionsContainer = findViewById(R.id.sessionsContainer)
        sessionsRecyclerView = findViewById(R.id.sessionsRecyclerView)
        sessionsEmptyText = findViewById(R.id.sessionsEmptyText)
        newSessionButton = findViewById(R.id.newSessionButton)

        sessionAdapter = SessionAdapter(
            onTap = { session -> onSessionTap(session) },
            onLongPress = { session -> onSessionLongPress(session) }
        )

        sessionsRecyclerView.layoutManager = LinearLayoutManager(this)
        sessionsRecyclerView.adapter = sessionAdapter

        newSessionButton.setOnClickListener { startNewSession() }
    }

    private fun loadSessions() {
        val client = relayClient ?: return

        lifecycleScope.launch {
            val sessions = client.getSessions()
            runOnUiThread {
                sessionAdapter.submitList(sessions)
                sessionsRecyclerView.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
                sessionsEmptyText.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
                sessionsLoaded = true
            }
        }
    }

    private fun onSessionTap(session: SessionInfo) {
        // Load this session's messages into the chat view
        val client = relayClient ?: return

        lifecycleScope.launch {
            val messages = client.getSessionMessages(session.id)
            runOnUiThread {
                // Clear current chat and replay history
                spannableOutput.clear()
                responseBuffer.clear()
                responseStartPos = -1

                appendColored("Session: ${session.title}\n", colorGreen)
                appendColored("${session.model ?: "unknown"} · ${session.messageCount} messages\n\n", colorDim)

                for (msg in messages) {
                    if (msg.role == "user") {
                        appendColored("YOU: ", colorGreen)
                        append("${msg.text}\n\n")
                    } else {
                        appendColored("MODEL: ", colorBlue)
                        val rendered = markwon.toMarkdown(msg.text)
                        spannableOutput.append(rendered)
                        terminalOutput.text = spannableOutput
                        append("\n")
                    }
                }

                // Switch to this session for future messages
                sessionMgr.currentSessionId = session.id

                // Switch to chat tab
                bottomNav.selectedItemId = R.id.nav_chat
            }
        }
    }

    private fun onSessionLongPress(session: SessionInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete session?")
            .setMessage(session.title)
            .setPositiveButton("Delete") { _, _ -> deleteSessionById(session.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSessionById(sessionId: String) {
        val client = relayClient ?: return

        lifecycleScope.launch {
            val deleted = client.deleteSession(sessionId)
            if (deleted) {
                // If we deleted the active session, clear it
                if (sessionMgr.currentSessionId == sessionId) {
                    sessionMgr.currentSessionId = null
                }
                runOnUiThread {
                    Toast.makeText(this@ChatActivity, R.string.session_deleted, Toast.LENGTH_SHORT).show()
                    loadSessions()
                }
            }
        }
    }

    private fun startNewSession() {
        sessionMgr.currentSessionId = null
        spannableOutput.clear()
        responseBuffer.clear()
        responseStartPos = -1
        terminalOutput.text = ""

        appendColored("KYLIDESCOPE Mobile v1.0\n", colorGreen)
        appendColored("New session started\n\n", colorGreen)

        bottomNav.selectedItemId = R.id.nav_chat
        commandInput.requestFocus()
    }

    // ═══ HELPERS ════════════════════════════════════════════════════════════

    private fun hapticTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        } catch (_: Exception) {
            // Missing VIBRATE permission or other issue — not worth crashing for
        }
    }

    private fun formatToolInput(name: String, input: Map<String, Any?>): String {
        return when (name) {
            "read_file" -> (input["path"] as? String)?.let { shortenPath(it) } ?: ""
            "list_directory" -> (input["path"] as? String)?.let { shortenPath(it) } ?: ""
            "grep_files" -> {
                val pattern = input["pattern"] as? String ?: ""
                val path = (input["path"] as? String)?.let { shortenPath(it) } ?: ""
                "\"$pattern\" in $path"
            }
            "web_fetch" -> (input["url"] as? String) ?: ""
            else -> input.values.firstOrNull()?.toString() ?: ""
        }
    }

    private fun summarizeToolResult(content: String, isError: Boolean): String {
        if (isError) return content.take(80)
        val lines = content.lines()
        return when {
            lines.size == 1 && content.length <= 60 -> content
            lines.size > 1 -> "${lines.size} lines"
            content.length > 1024 -> "${content.length / 1024}KB"
            else -> "${content.length} chars"
        }
    }

    private fun shortenPath(path: String): String {
        return path.replace("/Users/kylsolutions/", "~/")
    }

    private fun friendlyToolName(name: String): String {
        return when (name) {
            "read_file" -> "Reading file"
            "list_directory" -> "Listing directory"
            "grep_files" -> "Searching code"
            "web_fetch" -> "Fetching page"
            else -> "Processing"
        }
    }

    private fun updateThinking(activity: String) {
        thinkingIndicator.visibility = View.VISIBLE
        findViewById<TextView>(R.id.thinkingText).text = activity
    }

    private fun setStreaming(streaming: Boolean) {
        isStreaming = streaming
        runOnUiThread {
            sendButton.setImageResource(if (streaming) R.drawable.ic_stop else R.drawable.ic_send)
            commandInput.isEnabled = !streaming
            if (streaming) {
                thinkingIndicator.visibility = View.VISIBLE
                findViewById<TextView>(R.id.thinkingText).text = "Thinking..."
            } else {
                thinkingIndicator.visibility = View.GONE
            }
        }
    }

    private fun abortStream() {
        if (responseBuffer.isNotEmpty()) {
            append(responseBuffer.toString())
            responseBuffer.clear()
        }
        appendColored("\n[stopped]\n", colorRed)
        activeJob?.cancel()
        activeJob = null
        val sid = sessionMgr.currentSessionId
        if (sid != null) lifecycleScope.launch { relayClient?.abort(sid) }
        finalizeResponse()
        setStreaming(false)
    }

    private fun append(text: String) {
        spannableOutput.append(text)
        terminalOutput.text = spannableOutput
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun appendColored(text: String, color: Int) {
        val start = spannableOutput.length
        spannableOutput.append(text)
        spannableOutput.setSpan(
            ForegroundColorSpan(color), start, spannableOutput.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        terminalOutput.text = spannableOutput
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

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
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            } catch (_: Exception) { }
            responseStartPos = -1
        }
    }

    companion object {
        private const val REQUEST_ASK_CLAUDE = 100
    }
}
