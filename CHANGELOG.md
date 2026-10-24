# Changelog

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
