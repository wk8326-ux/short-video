from __future__ import annotations


def mp4_duration_seconds(*chunks: bytes) -> float | None:
    """Read the movie duration from an mvhd box found in a partial MP4 response."""
    for data in chunks:
        offset = 0
        while True:
            marker = data.find(b"mvhd", offset)
            if marker < 0:
                break
            offset = marker + 4
            box_start = marker - 4
            if box_start < 0:
                continue
            box_size = int.from_bytes(data[box_start:marker], "big")
            version_offset = marker + 4
            if version_offset >= len(data):
                continue
            version = data[version_offset]
            if version == 0:
                timescale_offset = marker + 16
                duration_offset = marker + 20
                duration_size = 4
            elif version == 1:
                timescale_offset = marker + 24
                duration_offset = marker + 28
                duration_size = 8
            else:
                continue
            required = duration_offset + duration_size
            if box_size < required - box_start or required > len(data):
                continue
            timescale = int.from_bytes(data[timescale_offset : timescale_offset + 4], "big")
            duration = int.from_bytes(data[duration_offset:required], "big")
            if timescale <= 0 or duration <= 0:
                continue
            seconds = duration / timescale
            if 0 < seconds <= 7 * 24 * 60 * 60:
                return seconds
    return None
