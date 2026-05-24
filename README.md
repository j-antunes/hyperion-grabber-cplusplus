# Hyperion Grabber

A **companion app for [Hyperion.ng](https://github.com/hyperion-project/hyperion.ng)** that captures your screen and streams it to a Hyperion server over the flatbuffers protocol, driving ambilight-style LED effects in near real-time.

Written in **C++** with a focus on **performance** and **cross-platform** support — one shared core powers builds for:

- **Android TV** — sideload the `.apk` from [Releases](../../releases)
- **Windows** — install the `.msi` from [Releases](../../releases)
- **Linux** — install the `.deb` from [Releases](../../releases) (or build from source)

All three platforms share the same `core/` library (TCP client, frame downscaler, black-bar detection) and the same flatbuffers wire protocol, so behavior stays consistent across devices.

---

## Windows / Linux desktop app

### Requirements

- [Hyperion.ng](https://github.com/hyperion-project/hyperion.ng) **2.2.1** running on your network
- Windows 10/11 (64-bit) or Ubuntu/Debian Linux

### Install

**Windows**
1. Download `HyperionGrabber-<version>.msi` from [Releases](../../releases)
2. Run the installer — no admin rights needed for user-level install
3. Launch **Hyperion Grabber** from the Start menu

**Linux**
1. Download `hyperion-grabber_<version>_amd64.deb` from [Releases](../../releases)
2. Install:
   ```bash
   sudo dpkg -i hyperion-grabber_*.deb
   ```
3. Launch **Hyperion Grabber** from your applications menu or run `hyperion-grabber`

### First-time setup

1. Enter your **Hyperion server IP** in the Host field (e.g. `192.168.1.100`)
   - You can also paste a full URL like `http://192.168.1.100` — the scheme is stripped automatically
   - The app checks reachability automatically 1 second after you stop typing (green dot = OK)
2. Leave **Port** at `19400` (flatbuffers default) unless you changed it in Hyperion
3. Set **Priority** to `150` (default) — lower numbers take priority over higher ones in Hyperion
4. Click **Test** to verify the connection if the dot doesn't appear
5. Click **Start** — the app begins capturing and streaming your screen

### Settings

| Setting | Description |
|---|---|
| Host | IP or hostname of your Hyperion server. Accepts bare IP, `http://`, or `https://` |
| Port | Hyperion flatbuffers port (default `19400`) |
| FPS | Capture frame rate (default `25`) |
| Priority | Hyperion source priority 1–255 (default `150`) |
| Brightness | Adjusts Hyperion master brightness 0–100% |
| Minimize to tray | Closing the window hides it to the system tray instead of exiting |
| Start on boot | Adds the app to Windows startup / Linux autostart |

### System tray

When **Minimize to tray** is enabled (default), closing the window keeps the grabber running in the background. Right-click the tray icon to **Open** the window or **Exit** the app.

### Troubleshooting

| Problem | Fix |
|---|---|
| Red dot / can't connect | Confirm Hyperion is running and the host IP is correct. Check that port 8090 is reachable (used for the connection test). |
| LEDs don't light up | Confirm flatbuffers port 19400 is open in Hyperion settings. |
| Wrong colours | Hyperion's LED layout may need calibration — this app sends the full screen capture. |
| Black screen on Start | Some GPUs require running the app as administrator for screen capture access. |

---

## Android TV app

### Requirements

- Android TV device (Android 8.0+ / API 26+)
- [Hyperion.ng](https://github.com/hyperion-project/hyperion.ng) **2.2.1** running on your network
- ADB enabled on the TV for sideloading

### Features

- Screen capture via MediaProjection — no root required
- Sends frames over flatbuffers (port 19400) at configurable FPS and resolution
- Auto-detects LED count and optimal resolution from Hyperion server
- Black bar detection — crops letterbox/pillarbox bars before sampling
- Brightness control — adjusts Hyperion master brightness from the app
- Schedule — auto start/stop at fixed times or based on sunset
- Auto-reconnect if Hyperion drops the connection
- Keepalive frames to maintain the connection on static screens
- Gradient border glow on screen edges while streaming

### Install

1. Enable ADB on your Android TV (`Settings → Device Preferences → Developer options`)
2. Download the latest `app-debug.apk` from [Releases](../../releases)
3. Install via ADB:
   ```bash
   adb connect <tv-ip>:5555
   adb install app-debug.apk
   ```
4. Open **Hyperion Grabber** on your TV
5. Enter your Hyperion server IP and press **Start**

### Configuration

| Setting | Description |
|---|---|
| Host | IP address of your Hyperion server |
| Port | Flatbuffers port (default 19400) |
| FPS | Capture frame rate (default 25) |
| Resolution | Downscale target — auto-detected from Hyperion LED layout |
| Brightness | Hyperion master brightness 0–100 |
| Schedule | Auto start/stop by time or sunset |

---

## Building from source

### Prerequisites
- Android SDK with NDK 27 (for Android)
- CMake 3.22+ and Java 17+ (for desktop)

### Android APK
```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Desktop app (Windows / Linux)
```bash
cd desktop
gradle packageMsi      # Windows .msi installer
gradle packageDeb      # Linux .deb package
gradle run             # Run directly without packaging
```

### C++ unit tests
```bash
cmake -B build -S .
cmake --build build -j$(nproc)
cd build && ctest --output-on-failure
```

---

## Compatibility

Tested on **Sony Bravia Android TV** and **Windows 11** with **Hyperion.ng 2.2.1**.

The flatbuffers schema is pinned to Hyperion.ng 2.2.1. If frames are not appearing, verify that the `Command` union order matches (`Color=1, Image=2, Clear=3, Register=4`) and that priority 150 is accepted by your Hyperion instance.
