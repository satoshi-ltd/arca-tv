# arca TV: notes for agents

A home screen for Google TV and Android TV, plus the arca Photos and arca Music apps that read from an Arca hub. A plain
Android app in Java, built without Gradle by `build.sh`. See `README.md` for what it does; this file is how to work on it.

Google TV and Android TV only. There is no LG or Samsung build; don't reintroduce one.

Personal rules (Spanish in chat, no code comments, no commits unless asked, no AI attribution) come from the
maintainer's global instructions and apply here.

## Commands

| Task | Command |
| --- | --- |
| Build | `./build.sh` must end with `Built ArcaTV.apk` |
| Install | `adb connect <TV_IP>:5555`, then `adb install -r ArcaTV.apk` |
| Look at the TV | `adb exec-out screencap -p > shot.png` and read the image |
| Log | `adb logcat -s arcaTV` |
| Thumbnails | `python3 thumbnails/tools/validate.py`, `python3 thumbnails/tools/render.py`, `python3 -m pytest thumbnails/tests` |
| Website | `cd site && npm test && npm run build`, preview with `python3 -m http.server --directory site/dist 4179` |

Environment: JDK 17 (`JAVA_HOME`), Android SDK with build-tools 34 and platform android-34 (`ANDROID_HOME`), `adb`,
`rsvg-convert` for thumbnails, and internet access for the first build (it downloads `libs.txt`). Never run two
builds at the same time: `build.sh` deletes `out/`.

There is no Java test harness. A functional change is checked on a real TV (screenshots through `adb`), and a change to
`thumbnails/` ships with a test in `thumbnails/tests`.

## Installing on a TV (for an AI assistant)

When a user asks to install arca TV on their TV (README "With an AI assistant"), follow this. Ask only for what the
request is missing, and ask before installing anything on their computer.

1. **Details.** Take the TV's IP address and the city from the request. Optional: the arca domo
   address. Keys and tokens (`domo.token`) are never typed into the chat: leave the value empty in
   `config.yaml` and tell the user to paste the value there themselves.
2. **Tools.** Look for what is already there before installing: a JDK 17+ (`java -version`, `/usr/libexec/java_home`,
   or Android Studio's `"/Applications/Android Studio.app/Contents/jbr/Contents/Home"` as `JAVA_HOME`), the Android SDK
   (`ANDROID_HOME`, `~/Library/Android/sdk`, Homebrew's `android-commandlinetools`) with build-tools 34 and
   platform android-34, and `adb`. Missing pieces on a Mac: `brew install --cask temurin android-commandlinetools
   android-platform-tools`, then `sdkmanager "build-tools;34.0.0" "platforms;android-34"`. Ask before each install.
3. **Code.** `git clone https://github.com/satoshi-ltd/arca-tv && cd arca-tv`. If the clone is refused, the repository
   is private: ask the user to sign in (`gh auth login`) or to share access.
4. **Config.** Run `./build.sh` once: it copies `config.example.yaml` to `config.yaml`, creates the
   signing key `arca-tv.keystore` and builds with empty settings. Write `city` and any addresses the user gave
   into `config.yaml`; never print, echo or log a token, and never commit `config.yaml` or the keystore.
   Run `./build.sh` again; it must end with `Built ArcaTV.apk`.
5. **Connect.** `adb connect <TV_IP>:5555`. Tell the user to accept *Allow USB debugging?* on the TV with *Always
   allow*, then check `adb devices` shows the TV as `device`. Address every later command to it with
   `adb -s <TV_IP>:5555` so nothing reaches another device.
6. **Install.** `adb -s <TV_IP>:5555 install -r ArcaTV.apk`. If `adb -s <TV_IP>:5555 shell getprop ro.product.brand`
   says TCL, also run `adb -s <TV_IP>:5555 shell "appops set com.satoshilimited.tv AUTO_START allow || appops set
   com.satoshilimited.tv APP_AUTO_START allow"`.
7. **Hand over.** Tell the user the on-TV steps: open **arca TV → Home screen → arca TV**, accept the debugging
   prompt with *Always allow*, and follow the welcome. *Original* in the same place brings the factory home back.
   Remind them to keep the `arca-tv` folder: updates need the same `arca-tv.keystore`.

Troubleshooting:

- `unauthorized`: the prompt on the TV was missed or refused. `adb disconnect`, `adb connect` again and accept it.
- `offline`, timeouts or `Connection refused`: the TV and the computer must be on the same network, USB debugging
  must still be on (some TVs turn it off after an update), and the IP may have changed (check Settings → Network &
  Internet).
- The install hangs or the TV shows an app-verification or Play Protect prompt: accept it on the TV. A prompt that
  calls the app unsafe or outdated can be dismissed; the app targets an older Android on purpose.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / signatures do not match: the TV has a copy signed with another key. Use the
  original `arca-tv.keystore` if it exists; otherwise uninstall the old copy (`adb -s <TV_IP>:5555 uninstall
  com.satoshilimited.tv`) only after the user agrees, since that clears its settings and hub pairing.
- `Android SDK not found` / `JDK not found` from `build.sh`: set `ANDROID_HOME` / `JAVA_HOME` as in step 2.

## Environments and secrets

- `config.yaml` (git-ignored) holds `city`, `domo.url` and `domo.token`; `build.sh`
  reads it with a small awk parser (one level of sections, `key: "value"`, `#` comments) and moves an older
  `config.properties` into it. Never print, log, commit or paste the values. The domo key is
  compiled into the APK. `config.example.yaml` lists the keys.
- `*.keystore`, `*.idsig`, `*.apk` are git-ignored. Keep the keystore: updates must be signed with the same key.
- The hub pairing token lives in the app's private `arca-hub` preferences. Never copy it out.
- The Jellyfin sign-in (Quick Connect token, user and server address) lives in the app's private `settings` preferences, never in the APK
  or `config.yaml`. Never print it.
- No personal data in the repository: no real IP addresses, hub or TV names, library contents, photos or covers.
  Use placeholders such as `<TV_IP>` in docs.
- The TV is real hardware. Do not change its system settings (picture, sound, network, screen timeout) except through
  the app, and restore anything you change while testing.
- The website deploys from `.github/workflows/publish-site.yml` on every push to `main`, or by manual
  dispatch. It needs the secrets `CLOUDFLARE_ACCOUNT_ID` and `CLOUDFLARE_API_TOKEN` (Pages
  Edit) and the variables `CLOUDFLARE_PAGES_PROJECT_NAME` and `SITE_URL`; a missing one stops the run.

## The code

- Java 8 language level, minSdk 28, targetSdk 33. Fonts are `Geist` from assets.
- The only library is AndroidX Media3 ExoPlayer 1.5.1, the player for all video (today `HubVideo`, the photo viewer's).
  `libs.txt` pins it and its dependencies (Guava, AndroidX collection, exifinterface) by URL and SHA-256; `build.sh`
  downloads them into the git-ignored `libs/` on the first build, refuses any file whose hash differs, and shrinks the
  app and the libraries with R8 (`app/r8.pro`, the rules aapt2 writes for the manifest, each AAR's own `proguard.txt`;
  no obfuscation, and `out/mapping.txt` retraces inlined frames). Create the player only when a video plays, never on
  the home screen's start, and release it in `onStop` so another app gets the decoder and the memory back. Add nothing
  else without asking.
- The app icon and the TV banner (`arca_icon.png`, `arca_banner.png` in `res/drawable-nodpi`) are muted grey (tile #444444,
  arch #f2f2f2, door #bfbfbf), never green. Launchers keep icons by package and resource name: a new look needs new names.
- With the arca screensaver on it is the only one: `HomeMode.ensureSaver` makes it the selected and the fallback dream and sets
  `screensaver_enabled` from the Screensaver row, and the dream services of system apps (`systemDreams`) are turned off, never those
  of third-party apps. *Original* restores the recorded values.
- Jellyfin (Continue watching): `Settings → Continue watching` opens `JellyfinActivity`. `JellyfinLink` finds the server the way the
  hub is found, with `LanScan` (254 addresses, `GET /System/Info/Public` on 8096; Jellyfin's own UDP broadcast does not reach a server
  in Docker): first the address used last time, then the hub's address, then the scan, and "Enter address" if nothing answers. Then
  Quick Connect: the TV shows the 6-digit code, polls every 3 s and signs in with `AuthenticateWithQuickConnect`. Not built yet: the
  onboarding step and signing in with a password.
- Three processes: the UI runs in `:home`; `HomeMode` and `ModeJob` switch the home screen from the main process;
  `HomeKeyService` runs in `:keys`, with no Watchdog, so a crash there never counts toward the revert.
  `Watchdog` returns to the stock home screen after three crashes in five minutes. Keep the way back working.
- All screens are `View`s drawn by hand on one `Canvas` at 60 fps: no allocations in `onDraw`, no work on the UI thread,
  and always reset `fill` to an opaque colour before drawing a shader background (a leftover alpha makes it grey).
- Shared pieces, use them instead of copying:
  - `Icons`: the only icon source. Every UI icon is a `res/drawable/icon_*.xml` vector of one family (24 grid,
    stroke 1.75, round caps and joins, filled only for play), drawn with `Icons.draw` (muted by default, ink when
    focused); `Icons.res` maps a name to its vector. No PNG icons. The arca mark is the brand and is never redrawn.
  - `Glass`: the only container style (`plate`) and the focus style (`focus`, plus the ring alone via `ring`/`ringOval`
    for tiles and covers that draw their own fill).
  - `Backdrop`: every blurred or colour background, with drift and a crossfade. Input is an image or a brand colour.
  - `Header`: the secondary-screen header (`drawScreen`) and the lockup (`drawLockup`, `drawLockupAt`).
  - `IdleSaver`: wraps an activity's content and starts the screensaver after one minute without a key.
  - `PhotoSlideshow` with a `PhotoSource` (`PhotoStream` random across the whole library, `LibrarySource` sequential
    from the explorer): the slideshow, the screensaver and the viewer, including video items (hash and video flag
    travel with each `PhotoStream.Photo`). `PhotoMotion` is the one drift/zoom used by all three.
- Arca hub client: `ArcaHub` (discovery, pairing, `get`), `PhotoLibrary` (timeline, pages, thumbnail cache),
  `MusicLibrary` (tracks, albums, artists, playlists, recent albums).
- arca domo: `ArcaDomo` (home-server client, polling, actions, the Home summary), `DomoActivity` (rooms, purifier
  and vacuum screens, every state), `Icons` (the stroke icons in `app/res/drawable/house_*.xml`, tinted and cached).

## Arca hub protocol (as used by the TV)

- Discovery: scan the local /24 for `GET /.well-known/arca` (`service=arca`, `role=hub`) on port 17831, plain HTTP on
  the LAN. Pairing: `POST /pair {code, name, kind: "player"}` returns `{id, token, hubId}`; the code is single use and
  expires. The hub lists the TV as a Player, and a player credential is read-only: it reaches the catalog, gallery,
  previews, music library, covers and blobs, plus its own machine report; every other route answers 403. A new hub
  route the TV needs must join the hub's player allow-list (`PLAYER_READS` in Arca's `packages/daemon/machines.js`).
- Auth: `Authorization: Bearer <token>`. `POST /v1/machine-report` (`reportName`) sends the TV name, `app: "arca TV"`,
  the app version (`x.y` padded to `x.y.0`) and `folderIds`, the photo and music folders it plays (only once paired as a
  player); it is sent after pairing, when the name changes, and whenever the catalog is read and the name, version or
  folders differ from the last report the hub accepted. A TV paired before players existed must pair again.
- Catalog: `GET /v1/catalog` lists volumes; `gallery` and `music` booleans mark them.
- Photos: `GET /v1/gallery?volume=ID` returns the first page (60, newest first), `timeline` (per month `count`, `videos`)
  and `next`. `&month=YYYY-MM` seeks a month, `&after=<cursor>` continues. Previews:
  `GET /v1/gallery/preview?volume&path&hash&size=thumb|medium|large` returns `{"data":"data:image/jpeg;base64,..."}`.
  Photos and videos share one chronology; there is no location. Videos play inline in the viewer, streamed from
  `GET /v1/blobs/<hash>` (the same content-addressed, Bearer-and-Range route music uses) through `HubVideo` (ExoPlayer,
  a read-ahead buffer capped at 32 MB because it lives in the launcher's heap, the surface sized to the video's own
  aspect ratio so portrait clips are letterboxed).
- Music: `GET /v1/music/library?volume=ID` returns tracks (tags, `cover` key) and `playlists` (`.m3u8`, `entries` are
  folder-relative paths). Covers: `GET /v1/music/cover?volume&key&size=small|large` returns `{data}`. Audio streams
  from `GET /v1/blobs/<hash>` with a Bearer header and Range.
- Recent albums are kept on the TV (`arca-music` preferences, last 12), not on the hub.

## arca domo protocol (as used by the TV)

- Base URL and key come only from `config.yaml` (`domo.url`, `domo.token`), compiled into `Config`. Plain
  HTTP on the LAN, `Authorization: Bearer <token>`; 401 means the key was rejected.
- `GET /api/devices` (5 s timeout) returns every device with `id`, `type` (`purifier`, `vacuum`), `state` (the last
  good one, `null` if never read), `error` and `age` (seconds since `state` was read). Polled every 10 s while Home or
  Domo is visible, never in the background.
- `POST /api/devices/{id}/actions/{action}` (15 s timeout): `power {on}`, `mode {mode: auto|sleep|favorite}`,
  `fan {level: 0..14}`, `start`, `stop`, `dock`. It answers `{id, action, ok, state, error, age}`; the TV takes that
  state and does not poll again for it. 502 means the device did not answer.
  For a purifier the TV compares what it asked for (`power`, `mode`, `fan_level`) with that state: a mismatch means the
  purifier ignored it (`ArcaDomo.IGNORED`, "<name> didn't take the change."). Mode and fan are off while it is off.
- Actions move real devices in the house. Never call them while testing without the maintainer.

## Product rules (settled; do not reopen without the maintainer)

- Dark neutral backgrounds always; colour only from the focused item. Geist only. Header lockup with a white tag
  (`TV`, `PHOTOS`, `MUSIC`) and the level as the title; no city name.
- Glass everywhere: fill white 10 %, border 1.5 px white 14 %, top highlight; focus = fill white 14 % plus a double ring
  (a translucent black 35 % gap, then white). The gap is translucent, not solid, so it never reads as a hard line over
  a photo or brand backdrop. Pills: inactive glass, active solid white.
- Settings order: Home screen, Hub, Continue watching, Screensaver, Weather, Shortcuts, Colour buttons (only
  when a remote reports the four colour keys), Version. Each row has its
  family icon and a value that says what you get (Screensaver Photos/Colours/Off, Weather the city or Off, Shortcuts
  "4 of 5"), inside the same glass plate as Quick settings. The
  TV has no name setting: the hub gets Android's device name (`ArcaHub.deviceName`), or "arca TV" when there is none.
- Quick settings: "This TV" heading over Picture, Sound, Network, All settings, About, then arca TV; inputs on the
  right. One name per thing, never "settings" twice on the same panel.
- Shelf: an HDMI input with a signal takes the last app slots (before All apps, so the apps never shift) and, when it
  newly connects, the focus; Down from the cards also lands on it. When the signal goes, the app comes back. A console on HDMI (`devices` in `thumbnails.json`, matched by
  words in the input's name) is drawn like an app tile, with its brand tint and logo; other devices keep icon and name.
- Music has three tabs: Recent (albums), Artists (split view: artist list and the albums of the focused artist),
  Playlists (cover grid). "Shuffle all" (whole library, shuffled) is a pill at the end of the tab row; the
  first tile of an artist's album grid is "Shuffle" (that artist's tracks, shuffled). Artist to album to tracks; no
  separate artist page. Now playing has no queue.
- Photos · On this day: when earlier years have photos for today, the explorer opens on an "ON THIS DAY" row above the
  newest month (up to four 4:3 cards, one tile row high: the year's first photo of the day, "October 22, 2023",
  "3 years ago · 3 photos"), from `GET /v1/gallery/memories?volume&day=MM-DD&year=YYYY`, re-read every time Photos
  opens. On the row the title reads "Photos" and the backdrop is the focused card; Left/Right move along it, Down enters
  the grid, Up from the first grid row returns to it, OK seeks the card's photo in its month (a third of the way down,
  briefly highlighted), Back from the grid returns to the row and then leaves. No memories, no row. The hub's route is
  tried first; a player-paired TV gets 403 (it is not in arca's `PLAYER_READS`), so `PhotoLibrary` computes the same
  answer itself from routes a player may read: for each earlier year whose month is in the timeline it pages that month
  newest first from `&month=` and stops past today's date, counting today's photos and keeping the earliest as cover
  (the hub's rule). It runs after the explorer is shown, once per day and library signature.
- Photos: one continuous timeline with a time scrubber, hold to accelerate, thumbnails cached in memory and on disk.
  The slideshow, viewer and screensaver share one motion (`PhotoMotion`): every photo covers the screen; its life is
  1.2 s fade-in + 8 s hold + 1.2 s fade-out (10.4 s, a new photo every 9.2 s; the next one is fetched as soon as one
  shows) and the motion runs over exactly that life, linear with a 1 s
  ease at each end: zoom 1.00 to 1.04; the drift follows the axis that has room, sized once from the end overflow so
  its speed is constant: portraits from the upper third of the overflow toward the middle (at most 10 px/s, never
  past it), camera landscapes (4:3, 3:2, cropped top and bottom) vertically around the centre, panoramas sideways,
  both alternating per photo at most 12 px/s. The viewer opened from the explorer runs as a slideshow from that photo
  (OK pauses and freezes the motion, Left/Right step, a video advances when it ends). No on-screen navigation arrows. Videos play inline on open; only a video shows a progress bar, never a photo. The slideshow
  caption is the date only, read from the hub's three shapes (UTC with `Z` or an offset is shown in the TV's local date,
  a date-time without a zone is kept as written, a bare `YYYY-MM` shows the month); no date, no caption. The caption crossfades with its photo: the old date fades
  out rising 12 px while the new one fades in rising into place, over the same 1.2 s. No text
  labels for paused or playing.
- At night (22:00 to 06:00, TV local time) the screensaver dims itself to 40 % brightness. Automatic, no setting, no
  label.
- The screensaver starts after one minute without input on any arca screen; with a hub every photo is drawn
  independently and uniformly from the whole library (a random index over the timeline counts, then the page that
  holds it via cached `after` cursors; videos skipped, no repeat among the last 200), so eras mix; it can be turned
  off in Settings.
- arca domo exists only when `domo.url` is set: otherwise no indoor reading in the weather row, the weather corner is not focusable, no
  Devices screen and nothing in All apps. No setup screen or Settings row for it, and when it exists the weather corner is its
  only way in (it is not in All apps). Its screens and the focused weather
  corner take the backdrop from the worst fresh reading: arca green for Excellent or Good, amber for Light or
  Moderate, red for Heavy or Severe (`ArcaDomo.washColor`); level colour on a card is only an accent on the lit step,
  always next to the word.
  Age shows only on an old reading (amber past 2 min, dimmed *Out of date* past 5); no "Updated" line anywhere.
- The weather corner has two lines, identical on Home and in the screensaver (`Header`): `[weather icon] Drizzle 29°`,
  then `[wind] 8 µg/m³  [house] 12 µg/m³ | ↑31° ↓25°` with max/min under the temperature. Outdoor PM2.5 comes from
  Open-Meteo; the house value is the worst fresh room, only with arca domo. Units, icons and labels muted; numbers ink.
  Weather icons are `icon_clear_*`, `icon_partly_*`, `icon_cloudy`, `icon_fog`, `icon_drizzle`, `icon_rain`, `icon_snow` and `icon_thunder`, one per WMO code group, with night versions for clear and partly
  cloudy. No sunrise or sunset.
- Hub pairing assumes one hub per home: Searching goes straight to the code screen of the hub it finds (no list);
  none found offers Try again or Enter address. The code keypad has no submit key (the sixth digit sends it); Back
  on the code screen leaves.
- Copy is short, natural and non-technical (no ports, no protocol names in the UI).

## Always

- Never turn off an app that saved a Wi-Fi network (`wifiOwners` in `HomeMode`): Android deletes its networks and
  *Original* cannot bring them back.
- Never turn off Google TV's home screen while its setup app is on: the setup app's RecoveryActivity then takes the
  Home button (a restart there factory-reset a Xiaomi). When the setup app (or the stock home) saved the Wi-Fi, arca
  TV mode keeps both on (`keys` in the mode state) and `HomeKeyService`, an accessibility service in its own `:keys`
  process, answers the Home key and sends any appearance of the stock home or the recovery screen back to arca TV.
  The same service takes the four colour keys when one has a target (`RemoteColours`, a small file the service reads
  on each press); every other key passes untouched. `HomeMode.keyServiceWanted` decides when it must be on (arca TV
  holds the home role, asked to the system because the stored mode can lag behind, and keys mode or a colour target;
  it is turned on from the app's own process, since TCL's boot manager can block background jobs); *Original* and the crash revert turn it off and restore
  `accessibility_enabled` as it was.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0` through `HomeMode`.
- Never commit `config.yaml`, keystores or tokens.
- Do not bump versions or tag unless the maintainer asks.

## Open items

- Backdrop blur behind glass is not available on `Canvas`; containers are translucent only.
- No Java tests; no screen-reader support (`Spoken`) for the hub, photos and music screens yet.
