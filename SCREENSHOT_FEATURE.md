# Screenshot Capture Feature

## Overview

HIT-C now includes native Android camera capture with preview and save functionality. This feature is designed to make it easy to capture screenshots of your work and include them in Claude conversations.

---

## How It Works

### User Flow

1. **Tap the camera FAB** (Floating Action Button) in the bottom-right corner
2. **Grant camera permission** (first time only)
3. **Take a photo** using the native Android camera
4. **Preview dialog** shows the captured image
5. **Choose action:**
   - **Send** → Saves locally and shows path in terminal
   - **Retake** → Opens camera again
   - **Cancel** → Discards the screenshot

### What Happens When You Capture

Currently (v0.1.0):
- ✅ Photo is captured and saved to app's external files directory
- ✅ File path and size shown in terminal output
- ✅ Toast notification confirms save
- ✅ File is accessible for manual upload

Future (v0.2.0+):
- 🚧 Auto-upload to Claude conversation via API
- 🚧 Screenshot thumbnail in terminal
- 🚧 Image annotation before sending
- 🚧 Multiple screenshots in one conversation

---

## Technical Implementation

### New Files

| File | Purpose |
|------|---------|
| `ScreenshotHelper.kt` | Handles camera permissions, capture, and preview |
| `file_paths.xml` | FileProvider configuration for camera image URIs |

### Updated Files

| File | Changes |
|------|---------|
| `MainActivity.kt` | Integrated ScreenshotHelper, added FAB listener |
| `AndroidManifest.xml` | Added CAMERA permission, FileProvider |
| `activity_main.xml` | Added camera FAB |
| `strings.xml` | Added screenshot-related strings |

---

## Permissions

### Required

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

### Optional Features

```xml
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
```

Setting `required="false"` means the app can still be installed on devices without a camera (e.g., tablets), but the screenshot feature won't work.

---

## File Storage

Screenshots are saved to:
```
/storage/emulated/0/Android/data/com.kylsolutions.hitc/files/
```

File naming format:
```
HITC_yyyyMMdd_HHmmss.jpg
```

Example:
```
HITC_20260215_143022.jpg
```

---

## Security & Privacy

### Current Implementation
- ✅ Screenshots stored in app-private directory
- ✅ Files excluded from cloud backup (see `backup_rules.xml`)
- ✅ FileProvider prevents direct file access from other apps
- ✅ Runtime permission required (user must explicitly allow)

### Future Enhancements
- 🚧 Automatic file cleanup after upload
- 🚧 Encrypted local storage option
- 🚧 Delete screenshots after X days

---

## Usage Examples

### During a Claude Code Session

1. Working on a bug fix
2. Encounter an error message on screen
3. Tap camera FAB → capture error
4. Screenshot path shows in terminal:
   ```
   📸 Screenshot captured: HITC_20260215_143022.jpg
      Saved to: /storage/.../HITC_20260215_143022.jpg
      Size: 248 KB

   💡 Future: Will auto-upload to Claude conversation
   ```
5. Manually upload to Claude or wait for future API integration

---

## Troubleshooting

### Camera Permission Denied
**Problem:** Tapped FAB but nothing happens

**Solution:**
1. Go to Android Settings
2. Apps → Head-In-The-Cloud
3. Permissions → Camera → Allow

### FileProvider Error
**Problem:** "Failed to find configured root" error

**Solution:**
- Check `file_paths.xml` exists in `res/xml/`
- Verify FileProvider in `AndroidManifest.xml`
- Clean and rebuild project

### Image Not Saving
**Problem:** Camera opens but image doesn't save

**Solution:**
- Check external storage permissions
- Verify app has access to external files directory
- Check device storage space

---

## API Integration (Future)

### Planned Implementation

When Claude Code API integration is added:

```kotlin
private fun handleScreenshot(file: File) {
    // Show in terminal
    appendOutput("\n📸 Screenshot captured\n")

    // Upload to Claude conversation
    lifecycleScope.launch {
        val result = claudeApiClient.uploadImage(file, conversationId)
        if (result.success) {
            appendOutput("✓ Uploaded to conversation\n")
            file.delete() // Cleanup after successful upload
        } else {
            appendOutput("✗ Upload failed: ${result.error}\n")
        }
    }
}
```

---

## Next Steps

To complete the screenshot feature:

1. **Test on physical device** (emulator camera is limited)
2. **Add image preview** in the confirmation dialog
3. **Implement Claude API client** for auto-upload
4. **Add screenshot gallery** to view recent captures
5. **Support screen recording** (video capture)

---

*Last Updated: 2026-02-15 (v0.1.0)*
