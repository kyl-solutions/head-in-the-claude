# Head-In-The-Claude (HIT-C) 🌩️

**Version:** 0.2.0
**Status:** ✅ Hybrid Relay + Direct API with Intelligent Fallback

*A native Android terminal app for Claude Code — optimized for physical keyboards like the Clicks phone communicator. Hybrid architecture: relay mode for full tool access on your Mac, with intelligent fallback to direct API when away from your desk.*

---

## What Is This?

HIT-C is Claude Code in your pocket. The first mobile client with **intelligent mode switching**:

### 🔌 Relay Mode (On Local Network)
- ✅ Full Claude Code tool access (file operations, bash, git, etc.)
- ✅ Executes on your Mac dev environment
- ✅ Custom shortcuts (ls, git status, read file, run tests, etc.)
- ✅ Markdown rendering with syntax highlighting
- ✅ Fastest response times (local relay)

### 🌐 Direct API Mode (Anywhere Else)
- ✅ Chat anywhere (WiFi or cellular)
- ✅ Full conversation persistence (SQLite)
- ✅ Screenshot analysis (vision API)
- ✅ Works without relay server
- ✅ Automatic fallback when relay unavailable

### Why?

Because sometimes the best ideas come when you're **not** at your desk. With HIT-C and a physical keyboard (like the Clicks phone), you can:
- **At home:** Full dev environment access via relay (bash, git, file operations)
- **On the go:** Seamless fallback to direct API for chat + vision
- **Everywhere:** Custom shortcuts, markdown rendering, persistent conversations
- Keep your head in the cloud even when you're on the move

> *"Any sufficiently advanced technology is indistinguishable from magic."* — Arthur C. Clarke

---

## Features

### ✅ Implemented (v0.2.0)

**Core Architecture:**
- **Hybrid Mode Switching** — Auto-detects relay availability, graceful fallback
- **Relay Client** — Full Claude Code tool execution on Mac (bash, git, files)
- **Direct API Client** — Anthropic API integration for anywhere access
- **Connection Badge** — Real-time mode indicator (relay vs. direct)

**Terminal Experience:**
- **Custom Shortcuts Bar** — One-tap common commands (ls, git status, read file, run tests, explain, fix, refactor, summarize)
- **Markdown Rendering** — Markwon-powered formatting with syntax highlighting
- **Colored Output** — Smart syntax coloring (tools, errors, success, prompts)
- **Streaming Responses** — Real-time SSE for both relay and direct modes
- **Terminal UI** — Green-on-black monospace aesthetic (JetBrains Mono)

**Mobile-First:**
- **Screenshot Analysis** — Camera → Vision API (works in both modes)
- **Persistent Sessions** — SQLite database (direct mode only)
- **Secure Storage** — Encrypted API key storage (Android Keystore)
- **Physical Keyboard Optimized** — Built for Clicks Communicator
- **Haptic Feedback** — Tactile shortcut button feedback

**Developer Features:**
- **Auto-Save** — Every message saved (direct mode)
- **Multi-Model** — Choose Sonnet/Opus/Haiku
- **Session Management** — New session button, abort mid-stream
- **Smart Buffering** — Auto-trim at 50KB to prevent OOM

### 🚧 Coming Soon (v0.3.0+)
- Conversation list UI (view all past conversations)
- Search conversations
- Export as markdown
- Voice input
- SSH keepalive improvements (prevent relay disconnects)
- Tmux/screen integration for persistent relay sessions

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| Language | Kotlin |
| UI | Android Views + Material Components |
| Markdown | Markwon (rendering engine) |
| Networking | OkHttp + SSE (Server-Sent Events) |
| Database | Room (SQLite wrapper) |
| Security | Android Keystore + EncryptedSharedPreferences |
| Fonts | JetBrains Mono |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |

---

## Project Structure

```
head-in-the-claude/
├── app/
│   ├── src/main/
│   │   ├── java/com/kylsolutions/hitc/
│   │   │   └── MainActivity.kt          ← Main terminal activity
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml    ← Terminal UI layout
│   │   │   ├── values/
│   │   │   │   ├── strings.xml
│   │   │   │   ├── colors.xml           ← Terminal color scheme
│   │   │   │   └── themes.xml
│   │   │   └── xml/
│   │   │       ├── backup_rules.xml
│   │   │       └── data_extraction_rules.xml
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts                  ← App dependencies
│   └── proguard-rules.pro
├── build.gradle.kts                       ← Root build config
├── settings.gradle.kts
└── README.md                              ← You are here
```

---

## Setup

### Prerequisites

1. **Android Studio** (latest stable)
2. **Android device** with API 26+ (or emulator)
3. **Anthropic API Key** from [console.anthropic.com](https://console.anthropic.com/settings/keys)
4. **(Optional)** Relay server running on Mac for tool access

### Quick Start

#### Option 1: Direct API Only (Simplest)

1. **Build the app:**
   ```bash
   cd ~/Developer/kyl-solutions/head-in-the-claude
   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
   ./gradlew assembleDebug
   ```

2. **Install on device:**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. **First launch:**
   - App opens to Setup screen
   - Paste your Anthropic API key (starts with `sk-ant-api03-...`)
   - Select model (Sonnet recommended)
   - Save & Continue

4. **Start chatting:**
   - App connects in **direct API mode**
   - Badge shows: "Direct API"
   - Full chat + vision support
   - No tool execution (file ops, bash, etc.)

#### Option 2: Hybrid Relay + Direct API (Full Power)

1. **Start relay server on Mac:**
   ```bash
   # See relay-server/README.md for setup
   cd ~/Developer/kyl-solutions/head-in-the-claude/relay-server
   npm install
   npm start
   ```

2. **Configure relay connection:**
   - Open `HitcSessionManager.kt`
   - Set your Mac's local IP in `relayUrl`
   - Default: `http://192.168.1.100:3001`

3. **Build and install** (same as Option 1)

4. **Launch app:**
   - App attempts relay connection first
   - If successful → **Relay mode** (full tool access)
   - If failed → **Direct API fallback** (chat only)
   - Badge shows current mode

---

## Usage

### Understanding Modes

**Connection Badge** (top-right) shows current mode:
- 🟢 **"Relay"** — Connected to Mac, full tool access (bash, git, file ops)
- 🟡 **"Direct API"** — Standalone mode, chat + vision only

### Using Shortcuts

The **shortcuts bar** at the top provides one-tap common commands:

| Shortcut | Action |
|----------|--------|
| **ls** | List files in current directory |
| **git status** | Show git working tree status |
| **read file** | Prompts for file path, then reads it |
| **run tests** | Execute test suite |
| **explain** | Explain the code you're looking at |
| **fix** | Fix the issue in current code |
| **refactor** | Refactor for clarity and performance |
| **summarize** | Summarize what this project does |

**Note:** Shortcuts execute via relay tools when available, otherwise send as chat prompts.

### Sending Messages

1. **Type in the input field** at bottom
2. **Tap Send** or **press Enter** (physical keyboard)
3. **Watch response stream** in terminal
4. **Markdown auto-renders** when response completes

### Taking Screenshots

1. **Tap camera FAB** (floating action button)
2. **Grant camera permission** (first time)
3. **Take photo**
4. **Preview dialog** → choose Send/Retake/Cancel
5. **Claude analyzes** via vision API

### Managing Sessions

- **New Session button** — Start fresh conversation (clears terminal, resets state)
- **Abort** — Cancel mid-stream (implicit when starting new session)
- **History** — Direct mode saves all messages to SQLite (relay mode is stateless)

### Using with Physical Keyboard (Clicks Communicator)

Optimized for tactile typing:
- **Enter** — Send message
- **Haptic feedback** — Shortcut button taps
- **Fast navigation** — No need to lift fingers from keyboard

---

## Roadmap

### v0.1.0 ✅
- [x] SSH relay terminal
- [x] Basic terminal UI
- [x] Screenshot capture

### v0.2.0 ✅ (Current)
- [x] Hybrid relay + direct API architecture
- [x] Custom shortcuts bar (8 common commands)
- [x] Markdown rendering with syntax highlighting
- [x] Colored output (tools, errors, prompts)
- [x] Connection badge (mode indicator)
- [x] Intelligent fallback (relay → direct)
- [x] Screenshot vision analysis (both modes)
- [x] SQLite persistence (direct mode)
- [x] Encrypted API key storage
- [x] Haptic feedback

### v0.3.0 (Next)
- [ ] Conversation list UI (browse history)
- [ ] Search conversations
- [ ] Export as markdown
- [ ] Voice input
- [ ] SSH keepalive (prevent relay disconnects)
- [ ] Tmux integration (persistent relay sessions)
- [ ] Editable shortcuts (customize your workflow)

### v1.0.0 (Vision)
- [ ] Full Claude Code mobile client
- [ ] Multi-tab sessions
- [ ] Collaborative mode
- [ ] Offline command queue
- [ ] Cloud sync (optional)

---

## Security & Privacy

### API Key Storage ✅
- **Encrypted at rest** — Android Keystore + EncryptedSharedPreferences
- **AES256-GCM encryption** — Industry standard
- **Never transmitted** — Except to Anthropic API
- **Auto-deleted** — On app uninstall

### Conversation Data ✅
- **Local SQLite database** — App-private storage
- **Not backed up** — Excluded from Android cloud backup
- **Screenshots private** — Stored in app-private directory
- **No telemetry** — Zero tracking

### Network Security ✅
- **HTTPS only** — Direct API mode enforced
- **Certificate pinning** — OkHttp default behavior
- **Relay localhost exception** — Cleartext allowed for local relay only

### What's NOT Private
- ⚠️ **Relay mode sends to your Mac** — Messages processed via your local Claude CLI
- ⚠️ **Direct mode sends to Anthropic** — Standard API usage, subject to Anthropic's privacy policy
- ⚠️ **Screenshots analyzed** — Vision API processes images (both modes)

---

## Troubleshooting

### Relay Mode Issues

**"Relay unavailable" — Falls back to direct API**
- Check relay server is running: `curl http://YOUR_MAC_IP:3001/health`
- Verify Mac and phone on same WiFi network
- Check firewall isn't blocking port 3001
- Look at relay server logs for errors

**Relay disconnects during long tasks**
- Use tmux/screen on Mac for persistent sessions
- Reduce Android battery optimization for HIT-C
- Keep phone screen on (prevents background kills)

### Direct API Mode Issues

**"Failed to initialize" on first launch**
- Check you entered valid API key (starts with `sk-ant-api03-`)
- Verify internet connection
- Try re-entering API key in SetupActivity

**Rate limit errors (429)**
- You've hit Anthropic API usage limits
- Wait a few minutes and try again
- Check your API plan at console.anthropic.com

**Network timeout**
- Switch from cellular to WiFi
- Check Anthropic status: status.anthropic.com

### Screenshot Issues

**Camera permission denied**
- Settings → Apps → Head-In-The-Claude → Permissions → Camera → Allow

**Screenshot not sending**
- Check connection (relay or direct API)
- Verify internet connectivity
- Look for error messages in terminal output

---

## Development

### Building from Command Line

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires keystore)
./gradlew assembleRelease

# Install to connected device
./gradlew installDebug
```

### Code Style

- Kotlin official style guide
- 4 spaces (no tabs)
- Max line length: 120 characters

---

## License

Internal KYL Solutions tool.

---

## Author

**KYL Solutions** — kabelo@kyl.solutions

Built because the best code happens when you're *not* at your desk.

---

## Acknowledgments

- [Markwon](https://github.com/noties/Markwon) for beautiful markdown rendering
- [OkHttp](https://square.github.io/okhttp/) for reliable HTTP + SSE
- [Room](https://developer.android.com/training/data-storage/room) for elegant SQLite persistence
- [Clicks Technology](https://clicks.tech) for inspiring the physical keyboard dream
- Claude for being worth building a mobile client for

---

## Philosophy

> *"Any sufficiently advanced technology is indistinguishable from magic."* — Arthur C. Clarke

HIT-C is magic because it just works. Relay mode when you're home. Direct API when you're out. Custom shortcuts. Markdown rendering. Vision analysis. All from your pocket.

The best code happens when you're **not** at your desk. This is the tool that makes that possible.

---

**Status:** ✅ v0.2.0 — Hybrid relay + direct API working
**Hardware:** Optimized for Clicks Communicator (launching 2026)
**Built by:** KYL Solutions — kabelo@kyl.solutions
