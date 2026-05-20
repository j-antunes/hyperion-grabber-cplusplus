#!/bin/bash
# Runs every time the devcontainer starts.
# Launches Xvfb + the Android TV emulator, then optionally connects to a physical TV.

HYPERION_TV_HOST="${HYPERION_TV_HOST:-}"
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
    echo "[Emulator] Waiting for boot (up to 3 min)..."
    BOOT_TIMEOUT=180
    ELAPSED=0
    until adb shell getprop sys.boot_completed 2>/dev/null | grep -q "^1$"; do
        if [ "$ELAPSED" -ge "$BOOT_TIMEOUT" ]; then
            echo "[Emulator] Boot timeout — still starting in background, check 'adb devices'"
            break
        fi
        sleep 3
        ELAPSED=$((ELAPSED + 3))
    done
    adb shell getprop sys.boot_completed 2>/dev/null | grep -q "^1$" && echo "[Emulator] Booted — emulator-5554 ready"
fi

# ── Physical TV (optional, set HYPERION_TV_HOST env var to connect) ──────────
adb start-server > /dev/null 2>&1
if [ -n "$HYPERION_TV_HOST" ] && nc -z -w 2 "$HYPERION_TV_HOST" "$HYPERION_TV_PORT" 2>/dev/null; then
    echo "[ADB] TV reachable — connecting..."
    adb connect "$HYPERION_TV_HOST:$HYPERION_TV_PORT"
elif [ -n "$HYPERION_TV_HOST" ]; then
    echo "[ADB] TV not reachable (skipping)"
fi

echo ""
echo "Devices:"
adb devices
