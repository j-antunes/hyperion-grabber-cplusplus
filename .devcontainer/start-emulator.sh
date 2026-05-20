#!/bin/bash
# Runs every time the devcontainer starts.
# Launches Xvfb + the Android TV emulator, then tries to connect to the Bravia TV.

HYPERION_TV_HOST="192.168.14.92"
HYPERION_TV_PORT="5555"
EMULATOR_DISPLAY=":99"
EMULATOR_LOG="/tmp/emulator.log"

# ── Virtual display (Xvfb) ───────────────────────────────────────────────────
if pgrep -x Xvfb > /dev/null; then
    echo "[Display] Xvfb already running"
else
    echo "[Display] Starting Xvfb on $EMULATOR_DISPLAY..."
    Xvfb "$EMULATOR_DISPLAY" -screen 0 1280x800x24 &
    sleep 1
fi
export DISPLAY="$EMULATOR_DISPLAY"

# ── Android emulator ─────────────────────────────────────────────────────────
if pgrep -f "avd AndroidTV34" > /dev/null; then
    echo "[Emulator] Already running"
else
    echo "[Emulator] Starting AndroidTV34 (headless)..."
    # -accel auto: uses KVM on Linux, falls back to HAXM/Hypervisor on Mac/Windows,
    #              or pure software if neither available.
    # -no-window:  headless — view via 'adb exec-out screencap -p > shot.png'
    "$ANDROID_HOME/emulator/emulator" \
        -avd AndroidTV34 \
        -no-audio \
        -no-boot-anim \
        -no-window \
        -gpu swiftshader_indirect \
        -accel auto \
        > "$EMULATOR_LOG" 2>&1 &
    echo "[Emulator] PID $! — logs at $EMULATOR_LOG"
    echo "[Emulator] Waiting for boot..."
    adb wait-for-device shell \
        "while [ \"\$(getprop sys.boot_completed)\" != \"1\" ]; do sleep 1; done"
    echo "[Emulator] Booted — emulator-5554 ready"
fi

# ── Physical Bravia TV (optional, only if on same network) ───────────────────
adb start-server > /dev/null 2>&1
if nc -z -w 2 "$HYPERION_TV_HOST" "$HYPERION_TV_PORT" 2>/dev/null; then
    echo "[ADB] Bravia TV reachable — connecting..."
    adb connect "$HYPERION_TV_HOST:$HYPERION_TV_PORT"
else
    echo "[ADB] Bravia TV not reachable (not on home network — skipping)"
fi

echo ""
echo "Devices:"
adb devices
