"""مولّد هوية رفيق من مصدر واحد: الرمز (مساعد يحمل بطاقة مهام) + الكلمة + الشعار اللفظي.
يُخرج: SVG للوحة التحكم، وأيقونة أندرويد التكيفية (أمامية/أحادية)، وأيقونة الإشعار.
python brand.py FONTS_DIR OUT_DIR"""
import sys
from pathlib import Path

import uharfbuzz as hb
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

FONTS, OUT = Path(sys.argv[1]), Path(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)
BG, DARK = "#0F0E0D", "#141210"
G = ("#FCE29A", "#EDBE55", "#C78F2C")      # تدرّج ذهبي رئيسي
G2 = ("#F6CF6E", "#B9822A")


# ——— أشكال أولية كمسارات (تصلح لـ SVG ولـ VectorDrawable معًا)
def rrect(x, y, w, h, r):
    return (f"M{x + r},{y}H{x + w - r}A{r},{r} 0 0 1 {x + w},{y + r}V{y + h - r}A{r},{r} 0 0 1 {x + w - r},{y + h}"
            f"H{x + r}A{r},{r} 0 0 1 {x},{y + h - r}V{y + r}A{r},{r} 0 0 1 {x + r},{y}Z")


def circle(cx, cy, r):
    return f"M{cx - r},{cy}A{r},{r} 0 1 1 {cx + r},{cy}A{r},{r} 0 1 1 {cx - r},{cy}Z"


BAND = ("M548,416C680,404 812,452 848,552C866,604 846,652 808,690L780,664C800,620 796,588 770,566"
        "C724,528 640,520 572,534C548,500 540,452 548,416Z")
LEG = "M598,702L700,556C722,600 732,652 720,702Z"
CHECK = "M456,446l26,26l46,-56"
LINES = "M462,526h96M462,572h72"
EYES = "M694,322a20,20 0 0 1 40,0M776,322a20,20 0 0 1 40,0"

# كل عنصر: (مسار, تعبئة, حد, عرض الحد, مجموعة الدوران؟)  — التعبئة: 'g' ذهبي، 'g2' ذهبي ثانٍ، 'dark'، 'bg'
CARD_ROT = (-25, 515, 480)
ELEMENTS = [
    (rrect(392, 328, 246, 304, 62), "g", None, 0, True),
    (rrect(420, 356, 190, 248, 36), "dark", None, 0, True),
    (CHECK, None, "g", 20, True),
    (LINES, None, "g", 20, True),
    (LEG, "g2", None, 0, False),
    (BAND, "bg", "bg", 22, False),
    (BAND, "g", None, 0, False),
    (circle(722, 290, 164), "bg", None, 0, False),
    (circle(722, 290, 152), "g", None, 0, False),
    (rrect(612, 206, 232, 172, 86), "dark", None, 0, False),
    (EYES, None, "g", 15, False),
]
MARK_BOX = (370, 130, 500, 590)  # x, y, w, h


def svg_defs():
    return (f'<linearGradient id="g" gradientUnits="userSpaceOnUse" x1="400" y1="140" x2="860" y2="720">'
            f'<stop offset="0" stop-color="{G[0]}"/><stop offset=".45" stop-color="{G[1]}"/><stop offset="1" stop-color="{G[2]}"/></linearGradient>'
            f'<linearGradient id="g2" gradientUnits="userSpaceOnUse" x1="0" y1="556" x2="0" y2="702">'
            f'<stop offset="0" stop-color="{G2[0]}"/><stop offset="1" stop-color="{G2[1]}"/></linearGradient>'
            f'<linearGradient id="w" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{G[0]}"/>'
            f'<stop offset=".5" stop-color="{G[1]}"/><stop offset="1" stop-color="{G[2]}"/></linearGradient>')


def svg_mark(mono=None):
    col = {"g": "url(#g)", "g2": "url(#g2)", "dark": DARK, "bg": BG}
    out = []
    for d, fill, stroke, sw, rot in ELEMENTS:
        if mono:  # نسخة أحادية: الذهبي = اللون، الداكن والخلفية = شفاف بقصّ (نستخدم الخلفية الداكنة)
            f = {"g": mono, "g2": mono, "dark": BG, "bg": BG}.get(fill, "none") if fill else "none"
            s = {"g": mono, "bg": BG}.get(stroke, "none") if stroke else "none"
        else:
            f = col[fill] if fill else "none"
            s = col[stroke] if stroke else "none"
        a = f'<path d="{d}" fill="{f}"' + (f' stroke="{s}" stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round"' if stroke else "") + "/>"
        out.append(a if not rot else f'<g transform="rotate({CARD_ROT[0]} {CARD_ROT[1]} {CARD_ROT[2]})">{a}</g>')
    return "".join(out)


# ——— نص بخط حقيقي ← مسارات
def text_path(font_file, text, cx, cy, height=None, width=None):
    tt = TTFont(font_file)
    gs = tt.getGlyphSet()
    face = hb.Face(hb.Blob.from_file_path(str(font_file)))
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(hb.Font(face), buf, {})
    glyphs, x = [], 0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        glyphs.append((tt.getGlyphName(info.codepoint), x + pos.x_offset, pos.y_offset))
        x += pos.x_advance
    bp = BoundsPen(gs)
    for n, dx, dy in glyphs:
        gs[n].draw(TransformPen(bp, (1, 0, 0, 1, dx, dy)))
    x0, y0, x1, y1 = bp.bounds
    s = (height / (y1 - y0)) if height else (width / (x1 - x0))
    pen = SVGPathPen(gs, ntos=lambda v: f"{v:.1f}".rstrip("0").rstrip("."))
    mx, my = (x0 + x1) / 2, (y0 + y1) / 2
    for n, dx, dy in glyphs:
        gs[n].draw(TransformPen(pen, (s, 0, 0, -s, cx + (dx - mx) * s, cy + (my - dy) * s)))
    return pen.getCommands(), s * (x1 - x0), s * (y1 - y0)


def wrap(body, vb, w, h, bg=None, rx=0):
    b = f'<rect x="{vb[0]}" y="{vb[1]}" width="{vb[2]}" height="{vb[3]}" rx="{rx}" fill="{bg}"/>' if bg else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{" ".join(map(str, vb))}" width="{w}" height="{h}">'
            f'<defs>{svg_defs()}</defs>{b}{body}</svg>')


def build(word_font, tag_font, tag=True):
    mx, my, mw, mh = MARK_BOX
    cx = mx + mw / 2
    word, ww, wh = text_path(word_font, "رفيق", cx, my + mh + 150, height=200)
    parts = [svg_mark(), f'<path d="{word}" fill="url(#w)"/>']
    bottom = my + mh + 150 + wh / 2
    tw = 0
    if tag:
        tg, tw, th = text_path(tag_font, "إدارة مهام ومساعد شخصي بالذكاء الاصطناعي", cx, bottom + 80, height=48)
        parts.append(f'<path d="{tg}" fill="{G[1]}"/>')
        bottom += 80 + th / 2
    pad = 90
    W = max(mw, ww, tw) + 2 * pad
    vb = (round(cx - W / 2), my - pad, round(W), round(bottom - my + 2 * pad))
    return "".join(parts), vb


tajawal = FONTS / "tajawal_bold.ttf"
messiri = FONTS / "elmessiri_bold.ttf"
tag_font = FONTS / "tajawal_medium.ttf"

for name, f in (("tajawal", tajawal), ("messiri", messiri)):
    body, vb = build(f, tag_font)
    (OUT / f"full_{name}.svg").write_text(wrap(body, vb, vb[2] // 2, vb[3] // 2, BG))
    body, vb = build(f, tag_font, tag=False)
    (OUT / f"stack_{name}.svg").write_text(wrap(body, vb, vb[2] // 2, vb[3] // 2))

# الرمز وحده (شفاف) + بلاطة داكنة (favicon/الشريط الجانبي)
mx, my, mw, mh = MARK_BOX
side = max(mw, mh) + 120
tile_vb = (round(mx + mw / 2 - side / 2), round(my + mh / 2 - side / 2), side, side)
(OUT / "mark.svg").write_text(wrap(svg_mark(), (mx - 10, my - 10, mw + 20, mh + 20), 250, 300))
(OUT / "tile.svg").write_text(wrap(svg_mark(), tile_vb, 256, 256, BG, rx=side * 0.22))


# ——— أندرويد: VectorDrawable (108×108). الرمز داخل المنطقة الآمنة (قطر 66) مع هامش
def vector_drawable(mono=False, size=108, fit=60, offset_y=0.0):
    s = fit / mh
    tx = size / 2 - (mx + mw / 2) * s
    ty = size / 2 - (my + mh / 2) * s + offset_y
    head = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{size}dp" android:height="{size}dp" android:viewportWidth="{size}" android:viewportHeight="{size}">\n'
            f'  <group android:scaleX="{s:.5f}" android:scaleY="{s:.5f}" android:translateX="{tx:.3f}" android:translateY="{ty:.3f}">\n')

    def grad(colors, x1, y1, x2, y2, attr):
        items = "".join(f'<item android:offset="{o}" android:color="#FF{c[1:]}"/>'
                        for o, c in zip(([0, .45, 1] if len(colors) == 3 else [0, 1]), colors))
        return (f'<aapt:attr name="android:{attr}"><gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" '
                f'android:endX="{x2}" android:endY="{y2}">{items}</gradient></aapt:attr>')

    body = []
    for d, fill, stroke, sw, rot in ELEMENTS:
        if mono and fill in ("dark", "bg") and not stroke:
            # في الأحادية: الأجزاء الداكنة تُقصّ بلون شفاف لا يعمل — نرسم أسود شفافًا؟ نستخدم fillType لقصّها لاحقًا
            pass
        attrs = [f'android:pathData="{d}"']
        inner = ""
        if fill:
            if mono:
                attrs.append('android:fillColor="#FFFFFFFF"' if fill in ("g", "g2") else 'android:fillColor="#FF000000"')
            elif fill == "g":
                inner += grad(G, 400, 140, 860, 720, "fillColor")
            elif fill == "g2":
                inner += grad(G2, 0, 556, 0, 702, "fillColor")
            else:
                attrs.append(f'android:fillColor="#FF{(DARK if fill == "dark" else BG)[1:]}"')
        if stroke:
            attrs += [f'android:strokeWidth="{sw}"', 'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
            if mono:
                attrs.append('android:strokeColor="#FFFFFFFF"' if stroke == "g" else 'android:strokeColor="#FF000000"')
            elif stroke == "g":
                inner += grad(G, 400, 140, 860, 720, "strokeColor")
            else:
                attrs.append(f'android:strokeColor="#FF{BG[1:]}"')
        p = f'<path {" ".join(attrs)}>{inner}</path>' if inner else f'<path {" ".join(attrs)}/>'
        if rot:
            p = (f'<group android:rotation="{CARD_ROT[0]}" android:pivotX="{CARD_ROT[1]}" android:pivotY="{CARD_ROT[2]}">'
                 f'{p}</group>')
        body.append("    " + p)
    return head + "\n".join(body) + "\n  </group>\n</vector>\n"


(OUT / "ic_launcher_foreground.xml").write_text(vector_drawable(fit=58))
(OUT / "ic_stat_harith.xml").write_text(vector_drawable(mono=True, size=24, fit=22))
print("ok", tile_vb)


# ——— نسخة أحادية حقيقية (قناع شفافية): الأجزاء الداكنة مقصوصة بـ evenOdd
def mono_drawable(size, fit, head_only=False):
    s = fit / (mh if not head_only else 310)
    cxm, cym = (mx + mw / 2, my + mh / 2) if not head_only else (722, 290)
    tx, ty = size / 2 - cxm * s, size / 2 - cym * s
    W = 'android:fillColor="#FFFFFFFF"'
    st = lambda d, w: (f'<path android:pathData="{d}" android:strokeColor="#FFFFFFFF" android:strokeWidth="{w}" '
                       'android:strokeLineCap="round" android:strokeLineJoin="round"/>')
    head = [f'<path android:pathData="{circle(722, 290, 152)}{rrect(612, 206, 232, 172, 86)}" android:fillType="evenOdd" {W}/>',
            st(EYES, 18 if head_only else 15)]
    rest = [f'<group android:rotation="{CARD_ROT[0]}" android:pivotX="{CARD_ROT[1]}" android:pivotY="{CARD_ROT[2]}">'
            f'<path android:pathData="{rrect(392, 328, 246, 304, 62)}{rrect(420, 356, 190, 248, 36)}" android:fillType="evenOdd" {W}/>'
            f'{st(CHECK, 20)}{st(LINES, 20)}</group>',
            f'<path android:pathData="{LEG}" {W}/>', f'<path android:pathData="{BAND}" {W}/>']
    items = head if head_only else rest + head
    return ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{size}dp" android:height="{size}dp" android:viewportWidth="{size}" android:viewportHeight="{size}">\n'
            f'  <group android:scaleX="{s:.5f}" android:scaleY="{s:.5f}" android:translateX="{tx:.3f}" android:translateY="{ty:.3f}">\n    '
            + "\n    ".join(items) + "\n  </group>\n</vector>\n")


(OUT / "ic_launcher_monochrome.xml").write_text(mono_drawable(108, 58))
(OUT / "ic_stat_harith.xml").write_text(mono_drawable(24, 20, head_only=True))
print("mono ok")
(OUT / "rafiq_mark.xml").write_text(vector_drawable(size=100, fit=96).replace('android:width="100dp" android:height="100dp"', 'android:width="96dp" android:height="96dp"'))
for name, f in (("tajawal", tajawal),):
    body, vb = build(f, tag_font)
    (OUT / "full_clear.svg").write_text(wrap(body, vb, vb[2] // 2, vb[3] // 2))
print("extra ok")
