"""Settings, personal dictionary and snippets, persisted as JSON.

Default location: ~/.config/v2text/config.json (override with V2TEXT_HOME).
Privacy-first defaults: offline only, no AI cleanup, no context reading,
audio kept only when a transcription fails (so it can be retried).
"""
from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path


def app_home() -> Path:
    return Path(os.environ.get("V2TEXT_HOME", Path.home() / ".config" / "v2text"))


DEFAULT_APP_STYLES = {
    # substring of active app / window title (lowercase) -> style name
    "slack": "casual", "teams": "casual", "discord": "very_casual",
    "whatsapp": "very_casual", "telegram": "very_casual", "messages": "very_casual",
    "mail": "formal", "outlook": "formal", "gmail": "formal", "thunderbird": "formal",
    "word": "formal", "docs": "formal",
    "code": "technical", "cursor": "technical", "terminal": "technical", "pycharm": "technical",
}

# Never read context from, or paste into, these (password managers, banking).
DEFAULT_BLOCKED_APPS = ["keepass", "1password", "bitwarden", "lastpass", "dashlane",
                        "keychain", "netbanking", "bank"]

STARTER_DICTIONARY = [
    {"term": "LNG", "aliases": ["l n g", "el en gee"], "category": "domain"},
    {"term": "UGEL", "aliases": ["u gel", "you gel"], "category": "domain"},
    {"term": "Retail Outlet", "aliases": [], "category": "domain"},
    {"term": "Command Centre", "aliases": ["command center"], "category": "domain"},
]


@dataclass
class Config:
    # --- hotkeys (pynput key names) ---
    hotkey: str = "alt_r"            # hold to dictate; double-tap to lock hands-free
    command_hotkey: str = "ctrl_r"   # hold with text selected to edit it by voice (AI mode only)
    double_tap_window: float = 0.35  # seconds

    # --- engine routing ---
    offline_only: bool = True        # hard guarantee: nothing leaves the machine
    ai_cleanup: bool = False         # optional Claude pass on the *text* (never audio)
    claude_model: str = "claude-opus-5-5"
    effort: str = "low"

    # --- speech-to-text (local faster-whisper) ---
    whisper_model: str = "small"     # multilingual; benchmark tiny/base/small/medium on your machine
    whisper_device: str = "auto"
    language: str = "en-IN"          # en-IN | hi | hinglish | gu | auto (see languages.py)

    # --- audio capture ---
    sample_rate: int = 16000
    warm_mic: bool = False           # keep mic stream open for a pre-roll buffer (mic indicator stays on)
    preroll_ms: int = 400            # used only when warm_mic is on
    tail_ms: int = 300               # keep recording briefly after release so the last word survives
    min_seconds: float = 0.4

    # --- text processing ---
    remove_fillers: bool = True
    format_lists: bool = True
    self_corrections: bool = True

    # --- behaviour / privacy ---
    context_awareness: bool = False  # opt-in: read active app name to pick a style
    default_style: str = "default"
    app_styles: dict[str, str] = field(default_factory=lambda: dict(DEFAULT_APP_STYLES))
    blocked_apps: list[str] = field(default_factory=lambda: list(DEFAULT_BLOCKED_APPS))
    restore_clipboard: bool = True
    save_history: bool = True
    audio_retention: str = "on_failure"  # never | on_failure | always
    audio_retention_days: int = 14

    # --- personalisation ---
    dictionary: list[dict] = field(default_factory=lambda: [dict(d) for d in STARTER_DICTIONARY])
    snippets: dict[str, str] = field(default_factory=dict)  # spoken trigger -> expansion

    # ------------------------------------------------------------------
    @property
    def ai_enabled(self) -> bool:
        return self.ai_cleanup and not self.offline_only

    def dictionary_terms(self) -> list[str]:
        return [d["term"] for d in self.dictionary if d.get("term")]

    def alias_map(self) -> dict[str, str]:
        return {a: d["term"] for d in self.dictionary for a in d.get("aliases", []) if a}

    @classmethod
    def path(cls) -> Path:
        return app_home() / "config.json"

    @classmethod
    def load(cls) -> "Config":
        p = cls.path()
        if not p.exists():
            return cls()
        data = json.loads(p.read_text(encoding="utf-8"))
        known = {k: v for k, v in data.items() if k in cls.__dataclass_fields__}
        return cls(**known)

    def save(self) -> Path:
        p = self.path()
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(asdict(self), indent=2, ensure_ascii=False), encoding="utf-8")
        return p
