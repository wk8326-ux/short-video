from app.faststart import inspect_mp4_prefix


def _box(kind: bytes, payload: bytes = b"") -> bytes:
    return (8 + len(payload)).to_bytes(4, "big") + kind + payload


def test_fast_start_when_moov_precedes_media_data():
    data = _box(b"ftyp", b"isom") + _box(b"moov") + _box(b"mdat")

    assert inspect_mp4_prefix(data) == "optimized"


def test_not_optimized_when_media_data_precedes_moov():
    data = _box(b"ftyp", b"isom") + _box(b"mdat") + _box(b"moov")

    assert inspect_mp4_prefix(data) == "not_optimized"


def test_inconclusive_when_prefix_ends_inside_padding():
    data = _box(b"ftyp", b"isom") + (4096).to_bytes(4, "big") + b"free" + b"partial"

    assert inspect_mp4_prefix(data) == "inconclusive"


def test_invalid_box_size_is_inconclusive():
    data = _box(b"ftyp", b"isom") + (4).to_bytes(4, "big") + b"moov"

    assert inspect_mp4_prefix(data) == "inconclusive"
