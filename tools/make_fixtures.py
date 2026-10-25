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
subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=1920x1080",
                "-frames:v", "1", os.path.join(out, "photo.jpg")], check=True)
subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440", "-t", "6", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", os.path.join(out, "test-1080p.mp4")], check=True)
print("fixtures:", sorted(os.listdir(out)))
