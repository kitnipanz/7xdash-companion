# Changelog

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
