# Hyperion Grabber for Android TV

An Android TV app that captures the screen and streams it to a [Hyperion.ng](https://github.com/hyperion-project/hyperion.ng) server over the flatbuffers protocol, enabling ambilight-style LED effects driven by whatever is playing on your TV.

## Requirements

- Android TV device (Android 8.0+ / API 26+)
- [Hyperion.ng](https://github.com/hyperion-project/hyperion.ng) **2.2.1** running on your network
- ADB enabled on the TV for sideloading

## Features

- Screen capture via MediaProjection — no root required
- Sends frames over flatbuffers (port 19400) at configurable FPS and resolution
- Auto-detects LED count and optimal resolution from Hyperion server
- Black bar detection — crops letterbox/pillarbox bars before sampling
- Brightness control — adjusts Hyperion master brightness from the app
- Schedule — auto start/stop at fixed times or based on sunset
- Auto-reconnect if Hyperion drops the connection
- Keepalive frames to maintain the connection on static screens
- Gradient border glow on screen edges while streaming

## Installation

1. Enable ADB on your Android TV (`Settings → Device Preferences → Developer options`)
2. Download the latest APK from [Releases](../../releases)
3. Install via ADB:
   ```bash
   adb connect <tv-ip>:5555
   adb install hyperion-grabber.apk
   ```
4. Open **Hyperion Grabber** on your TV
5. Enter your Hyperion server IP and press **Start**

## Configuration

| Setting | Description |
|---|---|
| Host | IP address of your Hyperion server |
| Protocol | Flatbuffer (19400), Proto (19445), or JSON (19444) |
| FPS | Capture frame rate (default 25) |
| Resolution | Downscale target — auto-set from Hyperion LED layout |
| Brightness | Hyperion master brightness 0–100 |
| Schedule | Auto start/stop by time or sunset |

## Building from source

### Prerequisites
- Android SDK with NDK 27
- CMake 3.22+

### Android APK
```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### C++ unit tests
```bash
cmake -B build-tests -S .
cmake --build build-tests -j$(nproc)
cd build-tests && ctest --output-on-failure
```

## Compatibility

Tested on **Sony Bravia Android TV** with **Hyperion.ng 2.2.1**.

The flatbuffers schema is pinned to Hyperion.ng 2.2.1. If you are running a different version and frames are not appearing, check that the `Command` union order matches (`Color=1, Image=2, Clear=3, Register=4`) and that priority 150 is accepted.

Screen capture requires user permission via the MediaProjection dialog on first start. On Android 14, this permission cannot be granted from a background service — the app must be in the foreground when you press Start.
