"""Settings ingestion: schema, validation, screenshot extraction, client-side decrypt.

Public surface:
    SETTINGS, validate, ValidatedSettings, extract_settings
The AAPS encrypted-prefs decryptor is browser/Node JS under `decrypt/` (the master
password must never reach a server), not Python.
"""

from .extract import extract_settings
from .schema import SETTINGS, SettingSpec, resolve_alias
from .validate import SettingIssue, ValidatedSettings, validate

__all__ = [
    "SETTINGS",
    "SettingSpec",
    "resolve_alias",
    "validate",
    "ValidatedSettings",
    "SettingIssue",
    "extract_settings",
]
