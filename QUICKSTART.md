# HIT-C Quick Start Guide

Get up and running in 5 minutes.

---

## Step 1: Enable SSH on Your iMac

```bash
# On your iMac terminal:
sudo systemsetup -setremotelogin on

# Get your local IP address:
ifconfig | grep "inet " | grep -v 127.0.0.1
# Look for something like: inet 192.168.1.100
```

**Write down your IP address** — you'll need it in Step 3.

---

## Step 2: Open Project in Android Studio

1. Launch **Android Studio**
2. Click **Open**
3. Navigate to: `~/Developer/kyl-solutions/head-in-the-claude/`
4. Click **OK**

Wait for Gradle sync to complete (~2-3 minutes first time).

---

## Step 3: Configure Your iMac Connection

1. In Android Studio, open: `app/src/main/java/com/kylsolutions/hitc/MainActivity.kt`

2. Find this section (around line 73):
   ```kotlin
   val host = "YOUR_IMAC_IP"  // e.g., "192.168.1.100"
   val user = "kylsolutions"
   val password = "YOUR_PASSWORD"  // Or use key-based auth
   ```

3. Replace with your actual values:
   ```kotlin
   val host = "192.168.1.100"     // Your IP from Step 1
   val user = "kylsolutions"       // Your Mac username
   val password = "your_password"  // Your Mac password
   ```

4. **Save the file** (Cmd+S)

---

## Step 4: Connect Your Android Device

### Option A: Physical Device (Recommended)
1. Enable **Developer Options** on your phone:
   - Settings → About Phone → Tap "Build Number" 7 times
2. Enable **USB Debugging**:
   - Settings → Developer Options → USB Debugging → ON
3. Connect phone to Mac via USB
4. Allow USB debugging when prompted on phone

### Option B: Emulator
1. Tools → AVD Manager → Create Virtual Device
2. Select any phone (Pixel 6 recommended)
3. Download system image if needed
4. Click Finish

---

## Step 5: Run the App

1. Click the green **Play** button (▶) in Android Studio toolbar
2. Select your device from the dropdown
3. Wait for app to build and install (~30 seconds)

---

## Step 6: Connect to iMac

1. App launches on your phone
2. Tap **"Connect to iMac"**
3. You should see:
   ```
   Connecting to SSH...
   ✓ Connected to iMac
   ```

4. Start typing commands in the input field at the bottom
5. Tap **Send** or press Enter

---

## Testing Commands

Try these to verify everything works:

```bash
pwd                  # Should show your current directory
ls -la              # List files
whoami              # Should show your username
echo "HIT-C works!" # Print a message
```

---

## Troubleshooting

### "Connection refused"
- Verify SSH is enabled: `sudo systemsetup -getremotelogin` (should say "On")
- Check IP address is correct
- Make sure both devices are on same Wi-Fi network

### "Permission denied"
- Double-check username and password in MainActivity.kt
- Try SSH from another device first: `ssh username@ip_address`

### "App won't install"
- Make sure USB debugging is enabled
- Try: Build → Clean Project, then Run again

### "Gradle sync failed"
- Check internet connection (needs to download dependencies)
- File → Invalidate Caches → Invalidate and Restart

---

## Next Steps

Once connected:
- Try running `claude` or your preferred workflow
- Take screenshots (future feature)
- Use physical keyboard if you have one (Clicks phone)

---

**Ready?** Go to Step 1!
