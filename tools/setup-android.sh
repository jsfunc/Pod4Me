#!/bin/sh
# Run in a normal, network-enabled terminal. Shares the SDK across projects.
set -eu
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
if [ -z "${JAVA_HOME:-}" ] && [ -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
fi
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if ! command -v javac >/dev/null 2>&1; then
    printf 'Install a full JDK first: sudo apt-get install openjdk-17-jdk curl unzip\n' >&2
    exit 1
fi
mkdir -p "$ANDROID_HOME/cmdline-tools" .tools
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    archive="$PWD/.tools/android-commandline-tools.zip"
    curl --fail --location --retry 3 \
        https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
        --output "$archive"
    unpack=$(mktemp -d "$PWD/.tools/sdk-unpack.XXXXXX")
    unzip -q "$archive" -d "$unpack"
    mv "$unpack/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rmdir "$unpack"
    rm "$archive"
fi
printf '\nReview and accept the Android SDK licenses when prompted.\n'
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
    'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
printf '\nSDK installed. Build with: ./tools/build-apk.sh\n'
