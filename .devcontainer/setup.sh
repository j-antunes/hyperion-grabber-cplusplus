#!/bin/bash
# Runs once when the devcontainer is first created.
# Creates the Android TV AVD and builds the project.
set -e

echo "=== Hyperion Grabber — Container Setup ==="

# ── Create AVD ────────────────────────────────────────────────────────────────
if avdmanager list avd 2>/dev/null | grep -q "AndroidTV34"; then
    echo "[AVD] AndroidTV34 already exists — skipping"
else
    echo "[AVD] Creating AndroidTV34..."
    echo "no" | avdmanager create avd \
        --name    "AndroidTV34" \
        --package "system-images;android-34;android-tv;x86" \
        --device  "tv_1080p" \
        --force
    echo "[AVD] Created"
fi

# ── C++ host build (generates flatbuffers headers, builds test binary) ───────
echo "[CMake] Configuring..."
cmake -B build -DCMAKE_BUILD_TYPE=Debug -S /workspace
echo "[CMake] Building..."
cmake --build build -j"$(nproc)"

# ── Android debug APK ─────────────────────────────────────────────────────────
echo "[Gradle] Building debug APK..."
cd /workspace/android && ./gradlew assembleDebug --no-daemon
echo "[Gradle] Done"

echo "=== Setup complete ==="
echo "Run '.devcontainer/start-emulator.sh' to start the emulator."
