#!/usr/bin/env bash
# Builds lcd-clock.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs the Android SDK (platform 35 and build-tools), a JDK 17 or newer, and zip.
# Usage: ./build.sh [install]   (install: then ./install.sh puts it on the phone)
set -euo pipefail
cd "$(dirname "$0")"

die() { echo "build.sh: $*" >&2; exit 1; }

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
if [ ! -d "$SDK" ] && [ -d "$HOME/Library/Android/sdk" ]; then
    SDK=$HOME/Library/Android/sdk # Android Studio's place on macOS
fi
[ -d "$SDK" ] || die "no Android SDK at $SDK: install one and set ANDROID_HOME"
# build-tools 37.0.0 if it's there, else the newest installed.
BT=$SDK/build-tools/37.0.0
if [ ! -d "$BT" ]; then
    BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)
    BT=${BT%/}
fi
[ -n "$BT" ] && [ -x "$BT/aapt2" ] || die "no build-tools in $SDK (sdkmanager \"build-tools;37.0.0\")"
JAR=$SDK/platforms/android-35/android.jar
[ -f "$JAR" ] || die "no Android 15 platform in $SDK (sdkmanager \"platforms;android-35\")"
OUT=build
# Fedora's java-*-headless has no javac; fall back to the portable JDK next to the SDK.
if ! command -v javac >/dev/null; then
    export PATH="$HOME/Android/jdk/bin:$PATH"
fi
command -v javac >/dev/null || die "no javac: install a JDK 17 or newer"
command -v zip >/dev/null || die "no zip: install it"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes"

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -A assets --java "$OUT/gen" -o "$OUT/unsigned.apk" "$OUT/res.zip"

javac --release 17 -Xlint:-options -cp "$JAR" -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')
"$BT/d8" --release --min-api 33 --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')
(cd "$OUT" && zip -q unsigned.apk classes.dex)

"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f debug.keystore ]; then
    keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias lcdclock \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=LCD Clock" >/dev/null
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out lcd-clock.apk "$OUT/aligned.apk"
echo "Built $(pwd)/lcd-clock.apk"

if [ "${1:-}" = install ]; then
    exec ./install.sh
fi
