#!/usr/bin/env python3
"""Regenerates bundled assets from two npm packages (unpacked under a work dir):
  - lucide-static (ISC): outline icons -> Android vector drawables
  - wordlist-english (MIT, words from SCOWL): tiered word list for suggestions
Usage: make_assets.py <dir containing lucide/package and wl/package> <app/src/main dir>"""
import json, re, sys, xml.etree.ElementTree as ET
src, out = sys.argv[1], sys.argv[2]

ICONS = """arrow-left ellipsis-vertical sparkles box layers zap message-square settings palette moon sun keyboard
volume-2 vibrate globe languages spell-check message-square-more pencil-line sliders-horizontal list square-terminal
shield upload download chevron-right chevron-down check arrow-big-up delete corner-down-left mic sparkle plug cloud
plus trash-2 x refresh-cw clipboard wand-sparkles circle-dashed video""".split()

def num(el, k, d=0.0): return float(el.get(k, d))

def to_path(el):
    tag = el.tag.split('}')[1]
    if tag == 'path': return el.get('d')
    if tag == 'line': return f"M{num(el,'x1')},{num(el,'y1')}L{num(el,'x2')},{num(el,'y2')}"
    if tag in ('polyline', 'polygon'):
        pts = re.findall(r'-?[\d.]+', el.get('points'))
        d = 'M' + 'L'.join(f"{pts[i]},{pts[i+1]}" for i in range(0, len(pts), 2))
        return d + ('Z' if tag == 'polygon' else '')
    if tag in ('circle', 'ellipse'):
        cx, cy = num(el, 'cx'), num(el, 'cy')
        rx = num(el, 'r') if tag == 'circle' else num(el, 'rx')
        ry = num(el, 'r') if tag == 'circle' else num(el, 'ry')
        return f"M{cx-rx},{cy}a{rx},{ry} 0 1,0 {2*rx},0a{rx},{ry} 0 1,0 {-2*rx},0Z"
    if tag == 'rect':
        x, y, w, h = num(el, 'x'), num(el, 'y'), num(el, 'width'), num(el, 'height')
        r = min(num(el, 'rx', num(el, 'ry', 0)), w / 2, h / 2)
        if r == 0: return f"M{x},{y}h{w}v{h}h{-w}Z"
        return (f"M{x+r},{y}h{w-2*r}a{r},{r} 0 0 1 {r},{r}v{h-2*r}a{r},{r} 0 0 1 {-r},{r}h{-(w-2*r)}"
                f"a{r},{r} 0 0 1 {-r},{-r}v{-(h-2*r)}a{r},{r} 0 0 1 {r},{-r}Z")
    raise SystemExit(f"unsupported element {tag}")

for name in ICONS:
    root = ET.parse(f"{src}/lucide/package/icons/{name}.svg").getroot()
    paths = "".join(
        f'    <path android:pathData="{to_path(el)}" android:strokeColor="#FFFFFF" android:strokeWidth="2"'
        f' android:strokeLineCap="round" android:strokeLineJoin="round" />\n' for el in root)
    xml = ('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp"'
           ' android:viewportWidth="24" android:viewportHeight="24">\n' + paths + '</vector>\n')
    open(f"{out}/res/drawable/ic_{name.replace('-', '_')}.xml", 'w').write(xml)

# Word list: one word per line, most common tier first; a line starting with '#' opens a tier.
seen, lines = set(), []
for tier in (10, 20, 35, 40, 50):
    words = set()
    for variant in ('english', 'american'):
        words.update(json.load(open(f"{src}/wl/package/{variant}-words-{tier}.json")))
    keep = sorted(w for w in words if re.fullmatch(r"[A-Za-z][A-Za-z']*", w) and w not in seen and len(w) <= 20)
    seen.update(keep)
    lines.append(f"#{tier}")
    lines.extend(keep)
open(f"{out}/assets/words.txt", 'w').write("\n".join(lines) + "\n")
print(len(ICONS), "icons;", len(seen), "words")
