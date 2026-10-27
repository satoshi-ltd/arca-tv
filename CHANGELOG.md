# Changelog

## 1.2.0

- Home shows the focused Watch next title in large type above the row, with its rating, year, runtime and a line of overview.
- Screens move instead of cutting: a photo or a song cover grows into the next screen; a title, album, artist or playlist zooms from the list into its page; Back plays it in reverse. Switching tabs in Movies and Music slides the content in, and changing the sort fades the reordered grid in.
- Movies: posters with art no longer have their title printed on top; the focused poster shows its title and year below. On title pages Resume says how much is left, with a progress line.
- Music: Now playing lists what plays next, adds Shuffle, and takes the album's colour. When Up next hides, the title uses the whole width.
- Posters, covers and photos fade in from their own colour instead of popping in, only when they come from the hub; art already on the TV shows at once.
- Music covers are kept on the TV, so reopening Music does not download them again.
- On a title page, the backdrop follows the focused Related poster.
- The focus ring glides from one item to the next instead of jumping, and bumps against the edges.
- One type scale across the app: the scattered in-between sizes are gone, and the small header tag is larger and easier to read from the sofa.

## 1.1.0

- Holding OK on a Watch next card offers Remove from Watch next on every card, not only the ones you started. The hub keeps it off the row on every device until you play that title again. Disconnect and pairing discard old library, artwork and Watch next results, and a delayed refusal of an old credential cannot disconnect a newly paired hub.
- Artwork storage is trimmed as it grows, and full artwork keys prevent cache filename collisions. Leaving playback cancels pending player callbacks; delayed progress saves retain the video's original folder.

- arca Movies: your hub's movies and shows on the TV. Tabs Home, Movies, TV Shows, Kids and Others, a page for each
  movie and show, and a player with audio and subtitle lists, Up next and saved position. It appears when the hub has a
  video library, and streams the original file without transcoding.
- Watch next on Home comes from arca Movies: the titles you started, then the next episode of each show you follow. A card plays at the saved position (a next episode from the start).
- arca Movies & TV is now arca Movies, and the arca app logos take the C2 colours: the same logo on a dark tile tinted with the app's colour, a white arch and the door lit in that colour. arca TV's icon and banner use the same dark grey and glow.
- The arca TV banner and icon have a smaller logo, so it no longer fills the tile in Google TV's app gallery.
- Website: animated demos of arca Movies, Photos and Music copied from the design, the home demo with arca Movies, Photos and Music on the shelf and Watch next as the hub sends it, sections that lead with the arca library, and share cards, structured data, robots.txt and a sitemap. The connection guide uses the hub’s current library controls; demos take more time, show clean stills when paused, pause out of view and load their lower-page images only as needed.
- Jellyfin is no longer integrated: it is a normal app on the shelf. Settings has no Watch next row, the Quick Connect code and the Jellyfin sign-in are gone and any saved sign-in is erased on the first start.
- arca Movies: the Movies, TV Shows, Kids and Others tabs can be sorted by A–Z, Recently added, Year or Rating (Others: A–Z and Recently added), remembered per tab.
- Movies pages: a Related row of posters (at the end of a movie page, below the episodes on a show page; OK opens that title), audio and subtitle languages as two-letter chips on the badge row (two codes, then +N), the certification as an outlined badge, Mark as watched / unwatched for a whole show, a check on the poster of a watched title, and A–Z that ignores a leading article and accents.
- On a show page the background follows the focused episode: moving along the episodes shows each one's still, and it goes back to the show's backdrop on the buttons and seasons.
- Player: the top shows the title with its rating, certification, year and runtime, and the clock with the time the video would end at.
- Next and New cards carry a NEXT or NEW chip at the top left, as in arca mobile and desktop, and their second line no longer repeats the word (S1 · E4 · title, Added this week).
- Watch next on Home and in Movies is the hub's `watchNext` list, drawn as sent: the hub gives each card its title, second line, chip, artwork and progress, and the order is resume, next, related, new and waiting. Resume and next cards play; suggestions open the title's page. Without `watchNext` there is no row, and the Home picks that are shows are no longer dropped.
- Section headings are one style everywhere: medium 32, sentence case (Home, Movies rows, Related, Photos On this day and month headings with their count).
- Movies Home rows use the Home card (400×225, title and second line over a gradient, progress bar at the bottom edge) and the same section heading as Home, with more space between a heading and its cards; Related too.
- Movies Home has one Watch next row: what you started, then the next episode of each show without a card (labelled Next, no progress bar, plays from the start), and a Kids picks row. Movies leaves out the Kids movies and Recently added leaves out Kids movies.
- Player: a video the hub cannot stream says "This video's format can't be streamed" with only Close. Subtitles inside the file are only requested from the hub when you pick one or it is your remembered language; the list no longer repeats the file's own tracks, hub tracks are not labelled External, forced ones are flagged, and audio names come from the hub.
- Home: the shortcuts plate is shorter (190 px) and Watch next sits just above it. Holding OK on a Watch next card (Home and Movies Home) opens a menu with Details (the title's page) and Remove from Watch next.
- Hub screen lists Movies next to Photos and Music, each with what it holds: photos and videos, songs, movies and shows.
- The arca app thumbnails (Photos, Music, Movies, Domo) show the icon and the name only (so all four stay the same size), in a firmer weight. Each has its own colour: Photos amber, Music violet, Movies rose, domo blue (icon, thumbnail tint and Home backdrop when focused). Their logos are drawn wider (72% of the tile) with a larger name so they read as big as the other apps'.
- Player: rewind and forward arrows; holding Left or Right jumps in growing steps (10 s, then +30, +60, +120 and +240 s every 0.6 s); the dot and the time move, a marker stays where you started, a pill above the dot shows how far you went, and the
  video jumps when you let go. Audio and Subtitles each open their own list, and a pill is drawn only when there is
  something to choose.
- Watched titles show a check on their poster, and a show page is a hero with the show's backdrop, season pills and a row
  of episode cards.

## 1.0.5

- arca domo purifiers: after a change, the screen checks that the purifier took it (power, mode or fan). If it did not,
  it says "<name> didn't take the change." and keeps the real state. Mode and fan are off while the purifier is off,
  as it ignores them then.
- Jellyfin Quick Connect: the screensaver no longer covers the code, Back while searching leaves no stray code behind,
  a brief network error while waiting no longer cancels the code, and a TV name with accents or line breaks no longer
  breaks the sign-in.
- A colour button set to the screensaver now starts it when Home is already open.
- The site deploys on every push to `main`.

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
