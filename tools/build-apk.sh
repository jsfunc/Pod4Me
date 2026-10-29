#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
if [ -z "${JAVA_HOME:-}" ] && [ -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
fi
if [ ! -f "$ANDROID_HOME/platforms/android-36/android.jar" ]; then
    printf 'Android SDK 36 is missing. Run ./tools/setup-android.sh first.\n' >&2
    exit 1
fi
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/Pod4Me.apk
(cd dist && sha256sum Pod4Me.apk > Pod4Me.apk.sha256)
printf 'APK: %s/dist/Pod4Me.apk\n' "$PWD"
