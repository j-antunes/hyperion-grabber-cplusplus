FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive

# ── System packages ───────────────────────────────────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
    # Build tools
    build-essential \
    cmake \
    ninja-build \
    git \
    pkg-config \
    # X11 / OpenGL (Linux grabber + emulator software rendering)
    libx11-dev \
    libx11-6 \
    libgl1-mesa-glx \
    libglu1-mesa \
    # Emulator: virtual display (headless, cross-platform)
    xvfb \
    # Emulator: 32-bit runtime libs
    libc6-i386 \
    lib32z1 \
    lib32stdc++6 \
    # Emulator: audio (even -no-audio needs pulse headers at runtime)
    libpulse0 \
    # Emulator: misc
    libdbus-1-3 \
    libnss3 \
    libnspr4 \
    libxcomposite1 \
    libxdamage1 \
    libxrandr2 \
    # Android SDK/NDK prerequisites
    openjdk-17-jdk \
    wget \
    unzip \
    # Protocol helpers
    flatbuffers-compiler \
    netcat-openbsd \
    iputils-ping \
    && rm -rf /var/lib/apt/lists/*

# ── Android command-line tools ────────────────────────────────────────────────
ENV ANDROID_HOME=/opt/android-sdk
RUN mkdir -p $ANDROID_HOME/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
         -O /tmp/cmdtools.zip && \
    unzip -q /tmp/cmdtools.zip -d $ANDROID_HOME/cmdline-tools && \
    mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest && \
    rm /tmp/cmdtools.zip

ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator

# ── Android SDK components ────────────────────────────────────────────────────
# Emulator + system image baked into the image so container creation is fast
# and reliable (no large downloads on first open).
RUN yes | sdkmanager --licenses && \
    sdkmanager \
      "platform-tools" \
      "platforms;android-35" \
      "ndk;27.0.12077973" \
      "build-tools;35.0.0" \
      "cmake;3.22.1" \
      "emulator" \
      "system-images;android-34;android-tv;x86"

ENV ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.0.12077973

# Give ubuntu user full SDK write access (Gradle manages SDK components at build time)
RUN chown -R ubuntu:ubuntu $ANDROID_HOME

USER ubuntu
WORKDIR /workspace
