# hyperion-grabber-c

Cross-platform Hyperion screen grabber written in C++.
Targets Android TV (via NDK + JNI) and PC (Linux X11, Windows DXGI).

## Architecture

```
core/                   Shared C++ library (no platform deps)
  include/
    hyperion_client.h   Hyperion flatbuffers TCP client
    frame_processor.h   Frame downscaling (RGBA/RGB → target resolution)
    grabber_base.h      Capture loop base class
  src/

android/                Android / Android TV app
  app/src/main/
    java/…/
      HyperionNative.kt     JNI declarations
      ScreenGrabberService.kt  MediaProjection capture service
    cpp/
      jni_bridge.cpp    Connects Kotlin MediaProjection frames → core

pc/
  linux/x11_grabber     XGetImage-based capture (X11)
  windows/dxgi_grabber  Desktop Duplication API capture (Windows)
  main.cpp              CLI entry point

.devcontainer/          Dev container with build tools + Android NDK
```

## Building

### PC (Linux)
```bash
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j$(nproc)
./build/hyperion_grabber_linux 192.168.1.100 19400
```

### PC (Windows)
```powershell
cmake -B build
cmake --build build --config Release
.\build\Release\hyperion_grabber_windows.exe 192.168.1.100 19400
```

### Android (via devcontainer or Android Studio)
Open `android/` in Android Studio. The `CMakeLists.txt` at root is wired
via `externalNativeBuild` in `build.gradle`.

## Hyperion server default port
Flatbuffers listener: **19400**

## Limitations
- DRM-protected content (Netflix, etc.) cannot be captured on Android — Android OS blocks it.
- X11 grabber uses `XGetImage` (CPU path). For high FPS, a DRM/KMS or PipeWire backend is faster.
