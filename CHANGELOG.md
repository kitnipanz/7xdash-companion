# Changelog

## [1.4.1-beta] - 2026-10-26

### Recording
- **One encoder for the whole trip.** Each camera used to stop and rebuild its video encoder at every 1-minute video, which lost the last frames of each video and rebuilt the car's encoder thousands of times a week. Now the encoder keeps running: at each minute it asks for a keyframe, and the next video starts exactly on it. Videos follow on from each other with no missing frames, which also fills in instant captures and flash-to-pass clips that cross two videos. If the encoder gives no keyframe within 7 seconds, or is not working right, 7xDash falls back to the old rebuild for that video.
- **Recording pipeline setting removed** from Developer options. Recording always uses MediaCodec; a saved MediaRecorder choice no longer has any effect.

## [1.4.0] - 2026-10-26

### Mood light
Mood light is now a regular feature: it no longer needs **Beta features**. Bluetooth light strips and the car's own ambient light change color with the gear and the speed. Find it on the home screen, or in Settings › Mood light (under Smart sticker).

- **Lights**: ELK-BLEDOM and Happy Lighting (Triones) Bluetooth strips. Tap **Scan**, then **Connect** or **Add to group**.
- **Car ambient light**: the car's own top-zone ambient light can be in a group too, so it changes with your strips. It shows a solid color or turns off, and keeps the car's own brightness. It does not count towards the 4 Bluetooth lights.
- **Groups**: name groups of lights (for example Footwell and Dashboard). Each group has its own lights, color and gear and speed colors. Up to 4 groups and 4 Bluetooth lights in all.
- **Change with gear**: a color and brightness for P, R, D and D at 0 km/h, or Light off, or leave the light as it is. N leaves the light as it is.
- **Speed colors in D**: up to 8 "from X km/h" colors. **Blend between speed colors** fades from one to the next, so orange at 80 km/h turns to red by 120 km/h.
- **Try it**: a pretend car runs the group's colors. Pick P, R, N or D and hold **Hold to accelerate** or **Brake**; the connected lights follow, so you can check the colors parked.
- **On/off**: a master switch in the Mood light screen, an On/Off pill on the home card, and a **Mood light on/off** shortcut for any button or sticker.
- Strips keep up with fast color changes: power-on goes only when a strip is off, and the latest color is sent once more when changes stop, so a lost command is fixed.
- Lights show solid colors only. The strips' built-in animations did not look like their previews, so effects are not offered.

### Car ambient light (experimental)
- Settings › Mood light › **Car ambient light (experimental)** lists the car's ambient-light settings, with what each one reads now, and can send one value after you confirm. It is for finding out what else the car's ambient light can do; try it parked. Every scan and send goes in the black box.

## [1.3.20-beta] - 2026-10-26

### Mood light
- **Bluetooth strip keeps up with color changes.** The strip sometimes stayed on an old color while the car's ambient light changed. Power-on now goes only when the strip is off, so it no longer brings back its last color on top of the new one. A command lost on the way is fixed too: half a second after the last change, the strip gets its latest color once more.
- **Effects are off for now.** The built-in animations did not look like their previews on a real strip, so every gear and speed color is a solid color or off. One button in the editor switches between **Solid color** and **Light off**. Looks saved with an effect show as a steady light in their color. The default P and 120 km/h colors are solid now, so the default speed colors blend.

## [1.3.19-beta] - 2026-10-26

### Car ambient light in Mood light
- The car's own ambient light (the top zone) can now be one of the lights in a Mood light group, so it follows gear and speed with your Bluetooth lights. Find **Car ambient light** in the lights list and tap **Add to group**. It does not count towards the 4 Bluetooth lights.
- It gets the same colour as the rest of the group, blends between speeds too, and turns off when the look is Off or the Mood light switch is off. It cannot animate or dim: an effect shows as a steady colour in that colour, and the car keeps its own brightness.
- The car takes a new colour at most every quarter second, so it is not flooded while the speed blend changes colour.

## [1.3.18-beta] - 2026-10-26

### Car ambient light (experimental)
- New **Car ambient light (experimental)** screen in Settings › Mood light, to find out whether 7xDash can set the car's own ambient light. Nobody knows yet which car setting drives it, so **Scan car** lists every name in the car's system that sounds like ambient light, with whether the car supports it and what it reads now.
- **Try** on a setting sends one value (one that goes with it, or a number you type) only after you confirm, then shows what the car reads after. It only sends to ambient-light names, never on its own; try it parked. Every scan and send goes in the black box, so a Diagnostics report shows what worked.

## [1.3.17-beta] - 2026-10-26

### Mood light (beta)
- **Mood light on/off** master switch. Off, every group stops following the gear and the connected lights turn off; on, each group follows its own rules again. It sits in the Mood light screen's title bar, as an On/Off pill on the Mood light home card, and as a new **Mood light on/off** shortcut for any button or sticker. The pill and the shortcut say which way it went; with Beta features off they only say to turn it on first.
- The Mood light screen is easier to find your way around: under the group there are three pages, **Color**, **Gear & speed** and **Try it**, one at a time (it opens on the one you used last). **Scan** now sits above the lights list, titled with the open group's name, and an empty list shows the three steps to add a light.
- Without a connected light the Color page no longer looks greyed out: a note says to connect one, and the color you pick is still saved for the group. Only On and Off wait for a light.
- Shorter help text throughout, the connection status moved into the group's card, and a new first group is named "Group 1".

## [1.3.16-beta] - 2026-10-26

### Mood light (beta)
- New **Try it** card at the bottom of the Mood light screen: a pretend car runs the open group's gear and speed colors, even with Change with gear off. Pick P, R, N or D, hold **Hold to accelerate** to speed up (about 0 to 100 km/h in 6 seconds), hold **Brake** to slow down, or let go to coast. It shows the speed, which rule is showing (for example "80 → 120 km/h · 20% of the way") and the color on an LED strip, and the group's connected lights follow too.
- Up to 8 speed colors in D (was 6). Every pair of solid colors next to each other blends, so a chain like 10, 20, 40, 70, 80, 120, 150 km/h fades all the way through.
- For development: `tools/mood-light-sim` is the same simulator as a web page for Chrome on a computer, talking to a real light over Web Bluetooth.

## [1.3.15-beta] - 2026-10-25

### Mood light (beta)
- Mood light is now a beta feature: it shows only while **Beta features** is on (Settings → Interface). It has its own **Mood light (beta)** section under Smart sticker and a home card.
- **Effects** for ELK-BLEDOM and Happy Lighting lights: Breathe, Blink, Rainbow fade, Rainbow jump, Rainbow strobe and Light off, with an effect speed. Breathe and Blink use the nearest basic color (red, green, blue, yellow, cyan, magenta or white). Each effect plays as a moving LED strip in the picker, so you can see what it looks like first.
- **Change with gear**: pick a color, brightness and effect for P, R, D and D at 0 km/h, or leave the light as it is for a gear. N leaves the light as it is.
- **Speed colors in D**: up to 6 "from X km/h" colors. **Blend between speed colors** mixes two solid colors by speed (blue at 100 and red at 120 give 75% blue, 25% red at 105). Effects switch at their speed.
- **Groups**: name groups of lights (for example Footwell and Dashboard). Lights in a group act together, and each group has its own lights, color and gear and speed colors. A light is in one group at a time; up to 4 groups and 4 lights in all. Your earlier light becomes the first group, "Lights".
- While the car is on, 7xDash keeps each group's lights connected and changes them with the gear; opening the Mood light screen takes them over until you close it.
## [1.3.14] - 2026-10-25

- Auto dim no longer turns off a dim you switched on by hand outside the night hours. It used to re-check every minute and undo it; now it only switches dim when the night window starts or ends (and once when you turn Auto dim on or change its hours).

## [1.3.13] - 2026-10-25

Everything since 1.3.9, the last stable release.

### Anonymous usage stats (new, opt-in)
- **Anonymous usage stats**, off until you say yes. On first open 7xDash asks whether to turn them on; tick **Remember my choice** and it never asks again, otherwise it asks on the next open. The **Anonymous usage stats** switch in Settings → Interface turns them on or off any time (and stops the question). Both are kept in the USB stick settings copy. With them on, 7xDash sends which features get used to Google Analytics: app start (app version, system build), home card taps and holds, pages opened, shortcuts run (from any card, side rail, key or sticker), recording start and stop (reason, rough length) and a count of crashes (the error type only). Only a random install id is sent, no email, plate or location. Events go out together at most once a minute while in use, about 20–50 KB of data a day. Turning the switch off stops sending at once. Builds without `GA_MEASUREMENT_ID` / `GA_API_SECRET` send nothing.

### Diagnostics
- New **Install history** section. Every start records when 7xDash was installed and last updated and which system build the car runs, in the app and in `7xDash/history/install.txt` on the USB stick (that copy outlives an uninstall). A new line appears only when something changed, marked REINSTALLED, app updated or SYSTEM CHANGED, so the next time the car removes the app it can be matched to a system update.
- New **Memory over time** section for spotting a leak. Every 5 minutes 7xDash notes its memory (Java heap, native heap, total and graphics memory, threads, open files), plus a line whenever the system asks for memory back; about a week is kept, also in `7xDash/history/memory.txt` on the USB stick. Each run shows start, peak and latest values and growth per hour (after a 30-minute warm-up), marked GROWING when a value keeps climbing. A new run in the list means 7xDash restarted or was stopped by the system.

## [1.3.12-beta] - 2026-10-25

- **Anonymous usage stats**, off until you say yes. On first open 7xDash asks whether to turn them on; tick **Remember my choice** and it never asks again, otherwise it asks on the next open. The **Anonymous usage stats** switch in Settings → Interface turns them on or off any time (and stops the question). Both are kept in the USB stick settings copy. With them on, 7xDash sends which features get used to Google Analytics: app start (app version, system build), home card taps and holds, pages opened, shortcuts run (from any card, side rail, key or sticker), recording start and stop (reason, rough length) and a count of crashes (the error type only). Only a random install id is sent, no email, plate or location. Events go out together at most once a minute while in use, about 20–50 KB of data a day. Turning the switch off stops sending at once. Builds without `GA_MEASUREMENT_ID` / `GA_API_SECRET` send nothing.

## [1.3.11-beta] - 2026-10-25

- Diagnostics: new **Memory over time** section for spotting a leak. Every 5 minutes 7xDash notes its memory (Java heap, native heap, total and graphics memory, threads, open files), plus a line whenever the system asks for memory back; about a week is kept, also in `7xDash/history/memory.txt` on the USB stick. Each run shows start, peak and latest values and growth per hour (after a 30-minute warm-up), marked GROWING when a value keeps climbing. A new run in the list means 7xDash restarted or was stopped by the system.

## [1.3.10-beta] - 2026-10-25

- Diagnostics: new **Install history** section. Every start records when 7xDash was installed and last updated and which system build the car runs, in the app and in `7xDash/history/install.txt` on the USB stick (that copy outlives an uninstall). A new line appears only when something changed, marked REINSTALLED, app updated or SYSTEM CHANGED, so the next time the car removes the app it can be matched to a system update.

## [1.3.9] - 2026-10-25

Everything since 1.3.6, the last stable release.

### Remote view
- The Driver face and Backseat cameras now show on 7xDash web when the car screen is off or 7xDash is not in front. While your phone watches a cabin camera, that camera stays open with the screen dark (only that one; the other cabin camera is still let go). When the main screen's preview is not available, the picture comes from the camera's photo output, a few frames a second.
- The cabin cameras are no longer skipped when the surround camera is not up, and a dropped link lets the watched cabin camera go.

### Where I parked (beta)
- New **Pillar scan** switch on its home card (off by default). While you creep under 3 km/h in D or R, 7xDash reads the column signs from the front and side cameras every 3 seconds and remembers the latest floor and bay. Each new one shows on the HUD; at P it fills in whatever the parked read could not see. Forgotten after 10 minutes or when you drive off from P.
- Hold the card for a scan window. **Scan now** reads once and shows the photo, the floor and bay, what is remembered and the raw text read, and sends the spot to 7xDash web.

## [1.3.8-beta] - 2026-10-25

- Remote view: the Driver face and Backseat cameras now show on 7xDash web when the car screen is off or 7xDash is not in front. While your phone watches a cabin camera, that camera stays open with the screen dark (only that one; the other cabin camera is still let go). When the main screen's preview is not available, the picture comes from the camera's photo output, a few frames a second.
- Remote view: the cabin cameras are no longer skipped when the surround camera is not up, and a dropped link lets the watched cabin camera go.

## [1.3.7-beta] - 2026-10-25

- Where I parked: new **Pillar scan** switch on its home card (off by default). While you creep under 3 km/h in D or R, 7xDash reads the column signs from the front and side cameras every 3 seconds and remembers the latest floor and bay. Each new one shows on the HUD; at P it fills in whatever the parked read could not see. Forgotten after 10 minutes or when you drive off from P.
- Where I parked: hold the card for a scan window. **Scan now** reads once and shows the photo, the floor and bay, what is remembered and the raw text read, and sends the spot to 7xDash web.

## [1.3.6] - 2026-10-24

- Side mirror: new defaults tuned on the car. Rotate left is -37° (right stays 37°), and the picture on the HUD is 47% wide (was the full strip). Values you already set are kept.
- AirPlay: the floating window's hint now describes its gestures (pinch to resize, push to a side to dock) instead of the removed corner handle.

## [1.3.5] - 2026-10-24

- Announcements is no longer a beta feature: its home card, the Announcements entry in Settings › Megaphone and playing when Sentry Mode arms work without Beta features, and "beta" is gone from its shortcut names. Where I parked stays beta.
- The AirPlay and cabin passenger windows move, resize and dock like Super mirror: drag anywhere to move, pinch to resize around your fingers (the shape is kept), push or flick to a side to dock with a strip showing, tap the strip to bring it back. Position and size are remembered. The AirPlay corner resize handle is gone; double-tap still switches small and large.

## [1.3.4] - 2026-10-24

Everything since 1.2.1, the last stable release.

### Settings on USB (new)
- Settings are kept on the USB stick (`7xDash/settings/`) a few seconds after any change, and come back by themselves after a reinstall or Clear data: switches, shortcuts, stickers, the mood light, speakers, EQ, profiles, announcements and their recordings. Settings › System has Back up settings to USB and Restore settings from USB. Android permissions and Bluetooth pairing are not part of the copy.

### Announcements (beta)
- Play a phrase or a sound on the speakers picked for the megaphone, at the megaphone volume. Eight built-in sounds (Chime, Double beep, Siren, Horn, Ding-dong, Alarm, Reversing beeps, Attention chime) and ready English phrases; add your own in Settings › Megaphone › Announcements.
- Phrases play in your own recorded voice (the mic on each phrase), or in 7xDash's offline English voice (downloaded once, 22 MB), or the head unit's voice. Thai phrases play only when recorded; the ready-made Thai phrases are gone.
- A floating picker opens over any screen from the Announcements shortcut or the home card. Another shortcut action plays one chosen announcement straight away.
- When Sentry Mode arms, a chosen announcement can play (at most once a minute).
- With Beta features off, the home card, the Megaphone settings entry and the Sentry Mode trigger are hidden or off; the shortcuts still work.

### Speakers
- The megaphone and the outside speaker play on every speaker you tick in Settings › Megaphone › Speakers, at the same time. 7xDash web can tick them too.

### Shortcuts
- Each saved shortcut has an Edit button to change its action or press type.

### Mood light (experimental)
- Settings › Smart sticker › Mood light finds and controls cheap Bluetooth RGB lights (ELK-BLEDOM and Triones types): on/off, colour and brightness.

### Size
- The APK is about 30 MB bigger, for the offline speech engine.

## [1.3.2-beta] - 2026-10-24

- Settings are kept on the USB stick (`7xDash/settings/`) a few seconds after any change, and come back by themselves after a reinstall or Clear data: switches, shortcuts, stickers, the mood light, speakers, EQ, profiles, announcements and their recordings. Settings › System has Back up settings to USB and Restore settings from USB. Android permissions and Bluetooth pairing are not part of the copy.
- Announcements: record a phrase in your own voice (the mic on each phrase, or Record next to Add); it then plays instead of a voice engine. This is how Thai phrases play when the head unit has no Thai voice.
- Announcements: 7xDash's own English voice, downloaded once (22 MB) and working offline, for head units with no English voice. The APK grows by about 30 MB for the offline speech engine.
- Shortcuts: each saved shortcut has an Edit button to change its action or press type.
- New experimental Mood light screen (Settings › Smart sticker) for cheap Bluetooth RGB lights (ELK-BLEDOM and Triones types): find, connect, on/off, colour and brightness. Granting Bluetooth permission from a light's Connect button now connects it instead of scanning.

## [1.3.1-beta] - 2026-10-24

- Announcements: phrases said "no voice" on the car. 7xDash now declares that it uses text-to-speech (from Android 11 an app cannot see speech engines it does not declare), tries every speech engine on the head unit, and tries several English (US, UK, generic, Australian) and Thai variants. The black box lists the head unit's speech engines and what each answered, so a car with no English or Thai voice shows what it does have.
- New shortcut action: Play an announcement (beta). Pick which one when you set the button; pressing it plays that announcement straight away. Shortcut lists show it as "Play: <name>".
- Five more sounds: Horn, Ding-dong, Alarm, Reversing beeps and Attention chime. They join your list once, after your other sounds; sounds you deleted stay deleted.

## [1.3.0-beta] - 2026-10-24

- New beta Announcements: play a phrase or a sound on the speakers picked for the megaphone, at the megaphone volume. Comes with Chime, Double beep and Siren, and ready phrases in English and Thai; type your own in Settings > Megaphone > Announcements. Phrases are read out by the head unit's own voice (Thai when the phrase has Thai in it). While one plays, AirPlay music on the outside speaker drops to 15%.
- Floating picker: the new Announcements shortcut (steering wheel keys, controllers, stickers) or the home card opens it over any screen; tap one to play it. Hold the home card for the list.
- When Sentry Mode arms: pick an announcement to play each time Sentry Mode changes to armed, at most once a minute. Every Sentry Mode change goes to the black box.
- With Beta features off, the home card, the Megaphone settings entry and the Sentry Mode trigger are hidden or off; the shortcut still works.

## [1.2.1] - 2026-10-24

- Pick several speakers in Settings > Megaphone > Speakers: the megaphone and the outside speaker play on every ticked speaker at the same time. Tap a speaker to add or remove it; at least one stays ticked. A speaker the car refuses is skipped, and the black box says which ones played. Your earlier pick carries over.
- 7xDash web: the Outside speaker card lists the car's speakers to tick, and shows Off instead of Beta.

## [1.2.0] - 2026-10-24

Everything since 1.1.1, the last stable release.

### Outside speaker (new, out of beta)
- What you AirPlay to 7xDash also plays on the outside speaker, the one the megaphone uses: music, screen mirroring, and videos cast from a video app's AirPlay button. It has its own volume, and drops to 15% while the megaphone talks.
- Turn it on from the home card, the new Outside speaker on/off shortcut, Settings > Megaphone > Outside speaker (hold the home card to get there), or 7xDash web.
- CarPlay, Spotify on the head unit and the radio cannot be sent outside: the car does not let apps copy their sound. Play from your phone over AirPlay instead.

### AirPlay
- Sound plays as soon as the iPhone connects, for music, mirroring and cast videos, without starting a radio channel first. 7xDash now asks the car for media audio focus while it plays, so starting AirPlay may pause the radio or another media app.

### 7xDash web
- New Outside speaker card: turn it on or off, and set the outside speaker and megaphone volumes.

### Quit
- Quit button on the floating buttons, right of the dim moon. The first tap turns it red; a second tap within 3 seconds stops recording and closes 7xDash.
- Quit app can be picked as a shortcut for steering wheel keys, controllers and stickers.

### Cabin passenger
- Shows the rear cabin camera (Cabin 2), also with Cabin 1 or Cabin 2 off in the camera profile; the camera is then opened for the window only, not recorded.
- No longer opens blank: the window gives its picture back when closed, re-attaches when no frame arrives for 4 s, and says why when the car has no cabin camera.

### Home
- Fits the screen: the widget column takes 30% of the width, and the cards take 2 to 4 columns with rows of equal height, scrolling when they do not fit.

### Beta features
- New Settings > Interface > Beta features switch, off by default. Beta features are hidden and do not run while it is off.
- Where I parked (beta): when the car shifts into P, 7xDash reads the floor and bay signs from the cameras, offline, and shows them with a photo in the notification shade and on 7xDash web. Home card with an Auto on P switch, and a Where did I park shortcut.

### Logs
- Black box lines for start-up, switches, exits, quitting, AirPlay audio focus and the outside speaker are in English.

## [1.1.14-beta] - 2026-10-24

- Videos cast from the iPhone (the AirPlay button inside a video app) now ask the car for media audio focus, so their sound plays without starting a radio channel first.
- Outside speaker: cast videos also play on the outside speaker, not only AirPlay music and screen mirroring.

## [1.1.13-beta] - 2026-10-24

- AirPlay sound plays as soon as the iPhone connects, without starting a radio channel first. 7xDash now asks the car for media audio focus while AirPlay audio plays (the car opens its media channel only for the app holding it), and its output counts as media, not game. Starting AirPlay may pause the radio or another media app.
- Outside speaker: AirPlay audio now reaches the outside speaker the same way the megaphone does. The black box notes the outside speaker track and which device the car routed it to.

## [1.1.12-beta] - 2026-10-24

- Outside speaker now plays what you AirPlay to 7xDash, at its own volume, as well as on the cabin speakers. No more Start now screen capture prompt. The 1.1.10 black box showed copying the head unit's own sound cannot work on this car: CarPlay and music apps refuse capture, and even apps that allow it come back silent. While the megaphone talks, the music outside drops to 15% as before.
- Video links the phone hands over (played in 7xDash's own player) are not sent outside yet.

## [1.1.11-beta] - 2026-10-23

- Quit button moved from the side rail to the floating buttons, right of the dim moon. The first tap turns it red and asks to tap again; a second tap within 3 seconds stops recording and closes 7xDash.
- Quit app can now be picked as a shortcut for steering wheel keys, controllers and stickers.

## [1.1.10-beta] - 2026-10-23

- Quit button in the side rail, right under Dim. It asks before quitting, because quitting stops recording. New "Quit app" shortcut action quits straight away.
- Outside speaker: after 6 seconds of pure silence from the head unit, the black box lists every app playing and whether it allows its sound to be captured, and the screen says when the playing app (CarPlay and some music apps) blocks capture.
- Black box log lines for start-up, switches, exits and quitting are now in English.

## [1.1.9-beta] - 2026-10-23

- Cabin passenger (and its shortcut) shows the rear cabin camera, Cabin 2, again. 1.1.8 opened the Cabin 1 slot, which on the car is the driver face camera. With Cabin 2 off in the camera profile, the rear cabin camera is built for the cabin passenger window only, as 1.1.8 did for Cabin 1.

## [1.1.8-beta] - 2026-10-23

- Cabin passenger works with Cabin 1 off in the camera profile. The front cabin camera is built for the cabin passenger window only: it is not recorded, not in photos, and the main screen layout stays as the profile says. Turning cabin passenger on for the first time restarts the camera pipeline once (a recording in progress ends its segment and carries on).
- Cabin passenger no longer stays blank when there is no front cabin camera at all: it says why (not in the head unit's camera list) and turns itself off.

## [1.1.7-beta] - 2026-10-22

- Cabin passenger: still opens blank from a shortcut on some cars. This build writes a trace of every open to `Download/EVCam_Log/cabin-yyyyMMdd.log` (on the USB stick, else internal storage): the shortcut, the window, the camera connection and its state, frames received and drawn, and the automatic retries. Send it after a blank open.

## [1.1.6-beta] - 2026-10-22

- New beta Outside speaker: what the head unit plays (music, radio, AirPlay) also plays on the outside speaker, the one the megaphone uses. Home card (with Beta features on) and a new Outside speaker on/off shortcut; hold the card for its section in Settings > Megaphone, with its own volume. Android only lets an app hear other apps through a screen capture grant, so every start asks on the screen: tap Start now. Apps that block capture stay silent outside. While the megaphone talks, the music drops to 15%. The megaphone's voice is no longer captured, so it is not played twice.
- 7xDash web: an Outside speaker card turns it on or off and sets its volume, and sets the megaphone volume. Turning it on from the web still needs Start now on the car screen.

## [1.1.5-beta] - 2026-10-22

- Home fits the screen: the widget column takes 30% of the width (240-360dp), and the feature cards take 2 to 4 columns depending on the space, with rows of equal height. When there are more rows than fit, the cards scroll.
- New Settings > Interface > Beta features switch, off by default. While it is off, beta features are hidden from the home page and their automatic triggers do not run. Beta now: Where I parked (home card, and Auto on P). The Where did I park shortcut still works.

## [1.1.4-beta] - 2026-10-22

- Cabin passenger: fixed the window sometimes opening blank. Closing it used to throw away its picture while the cabin camera was still sending to it, which stopped the whole cabin stream (window, main-screen cabin pane and cabin recording) until the camera rebuilt it. The window now hands its picture back, like Super mirror, and takes it back when opened again. It also re-attaches when no new frame arrives for 4 s, and after the camera pipeline restarts.

## [1.1.3-beta] - 2026-10-22

- Home: the feature cards are more compact so all four rows fit. Smaller icons, one-line descriptions, and smaller Flash-to-pass and Auto on P pills.

## [1.1.2-beta] - 2026-10-22

- Where I parked: when the car shifts into P, 7xDash reads the floor and bay signs (B2, LEVEL 4, 5F, A-12, ZONE C 045…) from the front and side cameras on the car, offline. The result pops up in the notification shade with a photo, and goes to 7xDash web over the Car Link (it waits and resends when the car park has no signal). Reads Latin letters and digits only, not Thai. Needs the main screen running.
- Home: new Where I parked card. Tap to read the signs now; the Auto on P pill turns the automatic read on or off (on by default).
- Shortcuts: Where did I park.

## [1.1.1] - 2026-10-22

- Shortcuts: Toggle Google Drive upload. Shows an error when Drive is not connected and leaves the switch as it is.
- Shortcuts: Take photo (every camera), AirPlay on/off, and EQ on/off. Photo stays on the current page. EQ stays off if the head unit refuses the equalizer.

## [1.1.0] - 2026-10-18

- Settings reorganised into tabs. New tabs for Shortcuts, Smart sticker (with BLE signals), Controller, Megaphone and Side mirror, at the top of the list; Dim overlay keeps its own tab. Megaphone shows its button, volume and speaker settings right in the tab.
- Side mirror (turn-signal side view) moved out of Super mirror into its own tab, with new defaults tuned on the car: view width 86°, aim back 52°, aim up 17°, rotate left and right 37°, stays open 500 ms after the signal, and fills the whole HUD (width and height 100%). HUD width now goes up to 100% (was 40%); with both side mirrors on the HUD each picture stays under half the width. Settings you already changed are kept.
- Home: long-press Blind spot opens the Side mirror tab; long-press Megaphone opens the Megaphone tab.

## [1.0.15-beta] - 2026-10-18

- AirPlay casting starts much sooner. Before playing, the receiver fetched every YouTube track list through the iPhone, about 19 of them at ~0.6 s each. It now keeps only what the car plays (H.264 at 360p-1080p and the better audio in every language), about 6 lists. The player also starts after 1 s of video instead of 2.5 s.

## [1.0.14-beta] - 2026-10-18

- AirPlay casting downloads over the car's Wi-Fi when it has one with internet, instead of the car's own mobile data (the 7X sends everything over its in-car network by default, even on a home Wi-Fi). Falls back to the default network otherwise; the log says which route each cast used.
- AirPlay log: "iPhone at" now finds the car's Wi-Fi (inside the virtual-app host Android only shows 7xDash the default network, so it said "not on any of the car's networks").

## [1.0.13-beta] - 2026-10-18

- AirPlay casting follows the audio track picked on the iPhone. The receiver used to keep only the language matching the car's setting and drop the rest (a Thai video played its English auto-dub); now every language YouTube offers reaches the player, YouTube's own default plays first, and the player switches when you pick another track.
- AirPlay log: network lines ("net:"). Which network the car uses for internet (its own mobile data, a Wi-Fi router, or a phone hotspot, with Wi-Fi name and router), every time that changes; where each connecting iPhone is; and how much data each cast or mirroring session received and over what.

## [1.0.12-beta] - 2026-10-18

- AirPlay: "Video casting" switch on the AirPlay page (on by default). Off keeps the iPhone on screen mirroring, so YouTube no longer switches to casting and the car uses no internet for it.
- AirPlay page: the status line follows the app language (it could show English on a Thai screen).

## [1.0.11-beta] - 2026-10-18

- AirPlay log: long messages are no longer cut (up to 16 KB per line), so a cast's full track list and the iPhone's track choice are recorded.

## [1.0.10-beta] - 2026-10-18

- HUD notice: new Right position setting (default 20% in from the right), next to Left position, with a test card; the card runs between the two, so long notices no longer go past the right edge of the visible HUD.

## [1.0.9-beta] - 2026-10-18

- AirPlay video casting: tap the AirPlay button in a video's player on the iPhone and choose 7xDash. Only the video plays, in the floating window (ExoPlayer, HLS); play, pause and seeking follow the iPhone's controls. Copy-protected apps still won't play.
- AirPlay: new switch on the AirPlay page, "Show the picture while driving" (off by default, for passengers, with a warning). When on, the picture no longer hides in gear or while moving.
- HUD notice: the card runs full width, from its left position to the right edge. Long text wraps onto more lines (the card grows taller) instead of being cut. The card is slim: only as tall as its text, with little padding.

## [1.0.8-beta] - 2026-10-18

- AirPlay: fixed "see 7xDash but can't connect". The car's system mDNS told the iPhone an internal address (198.18.34.15). 7xDash now runs its own mDNS responder that answers each network with its own address (Wi-Fi 192.168.5.x), and stays off the car's internal links. Falls back to the system one if the mDNS port can't be shared.
- HUD notice: no "7xDash" line on top of the card any more; it took too much room.

## [1.0.7-beta] - 2026-10-18

- HUD notice: new setting (Settings > Rear view > HUD notifications, or long-press the home HUD card) to move the card right until it is fully visible; a test card shows on the HUD each time you change it. Default 20% from the left.
- AirPlay page shows the car's receiver address and port, with a Safari test for when the iPhone can't connect.
- AirPlay log: also records the address the car announces to the iPhone and every address it can be reached at.

## [1.0.6-beta] - 2026-10-18

- HUD notice: the card now sits left-aligned at the top-left of the HUD (8 px in), kept inside the screen; it no longer draws partly outside the visible HUD area.
- AirPlay: the receiver again offers the audio feature an iPhone expects from a mirroring receiver; without it the iPhone could see "7xDash" but not connect.
- AirPlay: fixed "AirPlay could not start" after turning it off and on quickly (the old receiver still held port 7000). Starts and stops now run one after another, and a busy port is retried, then spare ports 7001–7003 are used.
- AirPlay log on the USB drive: EVCam_Log/airplay-yyyyMMdd.log. It records the networks, each iPhone connection and request, the video and audio steps, and how the last run ended.

## [1.0.5-beta] - 2026-10-17

- AirPlay screen mirroring: the head unit shows up as "7xDash" in the iPhone's Screen Mirroring list. The iPhone screen plays in a floating window: drag to move, drag the corner to resize, double-tap for small or large, × to stop. Sound plays through the car speakers.
- AirPlay has its own page and a cell on the side rail. It stays on after a restart if you leave it on.
- The picture hides while the car is in gear or moving; sound keeps playing.
- Built on UxPlay through jqssun/android-airplay-server (both GPL-3.0). Music-only AirPlay and video URL casting are not included. Copy-protected apps show a black screen.

## [1.0.4-beta] - 2026-10-17

- Fixed "The head unit refused the equalizer." The EQ now falls back to a setup the head unit accepts, keeping the smooth curve, headroom and limiter when it can.
- HUD notices show at the top left instead of the centre.

## [1.0.3-beta] - 2026-10-17

- EQ is on the side rail.
- Built-in EQ presets: Flat, Rock, Jazz, Hip-hop, Pop and K-Pop.
- Five presets of your own (My 1 to My 5). Moving a fader on a built-in preset saves the curve to your last My preset.
- Cleaner EQ sound: smoother curve between the 15 faders, automatic headroom so boosts no longer clip, a limiter as a safety net, and lower processing delay.

## [1.0.2-beta] - 2026-10-17

- New app icon: a dashcam lens inside an orange gauge.
- One notice style everywhere: every message shows as the same 7xDash card, and also on the HUD when HUD notifications are on.
- HUD notifications show 7xDash's own messages (recording, captures, photos, dim, mirror, megaphone). No permission needed.
- No more mixed languages: services, notices and pop-ups follow the app language, and the Language setting has a single-language title.
- Instant capture and flash-to-pass are one feature: tap the card, press a shortcut, or flash the high beam (switch on the card). Saved captures has its own card.
- The home Photo card takes a photo in place instead of opening the camera page.
- New shortcut actions: Take photo (surround) and Take photo (cabin).
- About: thanks to dts88, maintainer of zeekr-shortcut-car.

## [1.0.1-beta] - 2026-10-17

- Google Drive sign-in is built in: the release now carries the OAuth client, so there is no client ID or secret to paste on the car.

## [1.0.0-beta] - 2026-10-17

First 7xDash build for the Zeekr head unit, based on zeekr-shortcut-car 2.11.47-beta.

- Home screen in the Zeekr OS 7 style: clock and drive-recorder widgets with a live camera picture, and one card per feature.
- Fixed control bar on every page: Home, Record, Dim, Rear camera, Instant capture, Megaphone, Settings. Can sit on the left or the right.
- Drive recorder for surround and cabin cameras, with the driving info bar.
- Photo, instant capture and flash-to-pass capture.
- Rear camera mirror and turn-signal blind-spot view, on the screen or the HUD.
- Other apps' notifications on the HUD.
- Smart sticker (ZEEKR_button) and shortcuts.
- Megaphone through the outside speaker.
- Remote view and Google Drive upload.
- Settings with a Basic / All switch. Basic shows the everyday options only.
- Vertical equalizer.
- English and Thai.
