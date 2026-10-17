# LCD Clock for the moto razr 40

A seven-segment, LCD-style clock face and a set of pages for the **moto razr 40**'s 1.5" outside
screen (368 × 194). No root needed. It plugs into Motorola's own clock styles, then adds pages and
panels you swipe through on the outside screen with the phone closed.

<p>
<img src="docs/screenshots/cover-face.png" width="276" alt="The clock face: time in light-blue segment digits, AM/PM, padlock and alarm bell, date and battery">
<img src="docs/screenshots/music-playing.png" width="276" alt="MUSIC page: track position in segment digits, scrolling title, album art and buttons">
<img src="docs/screenshots/cover-controls.png" width="276" alt="Quick controls panel">
</p>
<p>
<img src="docs/screenshots/cover-alarm.png" width="276" alt="ALARM page">
<img src="docs/screenshots/cover-calendar.png" width="276" alt="CALENDAR page, titles blurred">
<img src="docs/screenshots/cover-weather.png" width="276" alt="WEATHER page, place name blurred">
</p>

Unofficial, and not made by or affiliated with Motorola. It relies on Motorola's undocumented
clock-face plugin interface, so a Motorola update could break it.

> [!WARNING]
> **Banking apps.** LCD Clock needs an accessibility service ("LCD Clock cover button"), and
> banking apps treat an accessibility service in an app from outside Google Play as possible
> malware. HSBC's apps flag the phone, and a bank may lock or deactivate your account over it.
> LCD Clock switches the service off while the phone is open, but before you open a banking app:
>
> - **Check the cover button is off.** The cover button widget should show PAUSED or OFF, never a
>   red ON, and the quick settings tile should say "Off till closed" or "Off". If it's on, tap the
>   widget or the tile.
> - **Don't open a banking app while a video plays in apps like YouTube.** A video that's already
>   playing keeps the service on so it can keep playing when you close the phone. Stop the video
>   first (the service goes off a few seconds later), or turn off "Keep playing when you close the
>   phone" in the app. A video started while a banking app is still running doesn't switch it on.
> - **Check your banking apps are on LCD Clock's Banking apps list.** If you close the phone while
>   one of them is still running, even from Recents, the service stays off until you next open the
>   phone, because the banking app would notice it coming on. The cover pages and volume holds wait
>   until then too. Swipe the banking app away from Recents when you've finished to have them back.
>   Until you pick, the list is every app whose package name looks like a bank's.

## What it does

- **Clock face "LCD Digits"**: thin seven-segment digits with the unlit segments showing faintly,
  a blinking colon, AM/PM, the weekday, date and battery, a padlock while locked, and an alarm bell.
  It comes in four colours or Motorola's custom colour.
- **Pages**: swipe left or right on the face, or tap the icons in the menu row.
  - **TIMER**: a countdown timer.
  - **ALARM**: a daily alarm with snooze. The bell blinks while snoozed.
  - **CALENDAR**: the next 7 days.
  - **WEATHER**: the forecast from Open-Meteo.
  - **RECORDER**: a voice recorder.
  - **HOME**: three Home Assistant buttons.
  - **MUSIC**: one tab per app that's playing.
- **Panels**:
  - **Notifications** (swipe up): tap one to read all of it. Contents stay hidden while the phone
    is locked, and × and CLEAR are greyed out until it's unlocked.
  - **Quick controls** (swipe down): flashlight, SOS, Do Not Disturb, ring mode, media volume,
    battery saver, Night Light, Bedtime, Home Assistant buttons and a WireGuard VPN (through WG
    Tunnel).
- **Calls**: when a call rings with the phone closed, a CALL panel comes up over Motorola's own
  call screen. It shows the caller's photo (or their initial), their name as the calling app shows
  it (your contact's name, else the number) and the app, with a red DECLINE and a green ANSWER.
  Once answered, it shows the call's time, MUTE and a red HANG UP. It works for the phone, WhatsApp,
  KakaoTalk and other apps that post a call notification. Opening the phone hides it, and the call
  carries on inside. Some phone calls can't be muted from the outside screen; MUTE then shows
  NO MUTE.
- **Alarm and timer**: they ring on the outside screen with your choice of sound and vibration.
- **Volume-button holds**: with the phone closed, holding volume up or down opens a page of your
  choice, or toggles the flashlight.
- **Home screen widgets**: the face, or just the time.
- **Cover button switch**: a home screen widget and a quick settings tile show whether the
  accessibility switch is on, off until the phone closes, or off. A tap switches it off until the
  phone closes, or back on. Use it before banking while a video is playing.
- **Picture-in-picture**: video keeps playing when you close the phone. The razr would otherwise
  close the picture-in-picture window and stop the video. For this, the accessibility switch stays
  on with the phone open while a video app is playing, so a banking app opened then may object.
  Turn "Keep playing when you close the phone" off if that matters. A video started while a banking
  app is still running doesn't switch it on.

Everything is set up in the LCD Clock app on the inside screen. Its **Setup** list shows what's
still missing.

## Install

You need:

- **A moto razr 40** (not the Ultra) with USB debugging on: Settings → System → Developer options.
  Tested on Android 15.
- **A computer with adb**, from Android's platform-tools.
- **The Android SDK** with platform 35 and build-tools. build-tools 37.0.0 is preferred; the
  newest installed is used otherwise.
- **A JDK 17 or newer**, and **zip**.

Connect the phone and run:

```sh
./install.sh
```

It builds the APK, installs it and grants the permissions adb can grant: microphone, calendar,
location, notifications, audio files, secure settings (for the saver and Night Light controls, and
for switching the cover button), and usage access and `DUMP` (to see whether a banking app is still
running).

Then it opens the screens for the switches Android leaves to you, and waits while you turn each on:

1. **Accessibility**: turn on "LCD Clock cover button". This lets the outside screen open the pages
   and makes the volume holds work. The app switches it off while the phone is open and back on
   when you close it, because banking apps such as HSBC's refuse to run, or flag the phone, while an
   app from outside Google Play has accessibility on. So in Settings it shows as off while the
   phone is open.
2. **Notification access**: allow "LCD Clock music page". This is for the MUSIC tabs and the
   notifications panel.
3. **Clock styles**: pick **LCD Digits**. Motorola keeps this choice to itself, so it can't be set
   from adb.

Run `./install.sh` again to update. It skips whatever is already set up. If you have more than one
device connected, choose one with `ANDROID_SERIAL=<serial> ./install.sh`.

The first build creates `debug.keystore`. **Keep it**: updates have to be signed with the same key,
and installing over a differently signed copy means uninstalling first, which loses the app's
settings. The keystore isn't committed, so back it up yourself.

## Optional setup, in the app

- **Home Assistant**: enter your server's `https://` address and a long-lived access token (Home
  Assistant → your profile → Security). The HOME icons then show whether their entities are on, and
  switch them together. Plain `http://` addresses are refused, so the token never travels
  unencrypted. Buttons can also tap Home Assistant's own Button widgets instead.
- **VPN tile**: needs [WG Tunnel](https://github.com/zaneschepke/wgtunnel) with its remote control
  turned on. Enter its key and the tunnel name in the app. The key only goes to the WG Tunnel that
  was installed when you saved it: if you reinstall WG Tunnel from somewhere else, save it again.
- **Per page**: show or hide each page, change the tab order, and set how long the outside screen
  stays on. Also on the cards: the alarm's snooze length and the ring sounds.

## Privacy

Everything stays on the phone, except:

- **Weather**: approximate location goes to [Open-Meteo](https://open-meteo.com/), and Android's
  geocoder looks up the place name.
- **Home Assistant**: requests go over HTTPS to the server you enter. The token is kept in the app's
  encrypted storage and never logged.

## Building only

```sh
./build.sh            # builds lcd-clock.apk (no Gradle: aapt2, javac, d8, zipalign, apksigner)
./build.sh install    # builds, then runs ./install.sh
```

The SDK is looked for at `$ANDROID_HOME`, then `~/Android/Sdk`, then `~/Library/Android/sdk`.

## How it works

- **Clock face**: `LcdClockService` and `LcdSessionService` implement Motorola's clock-face plugin
  services. Motorola's clock app binds them and shows the face through a `SurfaceControlViewHost`.
  `LcdOptionsProvider` supplies the colour options to Motorola's picker. The interface classes
  those calls carry are recreated in `src/com/motorola/clockface/sdk/`.
- **Outside-screen activity**: the meta-data
  `com.motorola.allow_activities_on_small_external_display` lets the app's activity run on the
  outside screen at all. `CoverActivity` hosts the pages there.
- **Starting it with the phone closed**: only an accessibility service (`ButtonService`) may start
  activities from the background, so it opens `CoverActivity` on each outside-screen wake and on
  volume holds.
- **Accessibility only while closed**: `ButtonPause` takes `ButtonService` out of the enabled
  accessibility services when the phone opens and puts it back when it closes, using the "write
  secure settings" permission `install.sh` grants. It goes by the hinge angle, so locking the open
  phone doesn't count as closing it. With "keep playing" on, it stays on while a
  picture-in-picture-capable app plays, for `PipGuard`. If a banking app (`BankApps`) is still
  running as the phone closes, it stays off. Running means used since its process last died: usage
  access says when it was used, and process exit records (the `DUMP` permission) say when it died.
  `install.sh` grants both.
- **Music and notifications**: these come through a notification listener (`MediaListener`), which
  Android keeps running, so it also runs `ButtonPause`.
- **Calls**: `Calls` reads calls from their notifications, through a list of readers tried in
  order: Android's CallStyle extras (answer, decline and hang-up intents), then buttons matched by
  their labels in English or Korean. An app that needs its own reading gets a reader added to the
  front of `Calls.SOURCES`. While a call rings, Motorola's call screen is a system window above
  every app, so `CallOverlay` shows `CallView` as an accessibility overlay on the outside screen. With
  `ButtonService` off, it opens as a `CoverActivity` page instead, which Motorola's screen may hide.

## Layout

- `src/dev/lcdclock/`: the app.
  - `CoverActivity`, `CoverTabs` and `SwipeFrame` hold the pages.
  - `CoverPage` is the shared base for the calendar, weather, recorder and panel pages.
  - `LcdClockView` draws the face, and `SegmentPainter` draws the segment digits and icons.
- `src/com/motorola/clockface/sdk/`: Motorola's plugin interface classes, recreated.
- `res/`, `assets/fonts/`: resources, and the DSEG fonts.
- `test/fakeplayer/`: a silent test media player for working on the MUSIC page.

## Credits

The segment fonts are [DSEG](https://www.keshikan.net/fonts-e.html) by keshikan, under the SIL Open
Font License 1.1 (`assets/fonts/DSEG-LICENSE.txt`).

## Licence

MIT (`LICENSE`), except the DSEG fonts in `assets/fonts/`, which keep their own licence.
