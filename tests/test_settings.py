from app.settings import Settings


def test_direct_url_cache_defaults_to_ten_minutes(monkeypatch):
    monkeypatch.delenv("DIRECT_URL_CACHE_SECONDS", raising=False)

    assert Settings.from_env().direct_url_cache_seconds == 600


def test_direct_url_cache_can_be_overridden(monkeypatch):
    monkeypatch.setenv("DIRECT_URL_CACHE_SECONDS", "300")

    assert Settings.from_env().direct_url_cache_seconds == 300
