#!/usr/bin/env python3
"""Fetch Lucide SVGs (if missing) and emit corral-ui's vendored icons/Lucide.kt."""
import os
import re
import urllib.request
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(ROOT, "docs", "assets", "lucide-src")
OUT = os.path.join(
    ROOT, "corral-ui", "src", "main", "kotlin", "dev", "corral", "ui", "icons", "Lucide.kt")
BASE_URL = "https://raw.githubusercontent.com/lucide-icons/lucide/66d8f9fc394b8530377e5f6112f0b8908ba01280/icons"
SVG_NS = "{http://www.w3.org/2000/svg}"

ICONS = [
    ("activity", "activity"),
    ("airplay", "airplay"),
    ("alert-octagon", "octagon-alert"),
    ("alert-triangle", "triangle-alert"),
    ("camera-off", "camera-off"),
    ("check", "check"),
    ("check-circle", "circle-check-big"),
    ("chevron-down", "chevron-down"),
    ("clock", "clock"),
    ("cpu", "cpu"),
    ("file-text", "file-text"),
    ("globe", "globe"),
    ("image", "image"),
    ("inbox", "inbox"),
    ("info", "info"),
    ("list", "list"),
    ("loader", "loader"),
    ("minus-circle", "circle-minus"),
    ("monitor", "monitor"),
    ("moon", "moon"),
    ("plus", "plus"),
    ("power", "power"),
    ("refresh-ccw", "refresh-ccw"),
    ("refresh-cw", "refresh-cw"),
    ("save", "save"),
    ("server", "server"),
    ("settings", "settings"),
    ("shield", "shield"),
    ("smartphone", "smartphone"),
    ("sun", "sun"),
    ("terminal", "terminal"),
    ("unlock", "lock-open"),
    ("usb", "usb"),
    ("user", "user"),
    ("wifi", "wifi"),
    ("x", "x"),
    ("x-circle", "circle-x"),
]


def fetch(source):
    os.makedirs(SRC_DIR, exist_ok=True)
    path = os.path.join(SRC_DIR, source + ".svg")
    if not os.path.exists(path):
        url = f"{BASE_URL}/{source}.svg"
        with urllib.request.urlopen(url, timeout=30) as response:
            body = response.read()
        if response.status != 200:
            raise RuntimeError(f"{source}: HTTP {response.status}")
        with open(path, "wb") as handle:
            handle.write(body)
    return path


def fmt(value):
    return f"{value:g}"


def attr(element, key, default=0.0):
    raw = element.get(key)
    return default if raw is None else float(raw)


def rect_path(element):
    x = attr(element, "x")
    y = attr(element, "y")
    width = attr(element, "width")
    height = attr(element, "height")
    rx = attr(element, "rx")
    ry = attr(element, "ry")
    if rx == 0:
        rx = ry
    if ry == 0:
        ry = rx
    if rx == 0:
        return f"M {fmt(x)} {fmt(y)} L {fmt(x + width)} {fmt(y)} " \
               f"L {fmt(x + width)} {fmt(y + height)} L {fmt(x)} {fmt(y + height)} Z"
    rx = min(rx, width / 2)
    ry = min(ry, height / 2)
    return (
        f"M {fmt(x + rx)} {fmt(y)} H {fmt(x + width - rx)} "
        f"A {fmt(rx)} {fmt(ry)} 0 0 1 {fmt(x + width)} {fmt(y + ry)} "
        f"V {fmt(y + height - ry)} "
        f"A {fmt(rx)} {fmt(ry)} 0 0 1 {fmt(x + width - rx)} {fmt(y + height)} "
        f"H {fmt(x + rx)} "
        f"A {fmt(rx)} {fmt(ry)} 0 0 1 {fmt(x)} {fmt(y + height - ry)} "
        f"V {fmt(y + ry)} "
        f"A {fmt(rx)} {fmt(ry)} 0 0 1 {fmt(x + rx)} {fmt(y)} Z"
    )


def ellipse_path(cx, cy, rx, ry):
    return (
        f"M {fmt(cx - rx)} {fmt(cy)} "
        f"A {fmt(rx)} {fmt(ry)} 0 1 0 {fmt(cx + rx)} {fmt(cy)} "
        f"A {fmt(rx)} {fmt(ry)} 0 1 0 {fmt(cx - rx)} {fmt(cy)} Z"
    )


def points_path(element, close):
    coords = [float(n) for n in re.findall(r"-?\d*\.?\d+", element.get("points", ""))]
    pairs = [f"{fmt(coords[i])} {fmt(coords[i + 1])}" for i in range(0, len(coords) - 1, 2)]
    data = "M " + " L ".join(pairs)
    return data + " Z" if close else data


def shape_path(element):
    tag = element.tag.replace(SVG_NS, "")
    if tag == "path":
        return element.get("d")
    if tag == "line":
        return f"M {fmt(attr(element, 'x1'))} {fmt(attr(element, 'y1'))} " \
               f"L {fmt(attr(element, 'x2'))} {fmt(attr(element, 'y2'))}"
    if tag == "rect":
        return rect_path(element)
    if tag == "circle":
        r = attr(element, "r")
        return ellipse_path(attr(element, "cx"), attr(element, "cy"), r, r)
    if tag == "ellipse":
        return ellipse_path(
            attr(element, "cx"), attr(element, "cy"), attr(element, "rx"), attr(element, "ry"))
    if tag == "polyline":
        return points_path(element, False)
    if tag == "polygon":
        return points_path(element, True)
    raise RuntimeError(f"unsupported element <{tag}>")


def path_data(source):
    root = ET.parse(fetch(source)).getroot()
    return [shape_path(child) for child in root if child.tag.replace(SVG_NS, "")]


def accessor(kebab, source):
    name = "".join(part.capitalize() for part in kebab.split("-"))
    return name, path_data(source)


def escape(data):
    return data.replace("\\", "\\\\").replace('"', '\\"')


def emit():
    blocks = []
    failed = 0
    for kebab, source in ICONS:
        try:
            name, nodes = accessor(kebab, source)
        except Exception as error:
            failed += 1
            print(f"MISSING {kebab}: {error}")
            continue
        paths = "\n".join(
            "            addPath(\n"
            f"                pathData = addPathNodes(\"{escape(data)}\"),\n"
            "                stroke = SolidColor(Color(0xFF000000)),\n"
            "                strokeLineWidth = 2f,\n"
            "                strokeLineCap = StrokeCap.Round,\n"
            "                strokeLineJoin = StrokeJoin.Round,\n"
            "                fill = null,\n"
            "            )"
            for data in nodes
        )
        blocks.append(
            f"    val {name}: ImageVector by lazy {{\n"
            f"        ImageVector.Builder(\"{name}\", 24.dp, 24.dp, 24f, 24f).apply {{\n"
            f"{paths}\n"
            "        }.build()\n"
            "    }"
        )
    header = (
        "package dev.corral.ui.icons\n\n"
        "import androidx.compose.ui.graphics.Color\n"
        "import androidx.compose.ui.graphics.SolidColor\n"
        "import androidx.compose.ui.graphics.StrokeCap\n"
        "import androidx.compose.ui.graphics.StrokeJoin\n"
        "import androidx.compose.ui.graphics.vector.ImageVector\n"
        "import androidx.compose.ui.graphics.vector.addPathNodes\n"
        "import androidx.compose.ui.unit.dp\n\n"
        "object Lucide {\n"
    )
    return header + "\n\n".join(blocks) + "\n}\n", failed


def main():
    source, failed = emit()
    if failed:
        raise SystemExit(f"{failed} icon(s) missing; {OUT} not written")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as handle:
        handle.write(source)
    print(f"wrote {OUT} ({len(ICONS)} icons, 0 missing)")


if __name__ == "__main__":
    main()
