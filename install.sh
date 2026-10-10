#!/usr/bin/env bash
# Puts LCD Clock on a moto razr 40 over adb in one go: builds the APK when the sources are newer
# than it, installs it, grants the permissions adb can grant, then opens the screens for the
# switches only you can turn on (accessibility, notification access, the clock style) and waits
# while you do. Running it again updates the app and skips whatever is already set up.
# Usage: ./install.sh [--force]   (--force: install on a phone that isn't a razr 40 anyway)
set -euo pipefail
cd "$(dirname "$0")"

PKG=dev.lcdclock
APK=lcd-clock.apk
FORCE=0
for arg in "$@"; do
    case "$arg" in
        --force) FORCE=1 ;;
        *) echo "usage: $0 [--force]" >&2; exit 2 ;;
    esac
done

say() { printf '\033[1m%s\033[0m\n' "$*"; }
die() { printf 'install.sh: %s\n' "$*" >&2; exit 1; }
phone() { adb shell "$@" | tr -d '\r'; }
# Whether a line of stdin matches (grep -q would stop reading early, and with pipefail the
# writer's broken pipe would count as no match).
has() { grep -E "$@" >/dev/null; }
# Opens a screen on the phone; false if the phone has no such screen.
open_screen() { ! phone am start "$@" 2>&1 | has -i "^error|exception"; }
# Waits for Enter when someone is at the terminal; otherwise carries on.
pause() {
    if [ -t 0 ]; then
        read -r -p "  $1 Then press Enter. " _
    fi
}

command -v adb >/dev/null || die "adb not found: install Android's platform-tools and put adb on your PATH"

# Exactly one phone, connected and allowed (or choose one with ANDROID_SERIAL).
if [ -z "${ANDROID_SERIAL:-}" ]; then
    count=$(adb devices | awk 'NR > 1 && $2 == "device"' | wc -l | tr -d ' ')
    if [ "$count" = 0 ]; then
        if adb devices | has -w unauthorized; then
            die "the phone hasn't allowed this computer yet: unlock it and accept the USB debugging prompt"
        fi
        die "no phone found: connect it by USB with USB debugging on (Settings → System → Developer options)"
    fi
    [ "$count" = 1 ] || die "$count devices connected: choose one with ANDROID_SERIAL=<serial> $0"
fi

model=$(phone getprop ro.product.model)
device=$(phone getprop ro.product.device)
sdk=$(phone getprop ro.build.version.sdk)
if [ "$device" != lynkco ] && [ "$model" != "motorola razr 40" ] && [ "$FORCE" = 0 ]; then
    die "this is a $model ($device), not a moto razr 40: LCD Clock is made for the razr 40's outside screen. Run with --force to install it anyway."
fi
[ "$sdk" -ge 33 ] 2>/dev/null || die "LCD Clock needs Android 13 or newer; this phone has API level $sdk"
say "Phone: $model, Android $(phone getprop ro.build.version.release)"

if [ ! -f "$APK" ] || [ -n "$(find src res assets AndroidManifest.xml build.sh -newer "$APK" 2>/dev/null | head -n 1)" ]; then
    say "Building $APK"
    ./build.sh
fi

enabled() { phone settings get secure "$1" | tr ':' '\n' | has -x "$PKG/($PKG)?\\.$2"; }
# The cover button is on, or the app has switched it off only while the phone is open (ButtonPause,
# for banking apps), which MediaListener's dump says. After an update MediaListener takes a moment
# to come back.
button_on() {
    enabled enabled_accessibility_services ButtonService && return 0
    [ "$fresh" = 1 ] && return 1
    for _ in 1 2 3 4 5; do
        phone dumpsys activity service "$PKG/.MediaListener" | has "cover button: paused" && return 0
        sleep 1
    done
    return 1
}
face_in_use() { phone dumpsys activity services "$PKG/.LcdSessionService" | has "c:com\.motorola\.clockface"; }
# Device state 0 is folded shut on the razr.
is_open() { [ "$(phone cmd device_state print-state 2>/dev/null)" != 0 ]; }
inside_screen() {
    is_open || pause "Open the phone: the next settings show on the inside screen."
    adb shell input keyevent KEYCODE_WAKEUP
}

fresh=1
[ -n "$(phone pm path "$PKG" 2>/dev/null)" ] && fresh=0
# Motorola connects to the face while it's the chosen style, but after an update only from the
# next wake, so look now.
face_before=0
face_in_use && face_before=1
say "$([ "$fresh" = 1 ] && echo Installing || echo Updating) LCD Clock"
if ! out=$(adb install --no-incremental -r "$APK" 2>&1); then
    echo "$out" >&2
    case "$out" in
        *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
            die "the LCD Clock on the phone was signed with a different key, so it can't be updated from here. Install with the debug.keystore it was built with, or note down its settings, uninstall it (adb uninstall $PKG) and run this again." ;;
    esac
    die "adb install failed"
fi
# Motorola's launcher keeps loading home screen widgets from the old APK's path after an update
# ("Can't load widget") until it restarts: restart it if LCD Clock widgets are placed.
if [ "$fresh" = 0 ] && phone dumpsys appwidget | has "cmp:ComponentInfo\{$PKG/"; then
    adb shell am force-stop com.motorola.launcher3
fi

say "Granting permissions"
# Runtime permissions (recorder, calendar, weather, notifications, other apps' recordings),
# WRITE_SECURE_SETTINGS for the battery saver and Night Light quick controls and for switching the
# cover button, and DUMP for seeing when a banking app's process has died (BankApps).
for permission in POST_NOTIFICATIONS RECORD_AUDIO READ_CALENDAR ACCESS_COARSE_LOCATION READ_MEDIA_AUDIO \
    WRITE_SECURE_SETTINGS DUMP; do
    adb shell pm grant "$PKG" "android.permission.$permission" >/dev/null 2>&1 \
        || echo "  couldn't grant $permission: allow it from the app's Setup list"
done
# Usage access, so closing the phone on a banking app keeps the cover button off (BankApps).
adb shell appops set "$PKG" GET_USAGE_STATS allow >/dev/null 2>&1 \
    || echo "  couldn't allow usage access: allow it from the app's Banking apps card"

# The rest are switches Android leaves to you, on the inside screen.
missing=()

if ! button_on; then
    say "Accessibility: turn on \"LCD Clock cover button\""
    echo "  It lets the outside screen open LCD Clock's pages and the volume-button holds work."
    echo "  It switches itself off while the phone is open, so banking apps don't object to it."
    inside_screen
    open_screen -a android.settings.ACCESSIBILITY_SETTINGS || true
    pause "On the phone: (Downloaded apps →) LCD Clock cover button → on."
    enabled enabled_accessibility_services ButtonService || missing+=("accessibility: LCD Clock cover button")
fi

if ! enabled enabled_notification_listeners MediaListener; then
    say "Notification access: allow \"LCD Clock music page\""
    echo "  It lets the MUSIC tabs control what's playing and the notifications panel show them."
    inside_screen
    open_screen -a android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS \
        --es android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME "$PKG/$PKG.MediaListener" \
        || open_screen -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS || true
    pause "On the phone: allow notification access."
    enabled enabled_notification_listeners MediaListener || missing+=("notification access: LCD Clock music page")
fi

# Motorola keeps the chosen style to itself (nothing adb can set): on a first install open its
# picker; on an update just remind, if the face wasn't in use.
if [ "$fresh" = 1 ]; then
    say "Clock style: choose \"LCD Digits\""
    inside_screen
    open_screen -a com.motorola.intent.action.SelectAndConfigClockFace \
        || echo "  Couldn't open Clock styles: find them in Settings → External display."
    pause "On the phone: pick LCD Digits and apply it."
elif [ "$face_before" = 0 ] && ! face_in_use; then
    echo "  (If LCD Digits isn't your clock style yet: Settings → External display → Clock styles.)"
fi

# The app itself (its Setup list checks all of this), after a first install or with something
# left to do; only on the inside screen, since started while closed it lands on the outside one.
if [ "$fresh" = 1 ] || [ ${#missing[@]} -gt 0 ]; then
    if is_open; then
        open_screen -n "$PKG/.PreviewActivity" || true
    else
        echo "  Open the phone and start LCD Clock to see its Setup list."
    fi
fi
if [ ${#missing[@]} -gt 0 ]; then
    say "Installed. Still to switch on (the app's Setup list has buttons for these):"
    printf '  - %s\n' "${missing[@]}"
else
    say "Done: LCD Clock is installed and set up. Close the phone and touch the outside screen."
fi
