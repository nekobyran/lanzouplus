#!/usr/bin/env python3
"""Repack the unsigned release APK in place without changing Android identity.

The Android Gradle plugin uses zlib's default compression level and leaves JPEG
resources stored. This post-pack step changes ZIP compression. With --aapt2 it
also takes only the optimized resources.arsc from AAPT2's output, preserving the
original entry set, manifest, DEX, native libraries, and resource IDs.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
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
            # Keep the release-size pass bounded: this still beats zlib-9 on
            # Android APK payloads, but avoids multi-minute hangs during hotfixes.
            iterations = 120 if len(data) >= 64 * 1024 else 24
            return _zopfli.compress(
                data, numiterations=iterations, blocksplittingmax=0
            )[2:-4]
        stream = zlib.compressobj(9, zlib.DEFLATED, -15)
        return stream.compress(data) + stream.flush()


def _content(entries: list[tuple[zipfile.ZipInfo, bytes]]) -> list[tuple[str, bytes]]:
    return [(info.filename, data) for info, data in entries]


def _remove_owned_temp(path: Path, parent: Path, prefix: str) -> None:
    parent = parent.resolve()
    if not path.name or not path.is_absolute() or not parent.is_absolute():
        raise RuntimeError("Refusing to remove a non-absolute temporary APK")
    target = path.resolve()
    if target.parent != parent or not target.name.startswith(prefix):
        raise RuntimeError("Refusing to remove a temporary APK outside its allowed parent")
    target.unlink(missing_ok=True)


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
    # other current entry, including compact launcher artwork, is safe to deflate.
    target.compress_type = (
        zipfile.ZIP_STORED
        if source.filename == "resources.arsc"
        else zipfile.ZIP_DEFLATED
    )
    return target


def _aapt2_resource_table(aapt2: Path, apk: Path) -> bytes:
    allowed_parent = apk.resolve().parent
    fd, raw_temp = tempfile.mkstemp(
        prefix="lanzou-aapt2-", suffix=".apk", dir=allowed_parent
    )
    os.close(fd)
    optimized = Path(raw_temp)
    try:
        result = subprocess.run(
            [
                str(aapt2),
                "optimize",
                "--collapse-resource-names",
                "-o",
                str(optimized),
                str(apk.resolve()),
            ],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            detail = result.stderr.strip() or result.stdout.strip()
            raise RuntimeError(
                f"AAPT2 optimize failed with exit code {result.returncode}: {detail}"
            )
        with zipfile.ZipFile(optimized, "r") as archive:
            return archive.read("resources.arsc")
    finally:
        _remove_owned_temp(optimized, allowed_parent, "lanzou-aapt2-")


def repack(apk: Path, aapt2: Path | None = None) -> tuple[int, int, str]:
    apk = apk.resolve()
    allowed_parent = apk.parent
    before = apk.stat().st_size
    with zipfile.ZipFile(apk, "r") as source:
        entries = [(info, source.read(info)) for info in source.infolist()]
    if aapt2 is not None:
        if not any(info.filename == "resources.arsc" for info, _ in entries):
            raise RuntimeError("APK does not contain resources.arsc")
        resource_table = _aapt2_resource_table(aapt2, apk)
        entries = [
            (info, resource_table if info.filename == "resources.arsc" else data)
            for info, data in entries
        ]
    expected = _content(entries)

    fd, raw_temp = tempfile.mkstemp(
        prefix="lanzou-repack-", suffix=".apk", dir=allowed_parent
    )
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
        actual = _content(actual_entries)
        if actual != expected:
            raise RuntimeError("repacked APK content verification failed")
        after = temp.stat().st_size
        if after >= before:
            _remove_owned_temp(temp, allowed_parent, "lanzou-repack-")
            return before, before, "unchanged"
        os.replace(temp, apk)
        compressor = "zopfli" if _zopfli is not None else "zlib-9"
        mode = f"aapt2+{compressor}" if aapt2 is not None else compressor
        return before, after, mode
    finally:
        zipfile._get_compressor = original_compressor  # type: ignore[attr-defined]
        _remove_owned_temp(temp, allowed_parent, "lanzou-repack-")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument(
        "--aapt2",
        type=Path,
        help="AAPT2 executable used for --collapse-resource-names",
    )
    args = parser.parse_args()
    aapt2 = args.aapt2.resolve() if args.aapt2 is not None else None
    before, after, mode = repack(args.apk.resolve(), aapt2)
    print(f"lossless-apk-repack mode={mode} bytes={before}->{after} saved={before-after}")


if __name__ == "__main__":
    main()
