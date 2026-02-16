# Mobile Native Claude Client — Full Specification

**Version:** 0.2.0
**Target:** Fully mobile Claude experience without relay server
**Goal:** Direct Claude API integration + persistent session memory

---

## Overview

Current architecture (v0.1.0):
```
Android App → Relay Server (iMac) → Claude CLI → Anthropic API
```

Target architecture (v0.2.0):
```
Android App → Anthropic API (direct)
            ↓
         SQLite (local session storage)
```

---

## 1. Direct Claude API Integration

### 1.1 Architecture Changes

**Remove:**
- ✗ `RelayClient.kt` (HTTP/SSE to relay server)
- ✗ `HitcSessionManager.kt` (current SharedPreferences-based manager)
- ✗ Relay server dependency (no iMac needed)

**Add:**
- ✅ `AnthropicClient.kt` — Direct Anthropic API client
- ✅ `ConversationDatabase.kt` — SQLite for message history
- ✅ `SessionRepository.kt` — Persistent conversation management
- ✅ `ClaudeMessageParser.kt` — Stream parsing for SSE events

### 1.2 API Client Design

**File:** `app/src/main/java/com/kylsolutions/hitc/AnthropicClient.kt`

```kotlin
class AnthropicClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-5-20250929"
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(300, TimeUnit.SECONDS) // Long-running responses
        .build()

    private val baseUrl = "https://api.anthropic.com/v1"

    /**
     * Send a message to Claude and stream the response.
     * Returns a Flow of ClaudeEvent (text chunks, tool use, etc.)
     */
    fun sendMessage(
        conversationHistory: List<Message>,
        systemPrompt: String? = null
    ): Flow<ClaudeEvent>

    /**
     * Send a message with an image attachment.
     */
    fun sendMessageWithImage(
        conversationHistory: List<Message>,
        imageBase64: String,
        mediaType: String = "image/jpeg",
        prompt: String
    ): Flow<ClaudeEvent>

    /**
     * Cancel the active request.
     */
    fun abort()
}
```

**Key Features:**
- ✅ Streaming via Server-Sent Events (SSE)
- ✅ Vision support (screenshots → base64 → API)
- ✅ Tool use parsing (show tool names in terminal)
- ✅ Abort mid-stream (cancel button)
- ✅ Retry logic with exponential backoff

**API Endpoint:**
```
POST https://api.anthropic.com/v1/messages
```

**Request Format:**
```json
{
  "model": "claude-sonnet-4-5-20250929",
  "max_tokens": 4096,
  "stream": true,
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "What's in this image?"
        },
        {
          "type": "image",
          "source": {
            "type": "base64",
            "media_type": "image/jpeg",
            "data": "iVBORw0KGgoAAAANSUhEUg..."
          }
        }
      ]
    }
  ]
}
```

**Response Stream (SSE):**
```
event: message_start
data: {"type":"message_start","message":{"id":"msg_123","role":"assistant"}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"I see"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" a screenshot"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

event: message_stop
data: {"type":"message_stop"}
```

### 1.3 Event Parsing

**File:** `app/src/main/java/com/kylsolutions/hitc/ClaudeEvent.kt`

```kotlin
sealed class ClaudeEvent {
    data class MessageStart(val id: String) : ClaudeEvent()
    data class TextDelta(val text: String) : ClaudeEvent()
    data class ToolUse(val toolName: String, val input: String) : ClaudeEvent()
    data class Error(val message: String, val type: String?) : ClaudeEvent()
    object MessageStop : ClaudeEvent()
}
```

**Mapping from API events:**
- `message_start` → `ClaudeEvent.MessageStart`
- `content_block_delta` (text) → `ClaudeEvent.TextDelta`
- `content_block_delta` (tool_use) → `ClaudeEvent.ToolUse`
- `error` → `ClaudeEvent.Error`
- `message_stop` → `ClaudeEvent.MessageStop`

---

## 2. Persistent Session Memory

### 2.1 Database Schema

**File:** `app/src/main/java/com/kylsolutions/hitc/database/ConversationDatabase.kt`

**Technology:** Room (Android SQLite ORM)

**Tables:**

#### Conversations
```sql
CREATE TABLE conversations (
    id TEXT PRIMARY KEY,               -- UUID
    title TEXT NOT NULL,               -- Auto-generated from first message
    created_at INTEGER NOT NULL,       -- Unix timestamp (ms)
    updated_at INTEGER NOT NULL,       -- Unix timestamp (ms)
    message_count INTEGER DEFAULT 0,
    model TEXT DEFAULT 'claude-sonnet-4-5-20250929'
)
```

#### Messages
```sql
CREATE TABLE messages (
    id TEXT PRIMARY KEY,               -- UUID
    conversation_id TEXT NOT NULL,     -- Foreign key
    role TEXT NOT NULL,                -- 'user' or 'assistant'
    content TEXT NOT NULL,             -- Full message content (JSON for complex content)
    created_at INTEGER NOT NULL,       -- Unix timestamp (ms)
    has_image BOOLEAN DEFAULT 0,       -- Whether this message included an image
    image_path TEXT,                   -- Local file path if screenshot was attached
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
)
```

#### ToolUses (Optional — for analytics)
```sql
CREATE TABLE tool_uses (
    id TEXT PRIMARY KEY,
    message_id TEXT NOT NULL,
    tool_name TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE
)
```

**Indexes:**
```sql
CREATE INDEX idx_messages_conversation ON messages(conversation_id);
CREATE INDEX idx_messages_created_at ON messages(created_at);
CREATE INDEX idx_conversations_updated_at ON conversations(updated_at);
```

### 2.2 Repository Pattern

**File:** `app/src/main/java/com/kylsolutions/hitc/repository/SessionRepository.kt`

```kotlin
class SessionRepository(private val db: ConversationDatabase) {

    /**
     * Create a new conversation.
     */
    suspend fun createConversation(title: String? = null): Conversation

    /**
     * Get conversation by ID with all messages.
     */
    suspend fun getConversation(id: String): ConversationWithMessages?

    /**
     * List all conversations (sorted by updated_at DESC).
     */
    fun getAllConversations(): Flow<List<Conversation>>

    /**
     * Add a user message to a conversation.
     */
    suspend fun addUserMessage(
        conversationId: String,
        content: String,
        imagePath: String? = null
    ): Message

    /**
     * Add an assistant message to a conversation.
     */
    suspend fun addAssistantMessage(
        conversationId: String,
        content: String
    ): Message

    /**
     * Delete a conversation and all its messages.
     */
    suspend fun deleteConversation(id: String)

    /**
     * Update conversation title (auto-generate from first message).
     */
    suspend fun updateTitle(conversationId: String, title: String)

    /**
     * Get message history for API (last N messages).
     */
    suspend fun getMessageHistory(
        conversationId: String,
        limit: Int = 50
    ): List<ApiMessage>
}
```

**Data Models:**

```kotlin
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val model: String = "claude-sonnet-4-5-20250929"
)

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val hasImage: Boolean = false,
    val imagePath: String? = null
)

// For API requests (includes image data if present)
data class ApiMessage(
    val role: String,
    val content: List<ContentBlock>
)

sealed class ContentBlock {
    data class Text(val text: String) : ContentBlock()
    data class Image(
        val base64: String,
        val mediaType: String = "image/jpeg"
    ) : ContentBlock()
}
```

### 2.3 Session Lifecycle

**App Flow:**

1. **App Start** → Load most recent conversation OR create new
2. **User sends message** → Save to DB → Send to API → Stream response → Save assistant message
3. **User taps "New Session"** → Create new conversation → Switch to it
4. **User taps conversation list** → Load selected conversation → Restore full history

**Memory Management:**

- **Keep in RAM:** Current conversation only (last 50 messages)
- **Store on disk:** All conversations forever (until user deletes)
- **Image storage:** Save screenshots to app-private storage, reference by path

### 2.4 Conversation List UI

**New Screen:** `ConversationListActivity.kt`

**Layout:**
```
╔════════════════════════════════════╗
║  Conversations                [+]  ║
╠════════════════════════════════════╣
║                                    ║
║  📝 Fix login bug                  ║
║     "Can you help me debug..."     ║
║     2 min ago • 8 messages         ║
║                                    ║
║  ────────────────────────────────  ║
║                                    ║
║  🖼️  Screenshot analysis           ║
║     "What's in this image?"        ║
║     1 hour ago • 3 messages        ║
║                                    ║
║  ────────────────────────────────  ║
║                                    ║
║  💡 Refactor user service          ║
║     "How can I improve..."         ║
║     Yesterday • 24 messages        ║
║                                    ║
╚════════════════════════════════════╝
```

**Features:**
- ✅ Auto-generated titles (from first user message, truncated to 40 chars)
- ✅ Preview of first message
- ✅ Timestamp (relative: "2 min ago", "Yesterday", "Jan 15")
- ✅ Message count badge
- ✅ Icon if conversation has images
- ✅ Swipe to delete
- ✅ Tap to load conversation

**Navigation:**
- Tap "hamburger menu" in MainActivity → Opens ConversationListActivity
- Tap conversation → Loads it into MainActivity terminal
- Tap [+] → Creates new conversation

---

## 3. Updated MainActivity

### 3.1 Architecture Changes

**Before (v0.1.0):**
```kotlin
class MainActivity {
    private var relayClient: RelayClient? = null
    private var sessionMgr: HitcSessionManager

    fun sendMessage() {
        relayClient?.sendMessage(sessionId, message)
    }
}
```

**After (v0.2.0):**
```kotlin
class MainActivity {
    private val claudeClient: AnthropicClient by lazy {
        AnthropicClient(apiKey = getApiKey())
    }

    private val sessionRepo: SessionRepository by lazy {
        SessionRepository(ConversationDatabase.getInstance(this))
    }

    private var currentConversation: Conversation? = null

    fun sendMessage() {
        // 1. Save user message to DB
        val userMsg = sessionRepo.addUserMessage(conversationId, message)

        // 2. Get conversation history (last 50 messages)
        val history = sessionRepo.getMessageHistory(conversationId)

        // 3. Send to Claude API
        claudeClient.sendMessage(history)
            .collect { event ->
                when (event) {
                    is ClaudeEvent.TextDelta -> appendOutput(event.text)
                    is ClaudeEvent.MessageStop -> {
                        // Save assistant response to DB
                        sessionRepo.addAssistantMessage(conversationId, assistantResponse)
                    }
                }
            }
    }
}
```

### 3.2 New UI Elements

**Add to `activity_main.xml`:**

1. **Menu Button** (top-left) → Opens ConversationListActivity
2. **Conversation Title** (top-center) → Shows current conversation title
3. **Model Badge** (top-right) → Shows current model (e.g., "Sonnet 4.5")

**Updated Layout:**
```
╔════════════════════════════════════╗
║ ☰  Fix login bug      [Sonnet 4.5]║
╠════════════════════════════════════╣
║                                    ║
║  Terminal Output Area              ║
║  (scrollable, green-on-black)      ║
║                                    ║
║                                    ║
║                                    ║
╠════════════════════════════════════╣
║  [Input Field]            [Send] 📸║
╚════════════════════════════════════╝
```

---

## 4. API Key Management

### 4.1 Secure Storage

**Use Android Keystore for encryption:**

**File:** `app/src/main/java/com/kylsolutions/hitc/ApiKeyManager.kt`

```kotlin
class ApiKeyManager(context: Context) {
    private val prefs = context.getSharedPreferences("hitc_secure", Context.MODE_PRIVATE)
    private val encryptor = EncryptedSharedPreferences.create(...)

    fun saveApiKey(key: String)
    fun getApiKey(): String?
    fun hasApiKey(): Boolean
}
```

### 4.2 First-Run Setup

**New Screen:** `SetupActivity.kt`

**Flow:**
1. App launches
2. Check if API key exists
3. If not → Show SetupActivity
4. User pastes API key (from Anthropic Console)
5. Key saved to encrypted storage
6. Redirect to MainActivity

**UI:**
```
╔════════════════════════════════════╗
║  Welcome to HIT-C 🌩️               ║
╠════════════════════════════════════╣
║                                    ║
║  To get started, you need an       ║
║  Anthropic API key.                ║
║                                    ║
║  Get one at:                       ║
║  console.anthropic.com/settings    ║
║                                    ║
║  ┌────────────────────────────┐   ║
║  │ sk-ant-api03-...           │   ║
║  └────────────────────────────┘   ║
║                                    ║
║           [Save & Continue]        ║
║                                    ║
╚════════════════════════════════════╝
```

---

## 5. Migration Plan

### 5.1 Backward Compatibility

**v0.1.0 → v0.2.0 transition:**

1. **Keep relay mode as fallback** (optional)
   - Add settings toggle: "Use Relay Server" vs "Direct API"
   - Useful for testing or if user doesn't have API key

2. **Auto-create first conversation**
   - On first launch with API key, create default conversation
   - Migrate any existing session history (if applicable)

### 5.2 Phased Rollout

**Phase 1:** Direct API client (no persistence)
- ✅ `AnthropicClient.kt` working
- ✅ Stream parsing working
- ✅ Screenshot support working
- ✅ No database yet (in-memory only)

**Phase 2:** Add persistence
- ✅ Room database setup
- ✅ SessionRepository implementation
- ✅ Auto-save messages

**Phase 3:** Conversation management
- ✅ ConversationListActivity
- ✅ Load/delete conversations
- ✅ Auto-title generation

**Phase 4:** Polish
- ✅ Settings screen (model selection, API key management)
- ✅ Export conversations (share as markdown)
- ✅ Search conversations

---

## 6. Performance Considerations

### 6.1 Database Optimization

**Strategies:**
- ✅ Use Room's `@Transaction` for multi-step operations
- ✅ Lazy-load message content (only load when conversation opened)
- ✅ Index on `updated_at` for fast recent-conversation queries
- ✅ Limit API history to last 50 messages (older messages stay in DB but not sent to API)

### 6.2 Memory Management

**Image Storage:**
- ✅ Don't embed base64 in database (store as files)
- ✅ Compress screenshots before saving (JPEG quality 85%)
- ✅ Auto-delete images older than 30 days (optional setting)

**Message Buffer:**
- ✅ Current 50KB limit in `appendOutput()` stays
- ✅ For long conversations, paginate message loading

### 6.3 Network Efficiency

**API Rate Limits (Anthropic):**
- Tier 1: 50 req/min, 40K tokens/min
- Tier 2: 1000 req/min, 80K tokens/min

**Client-side throttling:**
- ✅ Debounce send button (prevent double-send)
- ✅ Show error if rate limit hit
- ✅ Retry with exponential backoff (2s, 4s, 8s)

---

## 7. Testing Strategy

### 7.1 Unit Tests

**Files to test:**
- `AnthropicClient.kt` → Mock OkHttp responses
- `SessionRepository.kt` → Mock Room database
- `ClaudeMessageParser.kt` → Test SSE event parsing

### 7.2 Integration Tests

**Scenarios:**
1. Send message → Receive streaming response → Save to DB
2. Send image → API returns vision analysis → Save with image reference
3. Create conversation → Add 100 messages → Query history
4. Delete conversation → Verify cascade delete of messages

### 7.3 Manual Testing Checklist

- [ ] Fresh install → API key setup flow
- [ ] Send text message → Stream displays correctly
- [ ] Send screenshot → Claude analyzes it
- [ ] Create 3 conversations → List shows all 3
- [ ] Load old conversation → Full history restored
- [ ] Delete conversation → Removed from list + DB
- [ ] App restart → Last conversation loads
- [ ] Offline mode → Show error, retry when online
- [ ] Abort mid-stream → Request cancelled

---

## 8. Dependencies

### 8.1 New Gradle Dependencies

**Add to `app/build.gradle.kts`:**

```kotlin
dependencies {
    // Existing
    implementation("com.jcraft:jsch:0.1.55")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // NEW: Room for SQLite
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // NEW: Encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // NEW: Lifecycle (for Flow + LiveData)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.8.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
}
```

### 8.2 Permissions

**Update `AndroidManifest.xml`:**

```xml
<!-- Existing -->
<uses-permission android:name="android.permission.CAMERA" />

<!-- NEW: Internet (for API calls) -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- NEW: Network state (for offline detection) -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

---

## 9. Future Enhancements (v0.3.0+)

### 9.1 Multi-Model Support

**Add model switcher:**
- Claude Opus 4.5 (most capable)
- Claude Sonnet 4.5 (default, balanced)
- Claude Haiku 4 (fastest, cheapest)

**UI:** Dropdown in settings or model badge tap

### 9.2 Voice Input

**Add speech-to-text:**
- Android SpeechRecognizer API
- Hold mic button → Dictate message → Auto-send

### 9.3 Conversation Search

**Full-text search:**
- Search across all conversations
- Highlight matches in message content
- Filter by date range, model, has-image

### 9.4 Export & Share

**Export formats:**
- Markdown (conversation transcript)
- JSON (full conversation object)
- Share to other apps (copy link, email, etc.)

### 9.5 Collaborative Sessions

**Sync across devices:**
- Cloud backup (Firebase/Supabase)
- Share conversation URL
- Multi-user chat rooms

---

## 10. Success Metrics

**v0.2.0 is successful if:**

1. ✅ App works **offline-first** (no relay server needed)
2. ✅ API key setup takes < 1 minute
3. ✅ Conversations persist across app restarts
4. ✅ Screenshot → Claude analysis < 10 seconds
5. ✅ Conversation list loads < 500ms
6. ✅ No data loss (messages saved reliably)
7. ✅ Stream latency < relay mode (direct API is faster)

**Stretch goals:**
- 🎯 Multi-device sync (cloud backup)
- 🎯 Voice input working
- 🎯 Conversation search implemented

---

## 11. Timeline Estimate

**Development phases:**

| Phase | Deliverable | Effort |
|-------|-------------|--------|
| 1 | AnthropicClient.kt (direct API) | Already started |
| 2 | Database schema + Room setup | 1-2 days |
| 3 | SessionRepository + tests | 1 day |
| 4 | MainActivity integration | 1 day |
| 5 | ConversationListActivity | 1 day |
| 6 | API key setup flow | Half day |
| 7 | Testing + polish | 1 day |

**Total:** ~6-7 days of focused development

---

## 12. Open Questions

1. **Should we keep relay mode as fallback?**
   - Pro: Useful for users without API key
   - Con: Extra code to maintain

2. **How long to keep conversation history?**
   - Option A: Forever (user deletes manually)
   - Option B: Auto-delete after 30 days
   - Option C: Ask user on first setup

3. **Should screenshots be uploaded immediately or require confirmation?**
   - Current: Immediate upload with default prompt
   - Alternative: Preview dialog first

4. **Model selection: settings or per-conversation?**
   - Settings: One model for all conversations
   - Per-conversation: Choose model when creating session

---

**Status:** 📝 Specification complete — ready for implementation
**Last Updated:** 2026-02-15
**Next Step:** Begin Phase 1 (AnthropicClient.kt implementation)
