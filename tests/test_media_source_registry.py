from __future__ import annotations

import asyncio

from app.media_sources import MediaSourceRegistry, SourceRuntime


class FakeClient:
    def __init__(self) -> None:
        self.closed = False

    async def close(self) -> None:
        self.closed = True


def test_reload_keeps_a_retained_runtime_alive_until_scan_finishes() -> None:
    async def scenario() -> None:
        registry = MediaSourceRegistry.__new__(MediaSourceRegistry)
        old_client = FakeClient()
        new_client = FakeClient()
        old_runtime = SourceRuntime({}, old_client, None)  # type: ignore[arg-type]
        new_runtime = SourceRuntime({}, new_client, None)  # type: ignore[arg-type]
        registry._runtimes = {"source": old_runtime}

        registry.retain(old_runtime)
        registry._build_runtime = lambda _: asyncio.sleep(0, result=new_runtime)  # type: ignore[method-assign]
        await registry.reload_source("source")

        assert old_client.closed is False
        assert new_client.closed is False

        registry.release(old_runtime)
        await asyncio.sleep(0)

        assert old_client.closed is True
        assert new_client.closed is False

    asyncio.run(scenario())
