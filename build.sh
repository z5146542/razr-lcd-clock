#!/usr/bin/env bash
# Builds lcd-clock.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: ./build.sh [install]
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$SDK/build-tools/37.0.0
JAR=$SDK/platforms/android-35/android.jar
OUT=build
# Fedora's java-*-headless has no javac; fall back to the portable JDK next to the SDK.
if ! command -v javac >/dev/null; then
    export PATH="$HOME/Android/jdk/bin:$PATH"
fi

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
    adb install -r lcd-clock.apk
fi
