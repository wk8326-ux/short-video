from app.settings import Settings


def test_direct_url_cache_defaults_to_ten_minutes(monkeypatch):
    monkeypatch.delenv("DIRECT_URL_CACHE_SECONDS", raising=False)

    assert Settings.from_env().direct_url_cache_seconds == 600


def test_direct_url_cache_can_be_overridden(monkeypatch):
    monkeypatch.setenv("DIRECT_URL_CACHE_SECONDS", "300")

    assert Settings.from_env().direct_url_cache_seconds == 300


def test_asmr_and_duration_defaults(monkeypatch):
    monkeypatch.delenv("ASMR_BASE_URL", raising=False)
    monkeypatch.delenv("ASMR_MEDIA_PATH", raising=False)
    monkeypatch.delenv("DURATION_BOUNDARY_SECONDS", raising=False)

    settings = Settings.from_env()

    assert settings.asmr_base_url == "https://www.asmrgay.com"
    assert settings.asmr_media_path == "/asmr6"
    assert settings.duration_boundary_seconds == 180
    assert settings.metadata_probe_batch_size == 30
