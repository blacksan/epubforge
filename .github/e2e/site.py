"""Generates a small multi-chapter story site for the emulator test (served on the host, seen as 10.0.2.2)."""
import os, struct, sys, zlib
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
N = 8
para = ("The lighthouse keeper counted the waves again, as he had every night since the storm, "
        "and each time the number came out a little different. ")
def chunk(t, d):
    return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
W, H = 120, 60
raw = b"".join(b"\0" + bytes([30, 90 + y, 160]) * W for y in range(H))
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
open(f"{out}/pic.png", "wb").write(png)
links = "\n".join(f'<li><a href="chapter-{i}.html">Chapter {i}: The Count of {i}</a></li>' for i in range(1, N + 1))
open(f"{out}/index.html", "w").write(f"""<!doctype html><html><head><meta charset="utf-8"><title>The Lighthouse Count</title></head>
<body><ul>{links}</ul></body></html>""")
for i in range(1, N + 1):
    body = "".join(f"<p>{i}.{p} {para * 3}</p>" for p in range(1, 41))
    img = '<p><img src="pic.png" alt="a picture"></p>' if i == 1 else ""
    open(f"{out}/chapter-{i}.html", "w").write(f"""<!doctype html><html><head><meta charset="utf-8"><title>Chapter {i}</title></head>
<body><h1>Chapter {i}: The Count of {i}</h1>{img}{body}</body></html>""")
print("site written", out)

# ---- Sample ebook files for the import test.
import zipfile
def xml_escape(t): return t.replace("&", "&amp;").replace("<", "&lt;")
def make_epub(path):
    chapters = [(f"Part {i}: The Keeper's Log", "".join(f"<p>Log {i}.{p}. {para * 2}</p>" for p in range(1, 31))) for i in range(1, 4)]
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        z.writestr("OEBPS/cover.png", png)
        items = "".join(f'<item id="c{i}" href="c{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(1, 4))
        spine = "".join(f'<itemref idref="c{i}"/>' for i in range(1, 4))
        z.writestr("OEBPS/content.opf", f'''<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">urn:uuid:e2e-keeper</dc:identifier><dc:title>The Keeper's Log</dc:title><dc:creator>A. Tester</dc:creator><dc:language>en</dc:language>
<dc:description>A sample book for the import test.</dc:description><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata>
<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>{items}</manifest>
<spine>{spine}</spine></package>''')
        nav = "".join(f'<li><a href="c{i}.xhtml">{xml_escape(t)}</a></li>' for i, (t, _) in enumerate(chapters, 1))
        z.writestr("OEBPS/nav.xhtml", f'<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>{nav}</ol></nav></body></html>')
        for i, (t, body) in enumerate(chapters, 1):
            z.writestr(f"OEBPS/c{i}.xhtml", f'<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>{xml_escape(t)}</title></head><body><h1>{xml_escape(t)}</h1>{body}</body></html>')

def make_pdf(path, pages=3):
    objs, kids = [], []
    font_id = 3
    def add(o): objs.append(o); return len(objs)
    add(None); add(None)  # 1 catalog, 2 pages (filled below)
    add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    for n in range(1, pages + 1):
        lines = [f"BT /F1 28 Tf 72 720 Td (Sample PDF page {n}) Tj ET"] + [
            f"BT /F1 12 Tf 72 {680 - k * 18} Td (Line {k} of page {n}: the lighthouse keeper counted the waves.) Tj ET" for k in range(1, 30)]
        stream = "\n".join(lines).encode()
        c = add(f"<< /Length {len(stream)} >>\nstream\n".encode() + stream + b"\nendstream")
        kids.append(add(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 {font_id} 0 R >> >> /Contents {c} 0 R >>"))
    objs[0] = "<< /Type /Catalog /Pages 2 0 R >>"
    objs[1] = f"<< /Type /Pages /Kids [{' '.join(f'{k} 0 R' for k in kids)}] /Count {len(kids)} >>"
    out = bytearray(b"%PDF-1.4\n"); offsets = []
    for i, o in enumerate(objs, 1):
        offsets.append(len(out))
        body = o if isinstance(o, bytes) else o.encode()
        out += f"{i} 0 obj\n".encode() + body + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode() + "".join(f"{x:010d} 00000 n \n" for x in offsets).encode()
    out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    open(path, "wb").write(out)

make_epub(f"{out}/keepers-log.epub")
make_pdf(f"{out}/sample.pdf")
print("books written")
