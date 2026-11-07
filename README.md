# 7xDash (head unit)

Dashcam, mirror and shortcut app for the Zeekr 7X head unit, in the Zeekr OS 7 style. English and Thai.

Based on [zeekr-shortcut-car](https://github.com/dts88/zeekr-shortcut-car), itself based on
[EVCam](https://github.com/suyunkai/EVCam). Released under GPL-3.0 (see `LICENSE` and `NOTICE.md`).

> Experimental, unofficial software with no vehicle safety certification. It does not replace the
> factory dashcam, reversing camera or blind-spot monitor. Do not use it while driving.

Plug in a USB drive before you start: videos, photos and the settings backup go there.

## Features and how to use them

Most features have a card on the **Home** page and a section in **Settings**. Hold a card to jump to its
settings; hold and drag to move it to another card's place. To choose what is on Home, tap **Customize Home**, the last card: drag a card by its dots to move it, × to
remove, tap a card under More cards to add it back. With Beta features on, Stable and Beta tabs split the cards. The rail on the left (Home, Record, Dim, Rear, Capture, Megaphone, EQ, AirPlay, Settings) is on
every page.

### Drive recorder
Records the surround and cabin cameras, with a driving info bar (speed, gear and more).
- **Record** on the rail or the floating button starts and stops recording.
- **Videos** on Home plays clips on a timeline.
- Settings → **Recording** and **Storage**: stream profile, record on launch, plate number stamp,
  where videos go and how much space they may use. Settings → System: keep recording when the screen goes off.

### Photo and instant capture
- **Photo**: tap the card for a photo from every camera; hold it to see your photos.
- **Instant capture**: saves 10 seconds before and after. Tap the card, press a shortcut, or turn on
  **Flash-to-pass** on the card and flash the high beam while recording.
- **Saved captures** lists and deletes them.

### Super mirror
A floating window showing any surround camera; the rear view is mirrored like the interior mirror.
- Turn it on from **Rear** on the rail, the **Rear camera** card, or Settings → Super mirror.
- Drag the window's sides to move it, pinch to resize, push it to a screen edge to dock it (tap the
  strip to bring it back).
- In the middle: swipe up or down to adjust the framing, left or right to change camera. With
  **Button mode**, tap the top, bottom, left or right area to pick front, rear, left or right.
- Settings → Super mirror: fisheye correction, field of view, front and rear only, window size, reset.

### Blind spot (turn-signal side view)
While you signal, the camera on that side pops up and closes when the signal goes off.
- Tap the **Blind spot** card to cycle: off, centre screen, HUD.
- Settings → Side mirror: show from a speed, straighten the picture, size and position, HUD size,
  how long it stays open, instant pop-up in D.

### Cabin passenger
A floating window with the rear cabin camera. It moves, pinches and docks like the Super mirror.
Open it with its shortcut; size it in Settings → System → Cabin passenger.

### Dim overlay
Covers the whole screen with an adjustable veil so maps are less bright at night; touches still reach
the app underneath.
- **Dim** on the rail or the moon on the floating button turns it on or off. Drag on the moon to make
  it darker or lighter.
- Settings → Dim overlay: opacity, brightness, warmth, pass touches through, reset.

### Floating button
A small button over every app: record (with the elapsed time), the dim moon, and quit.
- Settings → Floating button: what a tap and a long press do, opacity, lock position, show recording time.
- **Quit**: tap once (it turns red), tap again within 3 seconds to stop recording and close 7xDash.

### Megaphone
Talk to people outside the car through the outside speaker, using the cabin microphone.
- Tap the **Megaphone** card or rail button, or set a button for it (press and hold, or press once
  for a few seconds).
- Settings → Megaphone: how the button opens the mic, volume, and **Speakers**: tick every speaker
  that should play; the megaphone and the outside speaker play on all ticked speakers at once.

### Outside speaker
What you AirPlay to 7xDash (music, screen mirroring, cast videos) also plays on the outside speakers.
- Tap the **Outside speaker** card, or use its shortcut. Hold the card for its volume.
- CarPlay, Spotify on the head unit and the radio cannot be sent outside: the car does not let apps
  copy their sound. Play from your phone over AirPlay instead.

### Announcements
Play a phrase or a sound on the outside speakers.
- Tap the **Announcements** card (or its shortcut) for a floating picker over any screen; tap one to play it.
- Hold the card, or open Settings → Megaphone → Announcements, to add your own phrases, delete them,
  and pick one to play when **Sentry Mode arms** (at most once a minute).
- Eight built-in sounds (chime, double beep, siren, horn, ding-dong, alarm, reversing beeps, attention chime).
- Phrases play in your own voice if you record them (the mic next to each phrase), otherwise in
  7xDash's offline English voice (tap the English voice row once to download it, 22 MB) or the head
  unit's voice. Thai phrases play only when recorded.
- Another shortcut action plays one chosen announcement straight away.

### AirPlay
Mirror your iPhone, or cast videos from apps like YouTube, to the head unit.
- Tap **AirPlay** on the rail to turn the receiver on, then pick 7xDash in the iPhone's Screen Mirroring.
- The picture opens in a floating window: drag to move, pinch to resize, push it to a side to dock it,
  double-tap for small or large, × to stop.
- Settings on the AirPlay page: video casting, show the picture while driving.

### EQ
A cabin equalizer on top of the car's own sound.
- Tap **EQ** on the rail. Set the car's equalizer to Flat first, then play music and tune; moving a
  fader on a built-in preset saves it to My presets.

### HUD notifications
Shows 7xDash messages on the driver HUD. Tap the card to turn it on or off; Settings → Super mirror
sets the left and right position of the card.

### Shortcuts, smart stickers and controllers
Give steering-wheel keys, Bluetooth sticker buttons (ZEEKR_button) and game controllers an action:
record, photo, capture, mirror, dim, megaphone, AirPlay, EQ, outside speaker, announcements, quit and more.
- **Shortcuts** card → **Add shortcut**, press the button, choose tap, hold or double press, then pick
  the action. Each saved shortcut has **Edit** and **Delete**.
- **Smart sticker** card: long-press the sticker until it wakes, scan, then press it to save an action.
- Settings → **Controller** does the same for game controllers.

### Mood light (experimental)
Control a cheap Bluetooth RGB light (ELK-BLEDOM or Triones types) from the head unit.
- Settings → Smart sticker → **Mood light**. Close the light's own phone app first, scan, tap
  **Connect**, then use on/off, the colour swatches and sliders.

### Remote view and 7xDash web
Watch the cameras and control the car from your phone.
- Tap the **Remote view** card and scan the QR code with your phone.
- On the web page: live view of each camera, the outside speaker on/off, outside speaker and megaphone
  volumes, and which speakers play.

### Google Drive
Uploads instant captures and clips. Open the **Google Drive** card and sign in on your phone.

### Where I parked (beta)
When the car shifts into P, reads the floor and bay signs from the cameras, offline, and shows them with
a photo in the notification shade and on 7xDash web.
- Turn on Settings → Interface → **Beta features**, then tap the card to read now; its **Auto on P**
  switch reads every time you park.
- Its **Pillar scan** switch keeps reading the column signs while you creep under 3 km/h in D or R. Each new
  floor or bay shows on the HUD, and at P it fills in whatever the parked read could not see.
- Hold the card to scan once by hand: **Scan now** shows the photo, the floor and bay read, what is
  remembered and the raw text, and sends the spot to 7xDash web.

### OBD2 (beta)
Reads the car through an ELM327 adapter in the OBD socket, while the screen is open.
- Pair a Bluetooth Classic adapter in the car's Bluetooth settings, or join a Wi-Fi adapter's network.
  A Bluetooth LE adapter needs no pairing: press **Scan for BLE** on the screen and choose it. Smart
  stickers drop off during the 10-second search.
- Turn on Settings → Interface → **Beta features**, tap the **OBD2** card, choose the adapter, press
  **Connect**.
- Shows the 12 V battery voltage, the standard values the car reports, and raw bytes read directly
  from the battery module with the request used on the Zeekr 001. The 7X may answer little: its
  owners report only the VIN and voltage so far, and the bytes are not decoded yet.
- **Record all signals** saves everything the adapter can hear to `7xDash/odb_logs` on the USB drive.
  After a short check at the start it only listens. It stops after 1 hour, or 5 minutes of silence.
  A floating window lists signals that just changed. Tap **Mark** when something happens: the log
  notes the moment and what changed. Have a passenger watch it, or read the log later.
- Read only: it cannot clear codes or change anything. Unplug the adapter when you are done, because
  many keep drawing from the 12 V battery.

### Navigation (beta)
Turn-by-turn navigation on a Google map, with live traffic, spoken turns and the next turn on the HUD.
- Turn on Settings → Interface → **Beta features**, then tap the **Navigation** card.
- Search for a place, pick the one shared from your iPhone (Battery at arrival), or touch and hold on the
  map. The route shows its time, distance, arrival time and battery at arrival; tap **Start**.
- While guiding: the banner shows the next turn and how far it is, the map follows the car (move it by
  hand and **Back to the car** returns), and leaving the route finds a new one. Guidance keeps going
  on the HUD and by voice when you leave the screen, until you arrive or tap **End**.
- A route you pick before Start stays yours for the trip. If you leave it, a new route is found.
- **Navigation** tab in the action picker: **Floating map on / off**, **Map zoom in / out**, and **HUD map zoom** (Furthest, Medium, Close, Adaptive to speed; each press says which), for any button, sticker, Macro pad or automation.
- **Speak the turns** and **Next turn on the HUD** are on the trip card. Turns are spoken by the head
  unit's voice, or the offline English voice (Announcements) when it has none. Thai needs a Thai voice
  on the head unit.
- Needs a Google Maps Platform key in the build (`MAPS_API_KEY`, see Release) with the **Maps SDK for
  Android**, **Routes API** and **Places API (New)** enabled, and internet. The map needs Google Play
  services on the head unit; without it, guidance still works with no map.

### Automation
When something happens on the car, 7xDash does what you picked. Open the **Automation** card on Home. Off until you turn it on.
- **When:** the car turns on or off, shift to P, D or R, speed reaches or drops to a number, it gets dark or light, it starts or stops raining, battery drops to a number, outside temperature reaches or drops to a number.
- Pick several **When** to need them all: "it gets dark" and "it starts raining" runs the moment both are true, in either order.
- **Do**, as many as you like, in order: drive mode (Comfort, Normal, Sport), mood light on or off, mood light colour, car ambient light and its brightness, dim overlay, AC on or off, temperature zones (synced or dual), open the glovebox (only in P), or any shortcut action.
- Automations run on a change, not on a state: opening the app in D does not run "Shift to D". A speed has to drop 3 km/h back before it runs again, and a number or rain runs at most once in 30 seconds.
- Press **Add automation**, or start from an example. Pick When on the left and Do on the right; the line at the bottom reads back what you built. Tap a choice again to take it back.
- **Try it now** at the bottom runs its actions straight away. A mood light colour stays until the gear rules pick a new look.

### Settings backup on USB
Your settings (switches, shortcuts, stickers, lights, speakers, EQ, profiles, announcements and
recordings) are copied to `7xDash/settings/` on the USB drive a few seconds after any change, and come
back by themselves after a reinstall or Clear data.
- Settings → System → **Back up settings to USB** copies now; **Restore settings from USB** puts the
  copy back and restarts 7xDash.
- Android permissions and Bluetooth pairing are not part of the copy.

### Diagnostics
If something goes wrong, restart the app. If it continues, export a report from Settings → System →
**Diagnostics** and attach it to an issue.

## Credits

7xDash stands on the work of two projects and their authors:

- [**EVCam**](https://github.com/suyunkai/EVCam) by [suyunkai](https://github.com/suyunkai): the
  original drive recorder, playback, keep-alive and blind-spot features that everything here grew from.
- [**zeekr-shortcut-car**](https://github.com/dts88/zeekr-shortcut-car) by [dts88](https://github.com/dts88):
  the Zeekr head unit port with shortcuts, which 7xDash is forked from.

Thank you both. Copyright in their code stays with them and their contributors; see `NOTICE.md` for
details and the other open-source components used.

## Release

Bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section to `CHANGELOG.md`,
then push a tag `v<versionName>`. GitHub Actions builds, tests and publishes the release.

Navigation needs the repository secret `MAPS_API_KEY` (or `MAPS_API_KEY=` in `local.properties` for a local
build): a Google Maps Platform key with Maps SDK for Android, Routes API and Places API (New) enabled.
Restrict it to Android apps with package `lab.sevenx.dash` and the release signing certificate's SHA-1;
7xDash sends both with each Routes and Places call so the restriction also covers them.
