import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HEX = re.compile(r"^#[0-9a-fA-F]{6}$")
PACKAGE = re.compile(r"^[a-zA-Z][\w]*(\.[a-zA-Z][\w]*)+$")
FORBIDDEN = re.compile(r"<script|<foreignObject|<image|onload=|href=\"http", re.IGNORECASE)
FILL = re.compile(r'fill="(#[0-9a-fA-F]{6})"')


def close(a, b, limit=90):
    return sum((int(a[i:i + 2], 16) - int(b[i:i + 2], 16)) ** 2 for i in (1, 3, 5)) ** 0.5 < limit


def light(color):
    return min(int(color[i:i + 2], 16) for i in (1, 3, 5)) >= 0xE0


def blends_into_tint(svg, brand):
    fills = {fill.lower() for fill in FILL.findall(svg)}
    if len(fills) != 1 or not HEX.match(brand):
        return False
    fill = fills.pop()
    return not light(fill) and close(fill, brand.lower())


def errors(root=ROOT):
    found = []
    data = json.loads((root / "thumbnails.json").read_text())
    seen = set()
    for app in data["apps"]:
        name = app.get("name") or "?"
        if not app.get("name"):
            found.append("entry without a name")
        if not HEX.match(app.get("brand", "")):
            found.append(f"{name}: brand must be #rrggbb")
        packages = app.get("packages") or []
        if not packages:
            found.append(f"{name}: no packages")
        for package in packages:
            if not PACKAGE.match(package):
                found.append(f"{name}: bad package {package}")
            if package in seen:
                found.append(f"{name}: duplicate package {package}")
            seen.add(package)
        logo = app.get("logo")
        if logo:
            path = root / "logos" / logo
            if not path.is_file():
                found.append(f"{name}: missing logos/{logo}")
            elif FORBIDDEN.search(path.read_text()):
                found.append(f"{name}: logos/{logo} contains forbidden content")
            elif path.stat().st_size > 60_000:
                found.append(f"{name}: logos/{logo} is larger than 60 KB")
            elif blends_into_tint(path.read_text(), app.get("brand", "")):
                found.append(f"{name}: logos/{logo} is one colour close to its brand tint; make it white")
    return found


if __name__ == "__main__":
    problems = errors()
    for problem in problems:
        print(problem)
    sys.exit(1 if problems else 0)
