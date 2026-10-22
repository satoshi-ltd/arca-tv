import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT.parent / "app" / "assets" / "thumbnails"
MAX_W, MAX_H = 720, 240


def size_of(svg):
    box = re.search(r'viewBox="([^"]+)"', svg).group(1).split()
    ratio = float(box[2]) / float(box[3])
    width = min(MAX_W, round(MAX_H * ratio))
    return width, round(width / ratio)


def render(logo):
    svg = (ROOT / "logos" / logo).read_text()
    width, height = size_of(svg)
    target = OUT / (Path(logo).stem + ".png")
    subprocess.run(["rsvg-convert", "-f", "png", "-w", str(width), "-h", str(height), "-o", str(target), str(ROOT / "logos" / logo)], check=True)


def main():
    if shutil.which("rsvg-convert") is None:
        sys.exit("rsvg-convert not found")
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    data = json.loads((ROOT / "thumbnails.json").read_text())
    shutil.copy(ROOT / "thumbnails.json", OUT / "thumbnails.json")
    for app in data["apps"]:
        if app.get("logo"):
            render(app["logo"])


if __name__ == "__main__":
    main()
