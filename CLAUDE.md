# hyperion-grabber-cplusplus

Android TV screen grabber that sends frames to a [Hyperion.ng](https://github.com/hyperion-project/hyperion.ng) server over the flatbuffers protocol (TCP port 19400).

## Project layout

```
CMakeLists.txt              Root CMake — builds core, PC targets, tests, Android JNI
core/
  include/
    hyperion_client.h       TCP flatbuffers client (connects, registers, sends frames)
    frame_processor.h       RGBA→RGB downscale, rowStride handling
    grabber_base.h          Abstract base for platform grabbers
    generated/              flatc-generated C++ headers (committed, used by cross-compile)
  src/
    hyperion_client.cpp
    frame_processor.cpp
    grabber_base.cpp
flatbuffers/
  hyperion_request.fbs      Matches Hyperion.ng 2.2.1 exactly — order of union values matters
  hyperion_reply.fbs
pc/
  linux/x11_grabber.cpp     X11 screen grabber
  windows/dxgi_grabber.cpp  DXGI screen grabber
  main.cpp
android/
  CMakeLists.txt
  app/
    build.gradle            minSdk 26, compileSdk 35, NDK 27, abiFilters arm64+armv7+x86_64+x86
    src/main/
      cpp/jni_bridge.cpp    JNI entry points called from Kotlin
      java/com/hyperion/grabber/
        MainActivity.kt         Single-activity host
        MainFragment.kt         UI: two-column layout (settings | status + buttons)
        GrabberViewModel.kt     LiveData state, schedule, testLeds()
        ScreenGrabberService.kt Foreground service (FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        HyperionNative.kt       `external fun` declarations for JNI
        HyperionJsonClient.kt   JSON API helper (computeRecommendedResolution)
        HyperionProtoClient.kt  Protobuf client (port 19445, unused by default)
        SunsetCalculator.kt     computeSunsetHour() — pure function, unit-tested
        ScheduleMode.kt         enum OFF / FIXED / SUNSET
        ScheduleManager.kt      AlarmManager wrapper
        ScheduleReceiver.kt     BroadcastReceiver for schedule + BOOT_COMPLETED
        SettingKey.kt           SharedPreferences key constants
    src/test/java/com/hyperion/grabber/
        ResolutionTest.kt
        SunsetCalculatorTest.kt
tests/
  CMakeLists.txt            Google Test via FetchContent
  test_flatbuffers.cpp      Regression: Command union values must match Hyperion.ng 2.2.1
  test_frame_processor.cpp
.devcontainer/
  Dockerfile                Ubuntu + Android SDK/NDK + Xvfb + emulator
  devcontainer.json         --privileged, DISPLAY=:99, postCreate=setup.sh
  setup.sh                  Creates AVD + initial build (runs once)
  start-emulator.sh         Starts Xvfb + headless emulator + adb connect
```

## Build

### Android APK

```bash
cd android
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### C++ unit tests (host)

```bash
cmake -B build-tests -S .
cmake --build build-tests -j$(nproc)
cd build-tests && ctest --output-on-failure
```

### C++ cross-compile note

When `CMAKE_CROSSCOMPILING=ON` (Android NDK build), `flatc` is not available. The generated headers in `core/include/generated/` are committed to the repo and used directly. To regenerate them, run a native host build first.

## Deploy to TV

```bash
adb connect <tv-ip>:5555
adb -s <tv-ip>:5555 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s <tv-ip>:5555 logcat -s HyperionGrabber
```

## Hyperion.ng protocol — critical notes

The flatbuffers schema **must exactly match** Hyperion.ng 2.2.1. The union field order defines the type discriminant values — getting this wrong causes silent failures (wrong command type sent).

```
union Command { Color=1, Image=2, Clear=3, Register=4 }
```

- **Priority**: use **150** for grabber sources. Values like 50 are reserved and rejected.
- **Register reply**: after sending `Register`, you **must** call `readReply()` before sending any `Image` frames. Skipping this deadlocks the protocol.
- **Image structure**: `Image` contains an `ImageType` union (`RawImage` or `NV12Image`), not raw bytes directly.
- **Port**: flatbuffers server is **19400**. Protobuf is 19445, JSON API (read-only) is 19444.

## Service lifecycle

`ScreenGrabberService` uses `PAUSE`/`RESUME` intents (not stop/start) to preserve the MediaProjection token across configuration changes. The token cannot be re-requested from a background service on Android 10+.

- `ACTION_PAUSE` — stops ImageReader loop, keeps projection alive
- `ACTION_RESUME` — restarts ImageReader loop, reconnects TCP if needed
- Frame processing runs on a `HandlerThread` — TCP I/O must never block the main thread

## Emulator limitations

The devcontainer emulator uses `android-tv;x86` API 34. It **cannot** do screen capture (Android 14 restricts `FOREGROUND_SERVICE_MEDIA_PROJECTION` to real devices). Use the emulator only for testing the TCP/flatbuffers protocol via the **Test LEDs** button.

## Test LEDs

`HyperionNative.testConnection(host, port)` sends solid R/G/B/black frames (8 frames each at 25 fps) without needing a MediaProjection token. Returns `null` on success or an error string. Verify in Hyperion's JSON API: `GET http://<hyperion-host>:8090/json-rpc?command=priorities` should show priority 150.
