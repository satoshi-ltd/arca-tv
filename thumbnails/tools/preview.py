import html
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>arca-thumbnails</title>
<link href="https://fonts.googleapis.com/css2?family=Geist:wght@400;600&display=swap" rel="stylesheet">
<style>
body{margin:0;padding:64px;background:#0e0e0e;color:#f2f2f2;font-family:Geist,system-ui,sans-serif}
h1{font-size:22px;font-weight:600;letter-spacing:2.5px;text-transform:uppercase;color:#ffffffb8;margin:0 0 32px}
.grid{display:flex;flex-wrap:wrap;gap:32px}
.cell{width:250px}
.tile{position:relative;width:250px;height:141px;border-radius:24px;background:linear-gradient(180deg,#262626,#161616);display:flex;align-items:center;justify-content:center;overflow:hidden}
.tint{position:absolute;inset:0;border-radius:24px;box-shadow:inset 0 0 0 1.5px #ffffff22}
.logo{position:relative;display:flex;align-items:center;justify-content:center;width:100%;height:100%}
.logo svg{height:38px;width:auto;max-width:180px}
.name{position:relative;font-size:28px;font-weight:600;text-align:center;line-height:1.15}
.label{margin-top:12px;font-size:18px;color:#ffffffb8}
</style></head><body><h1>Thumbnails</h1><div class="grid">@@CELLS@@</div></body></html>
"""


def namespaced(svg, stem):
    ids = set(re.findall(r"\bid=\"([^\"]+)\"", svg))
    for name in ids:
        svg = svg.replace(f"id=\"{name}\"", f"id=\"{stem}-{name}\"")
        svg = svg.replace(f"url(#{name})", f"url(#{stem}-{name})")
        svg = svg.replace(f"href=\"#{name}\"", f"href=\"#{stem}-{name}\"")
    classes = set()
    for group in re.findall(r"class=\"([^\"]+)\"", svg):
        classes.update(group.split())
    for name in sorted(classes, key=len, reverse=True):
        svg = re.sub(rf"(?<![\w-])\.{re.escape(name)}(?![\w-])", f".{stem}-{name}", svg)
        svg = re.sub(rf"(class=\"[^\"]*?)(?<![\w-]){re.escape(name)}(?![\w-])", rf"\g<1>{stem}-{name}", svg)
    return svg


def inline(logo):
    svg = namespaced((ROOT / "logos" / logo).read_text(), Path(logo).stem)
    root = re.search(r"<svg[^>]*>", svg).group(0)
    bare = re.sub(r"\s(width|height)=\"[^\"]*\"", "", root)
    return svg.replace(root, bare, 1)


def cell(app):
    brand = app["brand"]
    tint = f"background:radial-gradient(120% 90% at 50% 130%,{brand}66 0%,#0000 70%)"
    body = f"<div class=\"logo\">{inline(app['logo'])}</div>" if app.get("logo") else f"<div class=\"name\">{html.escape(app['name'])}</div>"
    return f"<div class=\"cell\"><div class=\"tile\"><div class=\"tint\" style=\"{tint}\"></div>{body}</div><div class=\"label\">{html.escape(app['name'])} · {brand}</div></div>"


def build():
    data = json.loads((ROOT / "thumbnails.json").read_text())
    return PAGE.replace("@@CELLS@@", "".join(cell(a) for a in data["apps"]))


if __name__ == "__main__":
    (ROOT / "preview.html").write_text(build())
