#!/usr/bin/env bash
# Silent test player for the MUSIC page: a MediaSession with metadata, art and seek, no audio.
# ./build.sh install [2], then:
#   adb shell am start-foreground-service -n dev.lcdclock.fake/dev.lcdclock.fake.FakePlayer [-a play|pause|noart|notice|unnotice]
#   (notice: a long, silent test conversation for the notifications page; needs pm grant ... POST_NOTIFICATIONS)
#   adb shell am stopservice -n dev.lcdclock.fake/dev.lcdclock.fake.FakePlayer;  adb uninstall dev.lcdclock.fake
# With 2, a second player: package dev.lcdclock.fake2, app name "Tunes Test", its own tracks.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$SDK/build-tools/37.0.0
JAR=$SDK/platforms/android-35/android.jar
if ! command -v javac >/dev/null; then
    export PATH="$HOME/Android/jdk/bin:$PATH"
fi
rm -rf out && mkdir -p out/classes
if [ "${2:-}" = 2 ]; then
    sed -e 's/package="dev.lcdclock.fake"/package="dev.lcdclock.fake2"/' -e 's/Tube Test/Tunes Test/' AndroidManifest.xml > out/AndroidManifest.xml
else
    cp AndroidManifest.xml out/AndroidManifest.xml
fi
"$BT/aapt2" link -I "$JAR" --manifest out/AndroidManifest.xml -o out/unsigned.apk
javac --release 17 -Xlint:-options -cp "$JAR" -d out/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 33 --lib "$JAR" --output out $(find out/classes -name '*.class')
(cd out && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk
"$BT/apksigner" sign --ks ../../debug.keystore --ks-pass pass:android --out out/fake.apk out/aligned.apk
if [ "${1:-}" = install ]; then
    adb install -r out/fake.apk
fi
