import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))
import validate


def copy(tmp_path):
    shutil.copytree(validate.ROOT / "logos", tmp_path / "logos")
    shutil.copy(validate.ROOT / "thumbnails.json", tmp_path / "thumbnails.json")
    return tmp_path


def test_repository_is_valid():
    assert validate.errors() == []


def test_rejects_bad_brand_and_missing_logo(tmp_path):
    root = copy(tmp_path)
    data = json.loads((root / "thumbnails.json").read_text())
    data["apps"].append({"name": "X", "packages": ["com.example.x"], "brand": "red", "logo": "nope.svg"})
    (root / "thumbnails.json").write_text(json.dumps(data))
    found = validate.errors(root)
    assert any("brand" in e for e in found) and any("missing" in e for e in found)


def test_rejects_scripts_and_duplicate_packages(tmp_path):
    root = copy(tmp_path)
    (root / "logos" / "evil.svg").write_text("<svg><script>alert(1)</script></svg>")
    data = json.loads((root / "thumbnails.json").read_text())
    data["apps"].append({"name": "Evil", "packages": ["com.netflix.ninja"], "brand": "#000000", "logo": "evil.svg"})
    (root / "thumbnails.json").write_text(json.dumps(data))
    found = validate.errors(root)
    assert any("forbidden" in e for e in found) and any("duplicate" in e for e in found)


def test_inlined_logos_do_not_share_class_or_id_names():
    sys.path.insert(0, str(validate.ROOT / "tools"))
    import preview
    page = preview.build()
    assert 'class="cls-1"' not in page
    assert len(set(__import__("re").findall(r'id="([^"]+)"', page))) == len(__import__("re").findall(r'id="([^"]+)"', page))


def test_render_size_keeps_proportions_and_caps_the_box():
    import render
    wide = '<svg viewBox="0 0 200 33"></svg>'
    square = '<svg viewBox="0 0 100 100"></svg>'
    assert render.size_of(wide) == (720, 119)
    assert render.size_of(square) == (240, 240)


def test_rejects_a_one_colour_logo_in_its_brand_colour(tmp_path):
    root = copy(tmp_path)
    netflix = root / "logos" / "netflix.svg"
    netflix.write_text(netflix.read_text().replace('fill="#ffffff"', 'fill="#d81f26"'))
    assert validate.errors(root) == ["Netflix: logos/netflix.svg is one colour close to its brand tint; make it white"]
    assert not validate.blends_into_tint('<path fill="#ff0033"/><path fill="#ffffff"/>', "#ff0033")
    assert not validate.blends_into_tint('<path fill="#fafafa"/>', "#f8f8f5")


def test_rejects_a_device_without_match_words(tmp_path):
    root = copy(tmp_path)
    data = json.loads((root / "thumbnails.json").read_text())
    data["devices"].append({"name": "Console", "match": [], "brand": "#123456", "logo": "playstation.svg"})
    data["devices"].append({"name": "Loud", "match": ["Loud"], "brand": "#123456"})
    (root / "thumbnails.json").write_text(json.dumps(data))
    assert validate.errors(root) == ["Console: no match words", "Loud: match words must be lower case"]


def test_render_includes_device_logos():
    import render
    data = json.loads((validate.ROOT / "thumbnails.json").read_text())
    logos = render.logos(data)
    assert {"playstation.svg", "xbox.svg", "nintendo.svg"} <= set(logos)
    assert "netflix.svg" in logos


def test_arca_app_logos_share_one_view_box_width():
    import re

    widths = set()
    for name in ("arcaphotos", "arcamusic", "arcamovies", "arcadomo"):
        svg = (validate.ROOT / "logos" / f"{name}.svg").read_text()
        box = re.search(r'viewBox="([^"]+)"', svg).group(1).split()
        widths.add((box[2], box[3]))
    assert len(widths) == 1


def test_rejects_out_of_range_logo_width(tmp_path):
    root = copy(tmp_path)
    data = json.loads((root / "thumbnails.json").read_text())
    data["apps"][0]["width"] = 1.4
    (root / "thumbnails.json").write_text(json.dumps(data))
    assert any("width" in e for e in validate.errors(root))
