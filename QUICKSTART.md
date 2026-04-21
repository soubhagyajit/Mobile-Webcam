# Quick Start - Get Running in Minutes!

For experienced developers who want to get started immediately.

## Prerequisites
- ADB installed and configured in path on your system (I may or may not be include a autoconfigure feature later).
- Android Studio OR Xcode (for mobile builds)
- Phone and PC on same WiFi network

## 1. Install All Dependencies (5 min)

```bash

# Mobile App
use Android Studio to open the Project /MobileWebcam

# Desktop Client
cd desktop-client && npm install && cd ..

# React Native CLI (if not installed)
npm install -g react-native-cli
```

## 2. Start Everything (2 min)

Open 1 terminal:

**Terminal 1 - Desktop Client:**
```bash
cd desktop-client && npm start
```
**In Android Studio**
```bash 
-  Sync and build (Assuming you know basics)
```

## 4. Connect (2 min)

1. Connect your phone via USB if want USB or note your phone's ip if you don't want USB.
2. Mobile: Grant permissions → Server starts automatically (Click pause icon if you want to stop it).
3. Desktop: Click "Connect".
4. Done! Video should be streaming.

## Common Issues & Quick Fixes

**Can't connect?**
```bash
# Firewall blocking port 8080? Allow it:
# Windows: Windows Defender Firewall → Allow an app
# Mac: System Preferences → Security → Firewall Options
# Linux: sudo ufw allow 8080
```

**Metro bundler issues?**
```bash
cd mobile-app
npx react-native start --reset-cache
```

**Android build errors?**
```bash
cd mobile-app/android && ./gradlew clean && cd ../..
```

## Tips for Best Performance (for low end phones)

- Use 720p or 480p(balance of quality/performance)
- 30 FPS for most use cases
- 5GHz WiFi >> 2.4GHz WiFi
- Close unnecessary apps on phone

## Building Release Versions

**Desktop (Windows):**
```bash
cd desktop-client && npm run build:win
```

**Desktop (Mac):**
```bash
cd desktop-client && npm run build:mac
```

**Desktop (Linux):**
```bash
cd desktop-client && npm run build:linux
```

## That's It!

You now have a working mobile webcam system. Customize, improve, share!

For detailed setup, see INSTALLATION.md
