#!/usr/bin/env python3
"""Losslessly recompress the unsigned release APK in place.

The Android Gradle plugin uses zlib's default compression level and leaves JPEG
resources stored.  This tiny post-pack step changes only ZIP compression: entry
names, uncompressed bytes and Android resources remain byte-for-byte identical.
"""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import tempfile
import zipfile
import zlib

try:
    import zopfli.zlib as _zopfli
except ImportError:  # A normal build remains available without the optional tool.
    _zopfli = None


class _BufferedDeflater:
    def __init__(self) -> None:
        self._parts: list[bytes] = []

    def compress(self, data: bytes) -> bytes:
        self._parts.append(bytes(data))
        return b""

    def flush(self) -> bytes:
        data = b"".join(self._parts)
        if _zopfli is not None:
            # ZIP stores raw DEFLATE, while zopfli.zlib adds a 2-byte header and
            # a 4-byte Adler-32 trailer.
            iterations = 1000 if len(data) >= 64 * 1024 else 100
            return _zopfli.compress(
                data, numiterations=iterations, blocksplittingmax=0
            )[2:-4]
        stream = zlib.compressobj(9, zlib.DEFLATED, -15)
        return stream.compress(data) + stream.flush()


def _digest(entries: list[tuple[zipfile.ZipInfo, bytes]]) -> str:
    checksum = hashlib.sha256()
    for info, data in entries:
        checksum.update(info.filename.encode("utf-8"))
        checksum.update(b"\0")
        checksum.update(data)
    return checksum.hexdigest()


def _copy_info(source: zipfile.ZipInfo) -> zipfile.ZipInfo:
    target = zipfile.ZipInfo(source.filename, source.date_time)
    target.comment = source.comment
    target.create_system = source.create_system
    target.create_version = source.create_version
    target.extract_version = source.extract_version
    target.external_attr = source.external_attr
    target.internal_attr = source.internal_attr
    target.flag_bits = source.flag_bits & 0x800  # Preserve only the UTF-8 name bit.
    # resources.arsc must stay uncompressed so Android can memory-map it.  Every
    # other current entry, including the JPEG launcher icon, is safe to deflate.
    target.compress_type = (
        zipfile.ZIP_STORED
        if source.filename == "resources.arsc"
        else zipfile.ZIP_DEFLATED
    )
    return target


def repack(apk: Path) -> tuple[int, int, str]:
    before = apk.stat().st_size
    with zipfile.ZipFile(apk, "r") as source:
        entries = [(info, source.read(info)) for info in source.infolist()]
    expected = _digest(entries)

    fd, raw_temp = tempfile.mkstemp(prefix="lanzou-repack-", suffix=".apk", dir=apk.parent)
    os.close(fd)
    temp = Path(raw_temp)
    original_compressor = zipfile._get_compressor
    try:
        zipfile._get_compressor = lambda method, level=None: (  # type: ignore[attr-defined]
            _BufferedDeflater() if method == zipfile.ZIP_DEFLATED else None
        )
        with zipfile.ZipFile(temp, "w", allowZip64=False) as output:
            for info, data in entries:
                output.writestr(_copy_info(info), data)
        with zipfile.ZipFile(temp, "r") as check:
            actual_entries = [(info, check.read(info)) for info in check.infolist()]
        actual = _digest(actual_entries)
        if actual != expected:
            raise RuntimeError("repacked APK content verification failed")
        after = temp.stat().st_size
        if after >= before:
            temp.unlink(missing_ok=True)
            return before, before, "unchanged"
        os.replace(temp, apk)
        return before, after, "zopfli" if _zopfli is not None else "zlib-9"
    finally:
        zipfile._get_compressor = original_compressor  # type: ignore[attr-defined]
        temp.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    before, after, mode = repack(args.apk.resolve())
    print(f"lossless-apk-repack mode={mode} bytes={before}->{after} saved={before-after}")


if __name__ == "__main__":
    main()
