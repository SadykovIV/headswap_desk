"""Package the adapted release and its source. The end user needs only Python's standard library."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile
import zlib

import bsdiff4

SOURCE = "bb67e07ff14a6300082a33fa75be2082ab250e5aec1bd6f75e72b5b034b157c4"
TARGET_NAME = "DriveDeck-HeadSwap-0.6.8-code305.apk"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def expanded_target(target, data):
    # Compare decompressed APK entries, retaining exact compressed bytes when zlib differs.
    expanded = bytearray()
    spans = []
    previous = 0
    with zipfile.ZipFile(target) as apk:
        for entry in sorted(apk.infolist(), key=lambda item: item.header_offset):
            if entry.compress_type != zipfile.ZIP_DEFLATED:
                continue
            name_len, extra_len = struct.unpack_from("<HH", data, entry.header_offset + 26)
            start = entry.header_offset + 30 + name_len + extra_len
            end = start + entry.compress_size
            plain = apk.read(entry)
            for level in (6, 9, 1, 2, 3, 4, 5, 7, 8, 0):
                encoder = zlib.compressobj(level, zlib.DEFLATED, -15)
                if encoder.compress(plain) + encoder.flush() == data[start:end]:
                    expanded.extend(data[previous:start])
                    spans.append([len(expanded), len(plain), level])
                    expanded.extend(plain)
                    previous = end
                    break
    expanded.extend(data[previous:])
    return bytes(expanded), json.dumps(spans, separators=(",", ":")).encode()


def archive(path, files):
    with zipfile.ZipFile(path, "w") as output:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.external_attr = 0o100644 << 16
            output.writestr(entry, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    src = args.original.read_bytes()
    if sha(src) != SOURCE or args.target.name != TARGET_NAME:
        parser.error("Expected unmodified upstream code300 and the verified code305 build")
    base = Path(__file__).resolve().parent
    args.output.mkdir(exist_ok=True, parents=True)
    target = args.target.read_bytes()
    with zipfile.ZipFile(args.original) as apk:
        dictionary = src + b"".join(apk.read(item) for item in apk.infolist())
    expanded, spans = expanded_target(args.target, target)
    delta = bsdiff4.diff(dictionary, expanded)
    script = (base / "apply_patch.py.in").read_text().replace("@APK_SHA256@", sha(target)).replace(
        "@DEFLATE_SHA256@", sha(spans)).replace("@DELTA_SHA256@", sha(delta))
    instructions = f"""DriveDeck 0.6.8 code300 → DriveDeck HeadSwap code305

Распакуйте архив в отдельную папку. Скопируйте туда оригинальный DriveDeck 0.6.8 code300 и выполните
python3 apply_patch.py DriveDeck-0.6.8-code300.apk

SHA-256 оригинального APK    {SOURCE}
SHA-256 восстановленного APK {sha(target)}
Поддерживаются Python 3 и стандартная zlib. Существующий выходной APK сохраняется.
"""
    archive(args.output / "DriveDeck-0.6.8-code300-to-HeadSwap-code305-patch.zip", {
        "README.txt": instructions.encode(), "apply_patch.py": script.encode(),
        "deflate.json": spans, "delta.bin": delta,
    })
    source = {str(p.relative_to(base.parent)): p.read_bytes() for p in base.rglob("*")
              if p.is_file() and p.suffix in (".aidl", ".java", ".py", ".md", ".in") and "__pycache__" not in p.parts}
    archive(args.output / "DriveDeck-0.6.8-source-patches.zip", source)


if __name__ == "__main__":
    main()
