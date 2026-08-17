from app.media_metadata import mp4_duration_seconds


def _mvhd_v0(timescale: int, duration: int) -> bytes:
    payload = (
        b"\x00\x00\x00\x00"
        + (0).to_bytes(4, "big")
        + (0).to_bytes(4, "big")
        + timescale.to_bytes(4, "big")
        + duration.to_bytes(4, "big")
    )
    return (8 + len(payload)).to_bytes(4, "big") + b"mvhd" + payload


def _mvhd_v1(timescale: int, duration: int) -> bytes:
    payload = (
        b"\x01\x00\x00\x00"
        + (0).to_bytes(8, "big")
        + (0).to_bytes(8, "big")
        + timescale.to_bytes(4, "big")
        + duration.to_bytes(8, "big")
    )
    return (8 + len(payload)).to_bytes(4, "big") + b"mvhd" + payload


def test_reads_v0_duration_from_partial_mp4():
    assert mp4_duration_seconds(b"prefix" + _mvhd_v0(1000, 179_500)) == 179.5


def test_reads_v1_duration_from_tail_chunk():
    assert mp4_duration_seconds(b"prefix", b"tail" + _mvhd_v1(48_000, 9_600_000)) == 200


def test_rejects_invalid_or_missing_duration():
    assert mp4_duration_seconds(b"not-an-mp4") is None
    assert mp4_duration_seconds(_mvhd_v0(0, 10)) is None
