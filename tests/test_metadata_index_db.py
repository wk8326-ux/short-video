"""The folded catalogue's storage contract.

The matching rules live in ``shared_metadata``; what this file pins is the
storage those rules now sit on -- that a key reads back exactly like the dict
entry it replaced, and that the tie-breaker keeps its ordering once it is a
single integer in a database column.
"""

import pytest

from app.metadata_index_db import (
    CODE_TABLE,
    NAME_TABLE,
    PATH_TABLE,
    CatalogueIndexBuilder,
    FoldedKeyTable,
    PreparedRow,
)
from app.shared_metadata import pack_rank


def _row(payload: dict, **overrides) -> PreparedRow:
    values = {
        "identity": "abp485",
        "name_key": "",
        "code_keys": (),
        "path_keys": (),
        "rank": 0,
    }
    values.update(overrides)
    return PreparedRow(payload=payload, **values)


def _publish(tmp_path, rows):
    builder = CatalogueIndexBuilder(tmp_path / "index.db")
    builder.add_many(rows)
    return builder.publish(version="v1", fetched_at=1.0, rows=len(rows))


def test_pack_rank_orders_the_way_the_tuple_did():
    assert pack_rank((1, 0, 0, 0)) > pack_rank((0, 1, 1, 1))
    assert pack_rank((1, 1, 0, 0)) > pack_rank((1, 0, 1, 1))
    assert pack_rank((1, 0, 0, 0)) == pack_rank((1, 0, 0, 0))
    assert pack_rank(()) == 0


def test_a_published_mirror_reads_back_like_the_dicts_it_replaced(tmp_path):
    mirror = _publish(
        tmp_path,
        [
            _row(
                {"code": "ABP-485", "title": "有封面"},
                code_keys=("abp485",),
                path_keys=("关键词/abp-485.mp4",),
            )
        ],
    )
    names = FoldedKeyTable(mirror, NAME_TABLE)
    codes = FoldedKeyTable(mirror, CODE_TABLE)
    paths = FoldedKeyTable(mirror, PATH_TABLE)

    assert codes["abp485"]["title"] == "有封面"
    assert paths["关键词/abp-485.mp4"]["code"] == "ABP-485"
    assert "abp485" in codes
    assert "nope" not in codes
    assert codes.get("nope") is None
    assert len(codes) == 1
    # No row was given a folder name, so that table stayed empty.
    assert len(names) == 0
    with pytest.raises(KeyError):
        names["abp485"]
    mirror.close()


def test_a_key_claimed_by_two_identities_is_dropped_for_good(tmp_path):
    """A tie may not be broken by a later row coming back to the same key."""
    rows = [
        _row(
            {"code": "ABP-485"},
            name_key="共享",
            identity="abp485",
            code_keys=("abp485",),
        ),
        _row(
            {"code": "KCPN-054"},
            name_key="共享",
            identity="kcpn054",
            code_keys=("kcpn054",),
        ),
        # The first release is filed again: the key must stay withdrawn.
        _row(
            {"code": "ABP-485"},
            name_key="共享",
            identity="abp485",
            code_keys=("abp485",),
        ),
    ]
    mirror = _publish(tmp_path, rows)

    assert len(FoldedKeyTable(mirror, NAME_TABLE)) == 0
    # Both releases are still reachable by their own code.
    assert len(FoldedKeyTable(mirror, CODE_TABLE)) == 2
    mirror.close()


def test_the_row_with_more_of_the_release_wins_the_tie(tmp_path):
    """Multi-part releases repeat a key; the row carrying artwork has to win."""
    thin = {"code": "ABP-485", "title": "没有封面"}
    rich = {"code": "ABP-485", "title": "有封面", "poster_url": "/poster?x"}
    mirror = _publish(
        tmp_path,
        [
            _row(rich, code_keys=("abp485",), rank=pack_rank((1, 0, 0, 0))),
            _row(thin, code_keys=("abp485",), rank=pack_rank((0, 0, 0, 0))),
        ],
    )

    assert FoldedKeyTable(mirror, CODE_TABLE)["abp485"]["title"] == "有封面"
    mirror.close()


def test_a_build_that_folds_nothing_publishes_no_mirror(tmp_path):
    """An empty catalogue must not be allowed to replace a working file."""
    path = tmp_path / "index.db"
    builder = CatalogueIndexBuilder(path)
    builder.add_many([_row({"code": "ABP-485"})])

    assert builder.publish(version="v1", fetched_at=1.0, rows=1) is None
    assert not path.exists()
    # The temporary build is cleaned up with it.
    assert list(tmp_path.glob("*.tmp")) == []


def test_a_walk_that_fails_leaves_no_temporary_file_behind(tmp_path):
    path = tmp_path / "index.db"
    builder = CatalogueIndexBuilder(path)
    builder.add_many([_row({"code": "ABP-485"}, code_keys=("abp485",))])
    with pytest.raises(RuntimeError):
        with builder:
            raise RuntimeError("the catalogue died mid-walk")

    assert not path.exists()
    assert list(tmp_path.glob("*.tmp")) == []
