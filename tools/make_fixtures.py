#!/usr/bin/env python3
"""Тестовые файлы для проверки на эмуляторе CI (без личных данных)."""
import os
import subprocess
import sys
import zipfile

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
with open(os.path.join(out, "заметка-тест.md"), "w", encoding="utf-8") as f:
    f.write("# Заметка\n\nПроверка кириллицы и **Markdown**.\n")
with open(os.path.join(out, "cp1251.txt"), "wb") as f:
    f.write("Привет из Windows-1251!\n".encode("cp1251"))
with open(os.path.join(out, "data.json"), "w", encoding="utf-8") as f:
    f.write('{"ключ": "значение", "n": 1}\n')
with zipfile.ZipFile(os.path.join(out, "fixture.zip"), "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("readme.txt", "Тестовый архив\n")
    z.writestr("папка/вложенный.txt", "вложенный файл\n")


def write_pdf(path):
    """Минимальный корректный одностраничный PDF (без внешних библиотек)."""
    objs = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        None,
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    stream = b"BT /F1 36 Tf 72 720 Td (PFM test PDF) Tj ET"
    objs[3] = b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream"
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, o in enumerate(objs, start=1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n".encode() + o + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode()
    for off in offsets:
        out += f"{off:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    with open(path, "wb") as f:
        f.write(out)


write_pdf(os.path.join(out, "doc.pdf"))
subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=1920x1080",
                "-frames:v", "1", os.path.join(out, "photo.jpg")], check=True)
# Второе фото — для проверки перелистывания; trash-me.jpg — специально для проверки удаления в корзину.
for name, src in (("photo2.jpg", "smptebars=size=1600x1200"), ("trash-me.jpg", "rgbtestsrc=size=640x480")):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", src,
                    "-frames:v", "1", os.path.join(out, name)], check=True)
subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440", "-t", "6", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", os.path.join(out, "test-1080p.mp4")], check=True)
print("fixtures:", sorted(os.listdir(out)))
