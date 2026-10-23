# Changelog

## 1.0.4

- Colour buttons: when the TV has a remote with red, green, yellow and blue keys, Settings gains a Colour buttons row.
  Each button can open arca Photos, arca Music, the screensaver, an installed app or an HDMI input; Default leaves the
  button to whatever app is open. Only the four colour keys are ever taken.
- The TV name is Android's own device name (the one Cast, Bluetooth and HDMI-CEC use), sent to the hub as it is, so the TV name
  setting and its screen are gone; the name falls back to arca TV when the TV has none.
- Jellyfin connects by Quick Connect: Settings → Continue watching finds the server on your network (also in Docker), shows a code to approve
  in Jellyfin and keeps the sign-in on the TV. `jellyfin.url` and `jellyfin.token` are gone from `config.yaml`, so Continue watching needs
  connecting once after this update. Hub discovery and Jellyfin discovery share one network scan.
- With the arca screensaver on, it is the only screensaver: it is also the system's fallback, and the screensavers of the TV's
  system apps are turned off (an app that offers its own, like Jellyfin, is left alone, as turning it off would turn the app off).
  Off in Settings now also turns the system screensaver off, and *Original* restores both.
- arca domo is no longer in All apps: the weather corner opens it.
- Xiaomi's Live TV and Miracast stay out of All apps (Live TV still serves the HDMI inputs).
- The app icon and the TV banner are arca TV's own, in the muted grey of the site (they were still Free My TV's), and the
  icon is a full square so launchers show it larger.
- The remote is read from the keys it reports (a Bluetooth remote lists its own; an infrared receiver that claims every
  key is ignored). The same small service answers the Home button on TVs whose setup app saved the Wi-Fi.

## 1.0.3

- arca TV mode works on TVs whose first setup saved the Wi-Fi (Xiaomi and other Google TVs) without forgetting the
  network: Google TV's home and setup stay on, and a small accessibility service sends the Home button and any
  appearance of the stock home to arca TV. *Original* turns it off again.

## 1.0.2

- A game console on HDMI shows its brand on the shelf: PlayStation, Xbox and Nintendo tiles, matched from the name
  the device reports over HDMI-CEC (or the name you gave the input), and its colour tints the background when focused.
- When a device starts sending a signal on HDMI, its shortcut takes the shelf's last slot and gets the focus; when it
  stops, the app that was there comes back. While it is on, Down from Continue watching lands on it.
- Quick settings: each input shows one name, the device's or "HDMI n", with no "Connected" or "No signal" beside it;
  an input without a signal is dimmed.
- Thumbnails: `thumbnails.json` gains `devices`, matched by words in the input's name instead of packages.

## 1.0.1

- Photo-viewer video plays through AndroidX Media3 ExoPlayer, with a larger read-ahead buffer and portrait clips
  letterboxed.
- Libraries are pinned by SHA-256 in `libs.txt` and the APK is shrunk with R8 (about 1.3 MB).
- The website's share preview shows the home screen.
