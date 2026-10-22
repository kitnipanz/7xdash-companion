# Changelog

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
