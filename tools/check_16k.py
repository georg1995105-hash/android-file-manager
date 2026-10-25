#!/usr/bin/env python3
"""Проверка APK на совместимость с 16 КБ страницами памяти (ТЗ §1):
выравнивание PT_LOAD-сегментов каждой .so >= 16384 и выравнивание несжатых .so в ZIP.
https://developer.android.com/guide/practices/page-sizes"""
import struct
import sys
import zipfile

PAGE = 16384


def load_aligns(data: bytes):
    if data[:4] != b"\x7fELF":
        raise ValueError("не ELF")
    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"
    if is64:
        phoff, = struct.unpack_from(endian + "Q", data, 0x20)
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x36)
    else:
        phoff, = struct.unpack_from(endian + "I", data, 0x1C)
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x2A)
    result = []
    for i in range(phnum):
        off = phoff + i * phentsize
        p_type, = struct.unpack_from(endian + "I", data, off)
        if p_type != 1:  # PT_LOAD
            continue
        align, = struct.unpack_from(endian + ("Q" if is64 else "I"), data, off + (0x30 if is64 else 0x1C))
        result.append(align)
    return result


def main(path: str) -> int:
    bad = 0
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if not info.filename.endswith(".so"):
                continue
            abi = info.filename.split("/")[1]
            aligns = load_aligns(z.read(info))
            ok_elf = all(a >= PAGE for a in aligns) or abi in ("armeabi-v7a", "x86")
            header_off = info.header_offset + 30 + len(info.filename.encode()) + len(info.extra)
            ok_zip = info.compress_type != zipfile.STORED or header_off % PAGE == 0
            status = "OK" if ok_elf and ok_zip else "FAIL"
            if status == "FAIL" and abi in ("arm64-v8a", "x86_64"):
                bad += 1
            print(f"{status} {info.filename} LOAD align={sorted(set(aligns))} stored={info.compress_type == zipfile.STORED} zip16k={ok_zip}")
    print("16KB: " + ("PASS" if bad == 0 else f"FAIL ({bad})"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
