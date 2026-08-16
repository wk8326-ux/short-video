from app.auth import LoginRateLimiter, SessionManager, _configure, hash_password, verify_password


def test_password_hash_round_trip():
    encoded = hash_password("a-long-private-password")

    assert "a-long-private-password" not in encoded
    assert verify_password(encoded, "a-long-private-password") is True
    assert verify_password(encoded, "wrong-password") is False
    assert verify_password("malformed", "a-long-private-password") is False


def test_signed_session_is_persistent_and_tamper_evident():
    manager = SessionManager("s" * 48, "scrypt:first", max_age_seconds=100)
    token = manager.issue(now=1000)

    assert manager.verify(token, now=1099) is True
    assert manager.verify(token, now=1100) is False
    assert manager.verify(token + "x", now=1050) is False
    assert SessionManager("s" * 48, "scrypt:changed", 100).verify(token, now=1050) is False


def test_login_rate_limit_resets_after_success():
    limiter = LoginRateLimiter(max_failures=3, window_seconds=60, block_seconds=120)

    assert limiter.record_failure("client", now=10) == 0
    assert limiter.record_failure("client", now=11) == 0
    assert limiter.record_failure("client", now=12) == 120
    assert limiter.retry_after("client", now=13) == 119

    limiter.record_success("client")
    assert limiter.retry_after("client", now=13) == 0


def test_auth_environment_initialization_keeps_plaintext_out(tmp_path):
    env_file = tmp_path / ".env"
    env_file.write_text("ALIST_BASE_URL=https://alist.example\nAUTH_PASSWORD_HASH=\nSESSION_SECRET=\nSESSION_DAYS=180\n")

    _configure(env_file, "a-new-private-password", initialize=True)
    values = dict(line.split("=", 1) for line in env_file.read_text().splitlines() if "=" in line)

    assert values["ALIST_BASE_URL"] == "https://alist.example"
    assert verify_password(values["AUTH_PASSWORD_HASH"], "a-new-private-password")
    assert "a-new-private-password" not in env_file.read_text()
    assert len(values["SESSION_SECRET"]) >= 32
