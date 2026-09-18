import re
from pathlib import Path

ROOT = Path(r"C:\Users\arjay\StudioProjects\AaAPS3422a320")
PLUGIN = ROOT / "plugins" / "aps" / "src" / "main" / "kotlin" / "app" / "aaps" / "plugins" / "aps" / "openAPSAutoISF" / "OpenAPSAutoISFPlugin.kt"
COMPLETE_DOC_TEXT = Path(r"C:\Users\arjay\AppData\Local\Temp\claude\C--Users-arjay-StudioProjects-AaAPS3422a320\f8cff662-0f5f-4df7-b9d0-827e97ca90dd\scratchpad\cpn_complete_plain.txt")

text = PLUGIN.read_text(encoding="utf-8")

# 1) Literal addCarePortalNote("...") calls, including ones with ${...} interpolation --
# take everything up to the first ${ or the closing quote, whichever comes first.
codes = set()
for m in re.finditer(r'addCarePortalNote\(\s*"((?:[^"\\]|\\.)*)', text):
    raw = m.group(1)
    prefix = raw.split("${")[0].split("$")[0]
    if prefix:
        codes.add(prefix)

# 2) compactSettingNote("XX", ...) prefixes
for m in re.finditer(r'compactSettingNote\(\s*"([^"]+)"', text):
    codes.add(m.group(1))

# 3) String.format(...) based codes passed directly to addCarePortalNote
for m in re.finditer(r'addCarePortalNote\(\s*String\.format\([^,]+,\s*"([^"]+)"', text):
    codes.add(m.group(1).split("%")[0])

doc_text = COMPLETE_DOC_TEXT.read_text(encoding="utf-8")

missing = sorted(c for c in codes if c and c not in doc_text)
present = sorted(c for c in codes if c and c in doc_text)

print(f"Total distinct code/prefixes extracted from source: {len(codes)}")
print(f"Present in complete doc: {len(present)}")
print(f"MISSING from complete doc ({len(missing)}):")
for c in missing:
    print(" ", repr(c))
