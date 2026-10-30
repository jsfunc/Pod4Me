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
# Preserve the certificate used by earlier personal installations. This is a
# dedicated persistent copy of that key, not a new certificate requiring uninstall.
if [ -z "${POD4ME_KEYSTORE:-}" ]; then
    export POD4ME_KEYSTORE="$HOME/.android/pod4me-v1.keystore"
    if [ ! -f "$POD4ME_KEYSTORE" ]; then
        if [ ! -f "$HOME/.android/debug.keystore" ]; then
            printf 'No existing signing key. Set POD4ME_KEYSTORE, POD4ME_STORE_PASSWORD, POD4ME_KEY_ALIAS and POD4ME_KEY_PASSWORD to your personal keystore.\n' >&2
            exit 1
        fi
        install -m 600 "$HOME/.android/debug.keystore" "$POD4ME_KEYSTORE"
    fi
fi
java tools/CoreTestLauncher.java
python3 -I tools/check_schema.py
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :app:assembleDebugAndroidTest --no-daemon
apk=app/build/outputs/apk/release/app-release.apk
signer="$ANDROID_HOME/build-tools/35.0.0/apksigner"
aapt="$ANDROID_HOME/build-tools/35.0.0/aapt"
"$signer" verify "$apk"
badging=$("$aapt" dump badging "$apk")
case "$badging" in *application-debuggable*) printf 'Refusing to deliver a debuggable release.\n' >&2; exit 1;; esac
case "$badging" in *"versionCode='13' versionName='1.0.5'"*) ;; *) printf 'Unexpected APK version.\n' >&2; exit 1;; esac
if [ -f dist/Pod4Me.apk ]; then
    old_cert=$("$signer" verify --print-certs dist/Pod4Me.apk | sed -n '/Signer #1 certificate SHA-256 digest:/p')
    new_cert=$("$signer" verify --print-certs "$apk" | sed -n '/Signer #1 certificate SHA-256 digest:/p')
    if [ "$old_cert" != "$new_cert" ]; then
        printf 'Signing certificate differs from dist/Pod4Me.apk. Existing APK kept. Check the signing key before installing; do not uninstall your current app.\n' >&2
        exit 1
    fi
fi
mkdir -p dist
cp "$apk" dist/Pod4Me.apk
cp "$apk" dist/Pod4Me-1.0.5.apk
(cd dist && sha256sum Pod4Me.apk > Pod4Me.apk.sha256 && sha256sum Pod4Me-1.0.5.apk > Pod4Me-1.0.5.apk.sha256)
printf 'Verified non-debuggable v1 APK: %s/dist/Pod4Me.apk\n' "$PWD"
