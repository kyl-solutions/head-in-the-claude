# Head-In-The-Claude — Context Load

Load project context with minimal token cost. Read the briefing, know where everything is, pull files on demand.

## Instructions

### Phase 1: Lightweight Briefing (ALWAYS do this)

Print this briefing from memory — do NOT read files yet:

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🤖 HIT-C v0.3.0 — CONTEXT LOADED
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📱 Native Android terminal → Claude Code in your pocket
🔀 Hybrid: Relay (Mac CLI) ↔ Direct API (Anthropic, anywhere)
💾 Room DB (conversations + messages), Keystore encryption
🎨 Warm paper/e-ink theme, Metropolis font, coral accent (#FF6B5B)

ARCHITECTURE:
  MainActivity.kt (930L) — DrawerLayout UI, mode orchestration, adapters
  AnthropicClient.kt (319L) — Direct API, SSE streaming, vision
  RelayClient.kt — Relay mode via Hono.js bridge + getProjects()
  database/ — Room: Conversation + Message entities, DAOs
  relay-server/ — Node.js + Hono on port 3847 + /api/projects

UI COMPONENTS:
  DrawerLayout (left drawer)
  ├── PROJECTS — RecyclerView, loads from relay /api/projects
  ├── SESSIONS — RecyclerView, Room DB conversations
  ├── SHORTCUTS — LinearLayout, 10 shortcuts + translation
  └── SETTINGS — Model picker, Relay URL, API Key, About

  Main Content
  ├── Header (hamburger | logo | badge | gear)
  ├── ScrollView (conversation output, Markwon markdown)
  ├── TabLayout (Chat | Commands)
  └── Input area (camera | input | send)

FEATURES:
  ✅ Direct Anthropic API (SSE streaming + vision)
  ✅ Relay mode (CLI bridge to Mac)
  ✅ Projects browser (relay-connected, tap → inject path)
  ✅ Sessions list UI (tap → load, long-press → delete)
  ✅ Drawer with Projects/Sessions/Shortcuts/Settings
  ✅ Model picker (Sonnet/Opus/Haiku)
  ✅ Relay URL editor
  ✅ SQLite persistence (conversations + messages)
  ✅ Encrypted API key (Android Keystore)
  ✅ Screenshot → Vision analysis
  ✅ 10 shortcuts + Zulu/Sesotho translation
  ✅ Warm paper theme (e-ink aesthetic)
  ✅ Metropolis font family

PENDING:
  ⬜ Search across conversations
  ⬜ Export (markdown/JSON)
  ⬜ Voice input
  ⬜ Editable custom shortcuts
  ⬜ Conversation message history loading
```

### Phase 2: File Map (print but DON'T read)

Print this map so you know where to look — only read when you need to edit:

```
FILE MAP (read on demand):

CORE (read first when working on features):
  app/src/main/java/com/kylsolutions/hitc/
  ├── MainActivity.kt              — DrawerLayout, adapters, all UI logic
  ├── AnthropicClient.kt           — Direct API client (SSE)
  ├── RelayClient.kt               — Relay bridge + getProjects()
  ├── SetupActivity.kt             — First-run API key flow
  ├── ApiKeyManager.kt             — Encrypted key + model storage
  ├── ClaudeEvent.kt               — Event type definitions
  ├── TranslateActivity.kt         — Zulu/Sesotho translation
  ├── ScreenshotHelper.kt          — Camera capture
  └── HitcSessionManager.kt        — Session ID + relay URL tracking

DATA LAYER:
  ├── database/
  │   ├── ConversationDatabase.kt  — Room DB setup
  │   ├── Conversation.kt          — Entity: conversations
  │   ├── Message.kt               — Entity: messages
  │   ├── ConversationDao.kt       — Conversation queries
  │   └── MessageDao.kt            — Message queries
  └── repository/
      └── SessionRepository.kt     — Data access layer

LAYOUTS:
  app/src/main/res/layout/
  ├── activity_main.xml            — DrawerLayout + main content (484L)
  ├── item_project.xml             — Project list item
  ├── item_session.xml             — Session list item
  ├── item_shortcut.xml            — Drawer shortcut item
  ├── activity_setup.xml           — Setup screen
  ├── activity_translate.xml       — Translation UI
  └── dialog_language_picker.xml   — Language selection

RESOURCES:
  app/src/main/res/values/
  ├── strings.xml                  — All string resources
  ├── colors.xml                   — Warm paper palette (#E8E0D4, #FF6B5B)
  └── themes.xml                   — Material theme config

  app/src/main/res/drawable/
  ├── hitc_logo.png                — Header logo (from assets)
  ├── ic_menu.xml                  — Hamburger icon
  ├── ic_settings.xml              — Gear icon
  ├── ic_send.xml                  — Send arrow
  ├── ic_new_session.xml           — New session icon
  ├── bg_connection_badge.xml      — Badge background
  ├── bg_input_field.xml           — Input field background
  └── bg_shortcut_button.xml       — Shortcut pill background

  app/src/main/res/font/
  └── metropolis*.ttf              — Metropolis font family

RELAY SERVER:
  relay-server/
  ├── server.js                    — Hono HTTP + /api/projects endpoint
  ├── lib/auth.js                  — Token auth
  ├── lib/claude-bridge.js         — CLI bridge
  ├── lib/session-manager.js       — Session state
  └── package.json

BUILD:
  app/build.gradle.kts             — Dependencies (Room, OkHttp, Markwon, Material)
  build.gradle.kts                 — Root (KSP plugin)
  settings.gradle.kts              — Module config
  app/src/main/AndroidManifest.xml — 3 activities
```

### Phase 3: On-Demand (ONLY when working)

When the user says what they're working on:
- **UI work** → Read `MainActivity.kt` + `activity_main.xml` + `colors.xml` + `themes.xml`
- **Drawer** → Read `MainActivity.kt` (adapters at bottom) + `item_*.xml` layouts
- **API/streaming** → Read `AnthropicClient.kt` + `ClaudeEvent.kt`
- **Database** → Read `ConversationDatabase.kt` + entities + DAOs + `SessionRepository.kt`
- **Relay** → Read `RelayClient.kt` + `relay-server/server.js` + `relay-server/lib/*.js`
- **New feature** → Read `AndroidManifest.xml` + `build.gradle.kts` + the layer you're adding to
- **Bug fix** → Read the specific file(s) related to the bug

**Rule:** Never read more than 6-8 files at once. Pull what you need, work, pull more if needed.

### Phase 4: Ask

"What are we working on?"
