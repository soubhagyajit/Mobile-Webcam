# USB Cable Connection - For Maximum Reliability

USB connection provides better stability, lower latency, and no WiFi dependency!

## How It Works

Instead of WiFi, we'll use **ADB (Android Debug Bridge)** to forward the connection through the USB cable.

## Requirements

- USB cable (the one you use to charge your phone)
- ADB (comes with Android Studio)
- USB Debugging enabled on phone

## Step-by-Step Setup

### 1. Enable USB Debugging on Phone

**Android:**
1. Go to Settings → About Phone
2. Tap "Build Number" 7 times (enables Developer Options)
3. Go back to Settings → Developer Options
4. Enable "USB Debugging"
5. Connect your phone via USB
6. Allow USB debugging when prompted on phone

### 2. Install ADB (if not already installed)

**Windows:**
- Already included with Android Studio
- Or download standalone: https://developer.android.com/studio/releases/platform-tools

**Mac:**
```bash
brew install android-platform-tools
```

**Linux:**
```bash
sudo apt-get install android-tools-adb
```

### 3. Verify ADB Connection

Connect phone via USB, then:

```bash
adb devices
```

You should see your device listed:
```
List of devices attached
ABC123XYZ    device
```

If you see "unauthorized", check your phone for permission prompt.

### 4. Setup Port Forwarding

This forwards the server port (8080) from Phone to PC via USB:

```bash
adb forward tcp:8080 tcp:8080
```

This makes `localhost:8080` on your PC connect to `localhost:8080` on your Phone!


### 6. Start Everything
**Terminal 1 - Desktop Client:**
```bash
cd desktop-client && npm start
```
**In Android Studio**
```bash 
-  Sync and build (Assuming you know basics)
```
The app will automatically install and run on your USB-connected phone!

### 7. Connect

1. Mobile App: Click Start if not started yet.
2. Desktop Client: Click "Connect"
4. Stream via USB!

## Advantages of USB Connection

✅ **More Reliable** - No WiFi dropouts

✅ **Lower Latency** - Direct connection

✅ **Better Quality** - No WiFi bandwidth limits

✅ **No Network Setup** - Works anywhere

✅ **Faster Debugging** - ADB logs available

✅ **Battery Charging** - Phone charges while streaming

## Troubleshooting USB Connection

### "adb: command not found"

**Windows:**
Add to PATH: `C:\Users\YourName\AppData\Local\Android\Sdk\platform-tools`

**Mac/Linux:**
```bash
export PATH=$PATH:~/Library/Android/sdk/platform-tools  # Mac
export PATH=$PATH:~/Android/Sdk/platform-tools          # Linux
```

### "device unauthorized"

1. Unplug and replug USB cable
2. Check phone for authorization prompt
3. Click "Always allow from this computer"
4. Run `adb devices` again

### "device offline"

```bash
adb kill-server
adb start-server
adb devices
```

### Port forwarding not working

```bash
# Remove old forwards
adb forward --remove-all

# Setup again
adb forward tcp:8080 tcp:8080

# Verify
adb forward --list
```

### Multiple devices connected

```bash
# List devices
adb devices

# Use specific device
adb -s ABC123XYZ reverse tcp:8080 tcp:8080
adb -s ABC123XYZ shell
```