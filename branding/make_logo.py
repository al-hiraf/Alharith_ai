"""يولّد شعار رفيق من حروف خط Amiri Bold الفعلية (مُشكّلة بـ HarfBuzz) كمسارات متجهة مركزية بدقة."""
import sys
from pathlib import Path

import uharfbuzz as hb
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

FONT = sys.argv[1]
OUT = Path(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)
tt = TTFont(FONT)
gs = tt.getGlyphSet()


def shape(text):
    blob = hb.Blob.from_file_path(FONT)
    font = hb.Font(hb.Face(blob))
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(font, buf, {})
    out, x = [], 0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        out.append((tt.getGlyphName(info.codepoint), x + pos.x_offset, pos.y_offset))
        x += pos.x_advance
    return out


def bounds(glyphs):
    bp = BoundsPen(gs)
    for name, dx, dy in glyphs:
        gs[name].draw(TransformPen(bp, (1, 0, 0, 1, dx, dy)))
    return bp.bounds


def path(glyphs, box, cx, cy, width, dp=2):
    """يضع الكلمة بحيث يكون مركز حدودها في (cx,cy) بعرض width (مع قلب المحور y)."""
    x0, y0, x1, y1 = box
    s = width / (x1 - x0)
    pen = SVGPathPen(gs, ntos=lambda v: (f"{v:.{dp}f}").rstrip("0").rstrip("."))
    mx, my = (x0 + x1) / 2, (y0 + y1) / 2
    for name, dx, dy in glyphs:
        t = (s, 0, 0, -s, cx + (dx - mx) * s, cy + (my - dy) * s)
        gs[name].draw(TransformPen(pen, t))
    return pen.getCommands(), s * (y1 - y0)


word = shape("رفيق")
wb = bounds(word)
print("aspect", (wb[2] - wb[0]) / (wb[3] - wb[1]))

GOLD = """<linearGradient id="g" x1="0" y1="0" x2="0" y2="1">
<stop offset="0" stop-color="#FBEFC4"/><stop offset=".45" stop-color="#E7C46C"/><stop offset="1" stop-color="#A9781F"/></linearGradient>
<linearGradient id="r" x1="0" y1="0" x2="1" y2="1">
<stop offset="0" stop-color="#F7E7B0"/><stop offset=".5" stop-color="#D4A94C"/><stop offset="1" stop-color="#8A6224"/></linearGradient>
<radialGradient id="bg" cx=".5" cy=".38" r=".75"><stop offset="0" stop-color="#2E2924"/><stop offset="1" stop-color="#141312"/></radialGradient>"""


def logo_svg(size=512, word_w=0.60, ring=True, bg=True, mono=None):
    c = size / 2
    d, h = path(word, wb, c, c + size * 0.015, size * word_w)  # إزاحة بصرية بسيطة للأسفل
    fill = mono or "url(#g)"
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" width="{size}" height="{size}"><defs>{GOLD}</defs>']
    if bg:
        parts.append(f'<rect width="{size}" height="{size}" rx="{size * .22:.1f}" fill="url(#bg)"/>')
    if ring:
        parts.append(f'<circle cx="{c}" cy="{c}" r="{size * .41:.1f}" fill="none" stroke="{mono or "url(#r)"}" stroke-width="{size * .022:.1f}"/>')
        parts.append(f'<circle cx="{c}" cy="{c}" r="{size * .375:.1f}" fill="none" stroke="{mono or "#E0BC62"}" stroke-opacity=".35" stroke-width="{size * .004:.2f}"/>')
    parts.append(f'<path d="{d}" fill="{fill}"/>')
    parts.append("</svg>")
    return "".join(parts)


(OUT / "logo.svg").write_text(logo_svg())
(OUT / "mark.svg").write_text(logo_svg(64, 0.70).replace('stroke-width="1.4"','stroke-width="2.2"'))
(OUT / "word_gold.svg").write_text(logo_svg(512, 0.86, ring=False, bg=False))

# ——— Android: مقدمة الأيقونة التكيفية (108×108، المنطقة الآمنة قطرها 66)
fd, _ = path(word, wb, 54, 55.2, 40, dp=2)
ring_outer = "M54,26.5a27.5,27.5 0 1,1 -0.01,0z"
(OUT / "word_108.txt").write_text(fd)
# أيقونة الإشعار 24×24 (بيضاء)
nd, _ = path(word, wb, 12, 12.3, 21, dp=2)
(OUT / "word_24.txt").write_text(nd)
print("ok")
