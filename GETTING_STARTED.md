# Getting Started with HIT-C v0.2.0

**Quick guide to building and using Head-In-The-Claude with direct Anthropic API integration.**

---

## Prerequisites

### 1. Development Environment

**Required:**
- ✅ Android Studio (latest stable)
- ✅ Java 17+ (for Gradle)
- ✅ Android SDK 26+ (Android 8.0+)

**Check Java version:**
```bash
java -version
```

If you need Java 17:
```bash
# macOS (Homebrew)
brew install openjdk@17

# Set JAVA_HOME
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

### 2. Anthropic API Key

**Get your API key:**
1. Visit https://console.anthropic.com/settings/keys
2. Click "Create Key"
3. Copy the key (starts with `sk-ant-api03-...`)
4. **Save it** — you can't view it again!

**Pricing (as of Feb 2026):**
- Sonnet 4.5: $3/MTok input, $15/MTok output
- Opus 4.5: $15/MTok input, $75/MTok output
- Haiku 4: $0.80/MTok input, $4/MTok output

*Average conversation (~100 messages): $0.50-2.00*

### 3. Android Device

**Minimum:**
- Android 8.0 (SDK 26)
- 100 MB storage
- Internet connection (WiFi or cellular)

**Recommended:**
- Physical keyboard (Clicks phone, Bluetooth keyboard, etc.)
- Android 11+ for best performance
- Good network connection for streaming

---

## Build the App

### 1. Clone or Navigate to Project

```bash
cd /Users/kylsolutions/Developer/kyl-solutions/head-in-the-claude
```

### 2. Sync Gradle Dependencies

```bash
./gradlew build
```

**First build:** May take 5-10 minutes (downloads dependencies)

### 3. Build Debug APK

```bash
./gradlew assembleDebug
```

**Output:**
```
app/build/outputs/apk/debug/app-debug.apk
```

### 4. Install on Device

**Via USB:**
```bash
# Enable USB debugging on your phone first
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Via Android Studio:**
1. Open project in Android Studio
2. Connect device via USB
3. Click "Run" (green triangle)
4. Select your device

---

## First Launch

### 1. Enter API Key

On first launch, you'll see the setup screen:

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
║  [Model: Sonnet 4.5 ▼]            ║
║                                    ║
║           [Save & Continue]        ║
║                                    ║
╚════════════════════════════════════╝
```

**Steps:**
1. Paste your API key
2. Select model (Sonnet 4.5 recommended)
3. Tap "Save & Continue"

**Your API key is encrypted** and stored securely using Android Keystore.

### 2. Start Chatting

You'll see the main terminal screen:

```
╔════════════════════════════════════╗
║ ☰  New Conversation   [Sonnet 4.5]║
╠════════════════════════════════════╣
║                                    ║
║  ✓ Ready to chat with Claude       ║
║                                    ║
║  [Ask anything]                    ║
║                                    ║
║                                    ║
║                                    ║
║                                    ║
╠════════════════════════════════════╣
║  [Type your message...]   [Send] 📸║
╚════════════════════════════════════╝
```

**Try your first message:**
```
> Hello! Can you help me debug some code?
```

Response streams in real-time.

---

## Key Features

### Send Text Messages

1. Type in the input field
2. Tap "Send" or press Enter
3. Response streams in real-time
4. **Auto-saved** to database

### Send Screenshots

1. Tap the camera FAB (📸) in bottom-right
2. Grant camera permission (first time)
3. Take a photo
4. Preview and confirm
5. Claude analyzes the screenshot
6. **Auto-saved** with image reference

**Use cases:**
- "What's this error message?"
- "Review this code on my screen"
- "What UI improvements would you suggest?"

### Start New Conversation

1. Tap "New Session" button
2. Previous conversation is saved
3. Fresh conversation starts
4. Auto-titled from first message

### View Conversation History

**Current (v0.2.0):**
- Last conversation auto-loads on app start
- All messages saved in SQLite

**Coming (v0.3.0):**
- Conversation list UI
- Search across all conversations
- Export as markdown

---

## Usage Tips

### Physical Keyboard Shortcuts

If using Clicks phone or Bluetooth keyboard:

- **Enter** → Send message
- **Ctrl+N** → New conversation (coming soon)
- **Ctrl+K** → Clear terminal (coming soon)

### Performance

**Streaming speed depends on:**
- ✅ Network quality (WiFi > cellular)
- ✅ Claude API load (usually fast)
- ✅ Message length (shorter = faster first token)

**Typical response times:**
- First token: 1-2 seconds
- Complete response: 3-10 seconds

**If slow:**
- Check network connection
- Try switching to WiFi
- Use Haiku model (faster, cheaper)

### Cost Management

**Monitor usage:**
- Check Anthropic Console: https://console.anthropic.com/settings/usage
- Typical conversation: $0.50-2.00
- Screenshot analysis: +$0.10-0.30 per image

**Tips to reduce costs:**
- Use Haiku for simple questions
- Use Sonnet for coding tasks
- Use Opus only when you need the best

### Privacy & Security

**Your data:**
- ✅ API key encrypted on device
- ✅ Conversations stored locally only
- ✅ Screenshots in app-private folder
- ✅ Not synced to cloud (yet)
- ✅ Deleted when app uninstalled

**Network:**
- ✅ HTTPS only (Anthropic API)
- ✅ No third-party servers
- ✅ Direct phone → Claude connection

---

## Troubleshooting

### Build Errors

**"Unable to locate a Java Runtime"**
```bash
# Install Java 17
brew install openjdk@17
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

**"SDK location not found"**
- Open project in Android Studio
- Let it auto-configure SDK
- Or set `ANDROID_HOME` manually

**Gradle sync failed**
```bash
./gradlew clean
./gradlew build --refresh-dependencies
```

### Runtime Errors

**"Invalid API key format"**
- Check key starts with `sk-ant-api`
- Re-paste from Anthropic Console
- No spaces or newlines

**"Connection failed"**
- Check internet connection
- Try disabling VPN
- Verify Anthropic API status: https://status.anthropic.com

**"Rate limit exceeded"**
- Wait 60 seconds
- Upgrade Anthropic tier: https://console.anthropic.com/settings/plans

**"No response / stuck"**
- Check network quality
- Restart app
- Try airplane mode on/off

### Camera Issues

**"Camera permission denied"**
1. Android Settings → Apps → HIT-C
2. Permissions → Camera → Allow

**"Screenshot not saving"**
- Check storage space
- Grant storage permissions
- Restart app

---

## Advanced Configuration

### Change Model

**Per conversation:**
- Coming in v0.3.0

**Global default:**
1. Settings → API Key (coming soon)
2. Or reinstall app and choose during setup

### Database Location

**SQLite database:**
```
/data/data/com.kylsolutions.hitc/databases/hitc_conversations.db
```

**View database (rooted device):**
```bash
adb shell
su
sqlite3 /data/data/com.kylsolutions.hitc/databases/hitc_conversations.db
.tables
.schema conversations
SELECT * FROM conversations;
```

### Clear All Data

**Reset app:**
1. Android Settings → Apps → HIT-C
2. Storage → Clear Data
3. Reopen app → Enter API key again

**Or:**
```bash
adb shell pm clear com.kylsolutions.hitc
```

---

## Development Setup

### Run in Android Studio

1. Open project: `File → Open → head-in-the-claude/`
2. Wait for Gradle sync
3. Connect device or start emulator
4. Click Run (green triangle)

### Enable Debug Logs

**Logcat filter:**
```
package:com.kylsolutions.hitc
```

**Key tags:**
- `AnthropicClient` — API calls
- `SessionRepository` — Database operations
- `MainActivity` — UI events

### Testing Changes

**Quick iteration:**
1. Make code changes
2. `./gradlew assembleDebug`
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. Restart app

---

## Next Steps

**Now that you're set up:**

1. ✅ Send a few test messages
2. ✅ Try screenshot analysis
3. ✅ Create multiple conversations
4. ✅ Test persistence (restart app)
5. 📝 Give feedback: https://github.com/kyl-solutions/head-in-the-claude/issues

**Coming soon (v0.3.0):**
- Conversation list UI
- Search functionality
- Export conversations
- Voice input
- Settings screen

---

## Getting Help

**Issues:**
- GitHub Issues: https://github.com/kyl-solutions/head-in-the-claude/issues

**Documentation:**
- Full spec: `MOBILE_NATIVE_SPEC.md`
- Implementation notes: `IMPLEMENTATION_COMPLETE.md`
- Screenshot feature: `SCREENSHOT_FEATURE.md`

**Anthropic Support:**
- API docs: https://docs.anthropic.com
- Status page: https://status.anthropic.com
- Support: support@anthropic.com

---

**Happy coding from anywhere! 🌩️**

*Last Updated: 2026-02-15*
