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

Logos are drawn inside a box of 58% of the tile's width by 32% of its height, keeping their proportions, so wide wordmarks and square icons weigh about the same.

`brand` is the vivid colour of the brand, never its dark background.

## Trademarks

Logos are trademarks of their owners and are included only to identify their apps.
