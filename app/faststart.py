from __future__ import annotations

import struct


def inspect_mp4_prefix(data: bytes) -> str:
    """Return whether the MP4 metadata atom is available before media data."""
    offset = 0
    while offset + 8 <= len(data):
        size = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4 : offset + 8]
        header_size = 8

        if size == 1:
            if offset + 16 > len(data):
                return "inconclusive"
            size = struct.unpack_from(">Q", data, offset + 8)[0]
            header_size = 16
        elif size == 0:
            size = len(data) - offset

        if size < header_size:
            return "inconclusive"
        if kind == b"moov":
            return "optimized"
        if kind == b"mdat":
            return "not_optimized"
        if offset + size > len(data):
            return "inconclusive"
        offset += size

    return "inconclusive"
