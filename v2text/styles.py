"""Writing styles, picked per app (like Flow's Styles feature)."""
from __future__ import annotations

STYLES: dict[str, str] = {
    "default": "Clear, natural writing with standard capitalization and punctuation.",
    "formal": "Professional tone. Full sentences, proper capitalization and punctuation. "
              "Suitable for email and documents. Do not add greetings or sign-offs that were not spoken.",
    "casual": "Friendly, conversational tone. Normal capitalization, light punctuation. "
              "Keep contractions.",
    "very_casual": "Chat-message style: lowercase is fine, minimal punctuation, no trailing period "
                   "on single sentences. Keep it short and natural.",
    "technical": "Technical writing for code editors and terminals. Preserve identifiers, file names, "
                 "CLI commands and symbols exactly; format code-like tokens in `backticks` only if the "
                 "speaker clearly referred to code. No extra prose.",
}


def style_for_app(app: str | None, app_styles: dict[str, str], default: str = "default") -> str:
    if app:
        a = app.lower()
        for key, style in app_styles.items():
            if key.lower() in a and style in STYLES:
                return style
    return default if default in STYLES else "default"
