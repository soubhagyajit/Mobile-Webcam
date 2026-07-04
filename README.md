#  Android Webcam System

Turn your Android phone into a high-quality webcam using a fully local, serverless architecture.

Open-source (GPL-3.0) • No cloud • No signaling server

## ✨ What's Included

This is a **complete professional solution** with:

- 📱 **Kotlin Mobile App** (Android for now)
- 💻 **Electron Desktop Client** (Windows/Mac/Linux)
- 🔌 **USB Connection Support** (low latency, more stable)
- 📡 **WiFi Connection Support** (wireless freedom)
## 🧩 Components

### 💻 AWC — Desktop Client
Tauri-based app that connects to your phone and outputs virtual webcam.

### 📱 AWA — Android App
Kotlin-based mobile app that acts as the streaming server.
## 🚀 Features

### Mobile App
- HD/FHD/4K resolution support (480p, 720p, 1080p, 4K)
- Adjustable FPS (15, 30, 60 fps) *(Planned)*
- Front/back camera switching
- USB and WiFi connection modes
- Real-time connection status
- Low battery usage

### Desktop Client
- Virtual webcam device (works with Zoom, Teams, Meet, OBS, etc.) ***
- Clean, modern UI
- Connection statistics

### Connection Options
- **USB Mode**: Lower latency, more stable, no WiFi needed
- **WiFi Mode**: Wireless freedom, works anywhere

## 📋 Prerequisites

### Required
- **PC**: Windows 10+, (*Planned for* macOS and Linux)
- **Phone**: Android 7.0+

### For Development
- **Android Studio** (for Android builds)
- **VS Code** (recommended editor)
- Or your favourite software for developement


## 🎥 Using as Virtual Webcam ***

The desktop client creates a virtual webcam that works with:

- ✅ Zoom
- ✅ Microsoft Teams
- ✅ Google Meet
- ✅ Discord
- ✅ OBS Studio
- ✅ Skype
- ✅ Any app that uses webcams!

### Windows Setup
The virtual webcam should appear automatically in your video apps.

<!-- ### Mac Setup
Grant camera permissions in System Preferences → Security & Privacy

### Linux Setup
May require `v4l2loopback` kernel module:
```bash
sudo apt-get install v4l2loopback-dkms
``` -->

## 🔧 Configuration

### Change Resolution
Edit in mobile app settings or in code:
- 480p (640x480) - Low bandwidth
- 720p (1280x720) - **Recommended**
- 1080p (1920x1080) - High quality
- 4K (3840x2160) - Maximum quality


## 🏗️ Building for Production

### Desktop Apps
```bash
cd desktop-client

# Windows
npm run build:win

```

## 🐛 Troubleshooting

### Connection Issues

**"Can't connect -"**
- Check firewall (allow port 8080)
- Verify same WiFi network (for WiFi mode)
- Check USB debugging (for USB mode)


### Video Quality Issues

- Lower resolution to 720p
- Use USB instead of WiFi
- Close other apps using camera
- Use 5GHz WiFi if available

## 🌟 Advantages Over Other APPs

| Feature | AWA | Other APPs  |
|---------|--------------|----------|
| Price | **Free** | Pay for HD and high resolutions |
| Resolution | Up to 4K | 720p (free), 1080p (paid) |
| Open Source (Customize as you want) | ✅ Yes | ❌ No |
| USB Support | ✅ Yes | ✅ Yes |
| WiFi Support | ✅ Yes | ✅ Yes |
| Customizable | ✅ Full control | ❌ No |
| Privacy | ✅ Self-hosted | ⚠️ |
| No Ads | ✅ Yes | ❌ Has ads |

## 🤝 Contributing

This is an open-source project! Contributions welcome:

- 🐛 Report bugs
- 💡 Suggest features
- 🔧 Submit pull requests
- 📖 Improve documentation
- ⭐ Star the repository

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**.

© 2026 Soubhagyajit Borah

### What this means:

* ✅ You can use, modify, and distribute this software
* ✅ You can use it commercially
* ⚠️ You must disclose source code if you distribute it
* ⚠️ Any derivative work must also be licensed under GPL-3.0

See the [LICENSE](LICENSE) file for full details.


## 🙏 Acknowledgments

- Virtual camera powered by [Softcam](https://github.com/tshino/softcam) © tshino (MIT License)
- Built with Kotlin, Electron.
- Inspired by DroidCam and similar tools.
- Made with ❤️ for the open-source community.

## 🚀 Roadmap

- [ ] Audio streaming support
- [ ] Multiple phone support at a time
- [ ] Recording functionality
- [ ] Native virtual camera driver
- [ ] Cloud signaling option
- [ ] Mobile app on app stores

## 💬 Support

Having issues? Found a bug?

1. Check the documentation files
2. Search existing issues on GitHub
3. Create a new issue with details
4. Join our community discussions

## ⭐ Show Your Support

If you find this useful:
- ⭐ Star the repository
- 📢 Share with others
- 💬 Provide feedback
- 🤝 Contribute improvements

---

#### *** *Virtual webcam device is not included in client version v1.0.5 as I am investing an issue. I will soon release next version with Virtual camera included. If you need the Virtual camera, please install client version v1.0.4*

**Happy streaming! Made with ❤️ and ☕ by developers, for developers.**
