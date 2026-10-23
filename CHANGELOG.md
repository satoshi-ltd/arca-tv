# Changelog

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
