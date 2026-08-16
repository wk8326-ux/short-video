from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import hmac
import json
import os
import secrets
import stat
import threading
import time
from collections import deque
from dataclasses import dataclass
from pathlib import Path

PASSWORD_SCHEME = "scrypt"
SCRYPT_N = 1 << 14
SCRYPT_R = 8
SCRYPT_P = 1
SCRYPT_DKLEN = 32
SCRYPT_MAXMEM = 64 * 1024 * 1024
SESSION_COOKIE = "short_session"


def _encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def _decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def hash_password(password: str) -> str:
    if len(password) < 10:
        raise ValueError("Password must contain at least 10 characters")
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(
        password.encode(),
        salt=salt,
        n=SCRYPT_N,
        r=SCRYPT_R,
        p=SCRYPT_P,
        dklen=SCRYPT_DKLEN,
        maxmem=SCRYPT_MAXMEM,
    )
    return ":".join(
        (PASSWORD_SCHEME, str(SCRYPT_N), str(SCRYPT_R), str(SCRYPT_P), _encode(salt), _encode(digest))
    )


def verify_password(encoded: str, candidate: str) -> bool:
    try:
        scheme, raw_n, raw_r, raw_p, raw_salt, raw_digest = encoded.split(":")
        n, r, p = int(raw_n), int(raw_r), int(raw_p)
        if scheme != PASSWORD_SCHEME or not (1 << 12) <= n <= (1 << 18):
            return False
        if not 1 <= r <= 16 or not 1 <= p <= 4:
            return False
        salt, expected = _decode(raw_salt), _decode(raw_digest)
        if not 8 <= len(salt) <= 64 or not 16 <= len(expected) <= 64:
            return False
        actual = hashlib.scrypt(
            candidate.encode(),
            salt=salt,
            n=n,
            r=r,
            p=p,
            dklen=len(expected),
            maxmem=SCRYPT_MAXMEM,
        )
        return hmac.compare_digest(actual, expected)
    except (ValueError, TypeError):
        return False


class SessionManager:
    def __init__(self, secret: str, password_hash: str, max_age_seconds: int):
        self._secret = secret.encode()
        self._version = hashlib.sha256(password_hash.encode()).hexdigest()[:16]
        self.max_age_seconds = max_age_seconds

    def issue(self, now: int | None = None) -> str:
        issued_at = int(time.time()) if now is None else now
        payload = json.dumps(
            {
                "exp": issued_at + self.max_age_seconds,
                "iat": issued_at,
                "nonce": secrets.token_hex(8),
                "v": self._version,
            },
            separators=(",", ":"),
            sort_keys=True,
        ).encode()
        signature = hmac.digest(self._secret, payload, "sha256")
        return f"{_encode(payload)}.{_encode(signature)}"

    def verify(self, token: str | None, now: int | None = None) -> bool:
        if not token:
            return False
        try:
            raw_payload, raw_signature = token.split(".", 1)
            payload = _decode(raw_payload)
            signature = _decode(raw_signature)
            expected = hmac.digest(self._secret, payload, "sha256")
            if not hmac.compare_digest(signature, expected):
                return False
            data = json.loads(payload)
            current = int(time.time()) if now is None else now
            return (
                isinstance(data, dict)
                and data.get("v") == self._version
                and int(data.get("iat", 0)) <= current
                and current < int(data.get("exp", 0))
            )
        except (ValueError, TypeError, json.JSONDecodeError):
            return False


@dataclass
class _AttemptState:
    failures: deque[float]
    blocked_until: float = 0
    last_seen: float = 0


class LoginRateLimiter:
    def __init__(self, max_failures: int = 5, window_seconds: int = 300, block_seconds: int = 300):
        self.max_failures = max_failures
        self.window_seconds = window_seconds
        self.block_seconds = block_seconds
        self._states: dict[str, _AttemptState] = {}
        self._lock = threading.Lock()

    def retry_after(self, key: str, now: float | None = None) -> int:
        current = time.monotonic() if now is None else now
        with self._lock:
            state = self._states.get(key)
            if not state:
                return 0
            state.last_seen = current
            self._trim(state, current)
            return max(0, int(state.blocked_until - current + 0.999))

    def record_failure(self, key: str, now: float | None = None) -> int:
        current = time.monotonic() if now is None else now
        with self._lock:
            state = self._states.setdefault(key, _AttemptState(deque()))
            state.last_seen = current
            self._trim(state, current)
            state.failures.append(current)
            if len(state.failures) >= self.max_failures:
                state.failures.clear()
                state.blocked_until = current + self.block_seconds
            self._prune(current)
            return max(0, int(state.blocked_until - current + 0.999))

    def record_success(self, key: str) -> None:
        with self._lock:
            self._states.pop(key, None)

    def _trim(self, state: _AttemptState, now: float) -> None:
        while state.failures and state.failures[0] <= now - self.window_seconds:
            state.failures.popleft()
        if state.blocked_until <= now:
            state.blocked_until = 0

    def _prune(self, now: float) -> None:
        if len(self._states) <= 2048:
            return
        stale_before = now - max(self.window_seconds, self.block_seconds) * 2
        for key, state in list(self._states.items()):
            if state.last_seen < stale_before and state.blocked_until <= now:
                self._states.pop(key, None)


def _update_env(path: Path, values: dict[str, str]) -> None:
    existing = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    remaining = dict(values)
    output: list[str] = []
    for line in existing:
        key = line.split("=", 1)[0].strip() if "=" in line else ""
        if key in remaining:
            output.append(f"{key}={remaining.pop(key)}")
        else:
            output.append(line)
    if output and output[-1]:
        output.append("")
    output.extend(f"{key}={value}" for key, value in remaining.items())

    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_text("\n".join(output).rstrip() + "\n", encoding="utf-8")
    os.chmod(temporary, stat.S_IRUSR | stat.S_IWUSR)
    os.replace(temporary, path)


def _configure(path: Path, password: str, *, initialize: bool) -> None:
    current = path.read_text(encoding="utf-8") if path.exists() else ""
    current_values = {
        line.split("=", 1)[0].strip(): line.split("=", 1)[1].strip()
        for line in current.splitlines()
        if "=" in line and not line.lstrip().startswith("#")
    }
    if initialize and (current_values.get("AUTH_PASSWORD_HASH") or current_values.get("SESSION_SECRET")):
        raise SystemExit("Authentication is already configured")
    values = {"AUTH_PASSWORD_HASH": hash_password(password)}
    if initialize or not current_values.get("SESSION_SECRET"):
        values["SESSION_SECRET"] = secrets.token_urlsafe(48)
    values["SESSION_DAYS"] = "180"
    _update_env(path, values)


def _main() -> None:
    parser = argparse.ArgumentParser(description="Configure short-video authentication")
    parser.add_argument("command", choices=("init-env", "set-password"))
    parser.add_argument("env_file", type=Path)
    args = parser.parse_args()

    if args.command == "init-env":
        password = secrets.token_urlsafe(12)
        _configure(args.env_file, password, initialize=True)
        print(f"Initial password: {password}")
        return

    password = getpass.getpass("New password: ")
    if password != getpass.getpass("Confirm password: "):
        raise SystemExit("Passwords do not match")
    _configure(args.env_file, password, initialize=False)
    print("Password updated. Restart the container to invalidate existing sessions.")


if __name__ == "__main__":
    _main()
