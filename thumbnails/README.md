# arca-thumbnails

Thumbnails for arca-tv: one entry per app, shown on the shelf as the app's logo on dark glass with a soft tint of its brand colour. The same colour tints the home background when the tile is focused.

## Add an app

1. Add an entry to `thumbnails.json`:

   ```json
   {"name": "Netflix", "packages": ["com.netflix.ninja"], "brand": "#e50914", "logo": "netflix.svg"}
   ```

2. Optionally drop the logo in `logos/`. It must read on a dark tile: white or bright colours, no background, no scripts, under 60 KB. A one-colour wordmark in its own brand colour vanishes into the tile's brand tint, so make it white; `validate.py` rejects it. Without `logo` the tile shows the name in text.
3. Run `python3 thumbnails/tools/validate.py`, then `python3 thumbnails/tools/preview.py` and open `thumbnails/preview.html`.
4. Run `python3 thumbnails/tools/render.py` (needs `rsvg-convert`) to regenerate `app/assets/thumbnails`: the JSON and one PNG per logo, which is what the app reads. Android cannot draw SVG.

Logos are drawn inside a box of 58% of the tile's width (or `width`, a fraction between 0.3 and 0.9, set per entry) by 32% of its height, keeping their proportions, so wide wordmarks and square icons weigh about the same. The arca app logos (photos, music, media, domo) show the icon and the domain only and share one viewBox width, so their icons come out the same size. They share one logo and only change colour (brand C2): a very dark tile with 16% of the app's colour, a white arch and wordmark, and the door lit in the app's colour with a soft glow; the `brand` in `thumbnails.json` is that colour.

`brand` is the vivid colour of the brand, never its dark background.

## Add an HDMI device

Devices on HDMI (game consoles today) go in `devices` instead of `apps`, matched by lower-case words in the name the
input shows (the name the device reports over HDMI-CEC, or the one given in the TV's settings), first match wins:

```json
{"name": "PlayStation", "match": ["playstation", "ps5", "ps4", "ps3"], "brand": "#0070cc", "logo": "playstation.svg"}
```

A matched input shows that tile on the shelf; any other input keeps its icon and name.

## Trademarks

Logos are trademarks of their owners and are included only to identify their apps.
