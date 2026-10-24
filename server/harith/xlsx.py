"""كاتب Excel (.xlsx) بسيط بلا مكتبات خارجية — يعمل على Termux. أوراق من اليمين لليسار، أرقام حقيقية، ترويسة عريضة."""
from __future__ import annotations

import io
import zipfile
from xml.sax.saxutils import escape


def _col(n: int) -> str:
    s = ""
    n += 1
    while n:
        n, r = divmod(n - 1, 26)
        s = chr(65 + r) + s
    return s


def _cell(ref: str, v, style: int) -> str:
    st = f' s="{style}"' if style else ""
    if v is None or v == "":
        return f'<c r="{ref}"{st}/>'
    if isinstance(v, bool):
        v = "نعم" if v else "لا"
    if isinstance(v, (int, float)):
        return f'<c r="{ref}"{st}><v>{v}</v></c>'
    txt = escape(str(v)).replace("\x00", "")
    return f'<c r="{ref}" t="inlineStr"{st}><is><t xml:space="preserve">{txt}</t></is></c>'


def _sheet(rows: list[list], rtl: bool) -> str:
    widths: dict[int, int] = {}
    out = []
    for ri, row in enumerate(rows):
        cells = []
        for ci, v in enumerate(row):
            widths[ci] = max(widths.get(ci, 8), min(60, len(str(v if v is not None else "")) + 2))
            style = 1 if ri == 0 else (2 if isinstance(v, float) else 0)
            cells.append(_cell(f"{_col(ci)}{ri + 1}", v, style))
        out.append(f'<row r="{ri + 1}">{"".join(cells)}</row>')
    cols = "".join(f'<col min="{c + 1}" max="{c + 1}" width="{w}" customWidth="1"/>' for c, w in sorted(widths.items()))
    view = ('<sheetViews><sheetView workbookViewId="0"' + (' rightToLeft="1"' if rtl else '') +
            '><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>')
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
            f'{view}{"<cols>" + cols + "</cols>" if cols else ""}<sheetData>{"".join(out)}</sheetData></worksheet>')


def build_xlsx(sheets: list[tuple[str, list[list]]], rtl: bool = True) -> bytes:
    """sheets: [(اسم الورقة, [[صف الترويسة], [صف], ...]), ...]"""
    if not sheets:
        sheets = [("Sheet1", [[]])]
    names = []
    for n, _ in sheets:
        clean = "".join(ch for ch in str(n) if ch not in '[]:*?/\\')[:31] or "Sheet"
        base, k = clean, 2
        while clean in names:
            clean = f"{base[:28]}-{k}"
            k += 1
        names.append(clean)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                   '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                   '<Default Extension="xml" ContentType="application/xml"/>'
                   '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
                   '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
                   + "".join(f'<Override PartName="/xl/worksheets/sheet{k + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
                             for k in range(len(sheets))) + '</Types>')
        z.writestr("_rels/.rels",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
                   '</Relationships>')
        z.writestr("xl/workbook.xml",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                   'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>'
                   + "".join(f'<sheet name="{escape(n)}" sheetId="{k + 1}" r:id="rId{k + 1}"/>' for k, n in enumerate(names))
                   + '</sheets></workbook>')
        z.writestr("xl/_rels/workbook.xml.rels",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   + "".join(f'<Relationship Id="rId{k + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet{k + 1}.xml"/>'
                             for k in range(len(sheets)))
                   + f'<Relationship Id="rId{len(sheets) + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
                   '</Relationships>')
        z.writestr("xl/styles.xml",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                   '<numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00"/></numFmts>'
                   '<fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><sz val="11"/><color rgb="FF1E1E1E"/><name val="Arial"/></font></fonts>'
                   '<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>'
                   '<fill><patternFill patternType="solid"><fgColor rgb="FFE0BC62"/><bgColor indexed="64"/></patternFill></fill></fills>'
                   '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
                   '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
                   '<cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
                   '<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>'
                   '<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>'
                   '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>'
                   '</styleSheet>')
        for k, (_, rows) in enumerate(sheets):
            z.writestr(f"xl/worksheets/sheet{k + 1}.xml", _sheet(rows, rtl))
    return buf.getvalue()


def read_xlsx_rows(data: bytes, sheet: int = 1) -> list[list[str]]:
    """قراءة بسيطة للتحقق (للاختبارات): يعيد النصوص والأرقام كنصوص."""
    import re
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        xml = z.read(f"xl/worksheets/sheet{sheet}.xml").decode("utf-8")
    rows = []
    for r in re.findall(r"<row [^>]*>(.*?)</row>", xml):
        vals = []
        for c in re.findall(r"<c [^>]*?(?:/>|>(.*?)</c>)", r):
            m = re.search(r"<t[^>]*>(.*?)</t>", c) or re.search(r"<v>(.*?)</v>", c)
            vals.append(m.group(1) if m else "")
        rows.append(vals)
    return rows
