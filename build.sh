#!/bin/sh
# Builds the JNI library and the APK, and installs it on the connected phone.
#
#   ./build.sh            build, install and launch
#   ./build.sh apk        build only (app/build/outputs/apk/debug/app-debug.apk)
#   ./build.sh key DIR    load a key dir (keystore.json, password, capability) into the app
set -eu
cd "$(dirname "$0")"

if [ -z "${ANDROID_HOME:-}" ]; then
  for sdk in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -d "$sdk" ] && ANDROID_HOME="$sdk" && break
  done
fi
: "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"
export ANDROID_HOME
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
: "${ANDROID_NDK_HOME:?install an NDK (Android Studio: SDK Manager > SDK Tools > NDK)}"
export ANDROID_NDK_HOME
studio_jbr="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$studio_jbr" ]; then
  export JAVA_HOME="$studio_jbr"
fi

ADB="$ANDROID_HOME/platform-tools/adb"
PKG=dev.decdn.demo

if [ "${1:-}" = key ]; then
  dir="${2:?usage: ./build.sh key DIR}"
  # Straight into the app's private files dir (debug builds allow run-as).
  "$ADB" shell run-as "$PKG" mkdir -p files/key
  for f in keystore.json password capability; do
    "$ADB" shell run-as "$PKG" sh -c "'cat > files/key/$f && chmod 600 files/key/$f'" <"$dir/$f"
  done
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell am start -n "$PKG/.MainActivity"
  exit 0
fi

(
  cd rust
  cargo ndk -t arm64-v8a -P 30 -o ../app/src/main/jniLibs build --release
)
# Crates that also build as cdylibs (iroh, iroh-relay, the TLS verifier) land
# here too; libdecdn_android.so links them statically.
find app/src/main/jniLibs/arm64-v8a -name '*.so' ! -name libdecdn_android.so -delete

./gradlew --quiet assembleDebug
[ "${1:-}" = apk ] && exit 0
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n "$PKG/.MainActivity"
