"""21 Sep 2026: add StuckRisingSlowly description to the FULL manual (v30 -> v31).

New dated/versioned file only. Also drops the stale LoReb parenthetical that said
StuckRisingSlowly still reads LastLoRebAppliedAt.
"""
import re
import zipfile
from datetime import datetime
from html import escape
from pathlib import Path

AAA = Path(r"C:\winword\aaa")
stamp = f"{datetime.now():%H%M}"
SRC = AAA / "AutoISF Operations FULL Manual revised Sunday, September 20th, 2026 mydoc v30 2143 fully sorted careportal tables.docx"
OUT = AAA / (
    f"AutoISF Operations FULL Manual revised Monday, September 21st, 2026 mydoc v31 {stamp} "
    f"fully sorted careportal tables.docx"
)
x = zipfile.ZipFile(SRC).read("word/document.xml").decode("utf-8")
PARA_RE = re.compile(r"<w:p[ >].*?</w:p>", re.S)


def ptext(p):
    return "".join(re.findall(r"<w:t[^>]*>([^<]*)</w:t>", p))


def para_xml(lead, body, indent=True):
    ppr = '<w:pPr><w:ind w:left="720"/><w:jc w:val="both"/></w:pPr>' if indent else '<w:pPr><w:jc w:val="both"/></w:pPr>'
    return (
        f"<w:p>{ppr}<w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">{escape(lead, quote=False)} </w:t></w:r>"
        f"<w:r><w:t xml:space=\"preserve\">{escape(body, quote=False)}</w:t></w:r></w:p>"
    )


def insert_after_prefix(prefix, new_xml, label, which="all"):
    global x
    hits = [m for m in PARA_RE.finditer(x) if ptext(m.group(0)).startswith(prefix)]
    assert hits, (label, "no hit")
    if which == "unique":
        assert len(hits) == 1, (label, len(hits))
    for m in reversed(hits):
        x = x[: m.end()] + new_xml + x[m.end():]
    print("ok:", label, f"({len(hits)} place(s))")


OLD = (
    "LastLoRebAppliedAt is not refreshed (only StuckRisingSlowly reads it, as a recent-event exclusion) "
    "and the RT reason"
)
NEW = "LastLoRebAppliedAt is not refreshed, and the RT reason"
assert x.count(OLD) == 1, x.count(OLD)
x = x.replace(OLD, NEW, 1)
print("ok: loreb stale parenthetical")

SRS = (
    "Coded port of the native StuckRisingSlowly screenshot: a 5-minute 4.2 mmol TT only "
    "(no SMB-ratio, profile switch or CarePortal note; SMS \u201cStuckRisingSlowly Acce\u201d). "
    "Window 08:00\u201301:00 (wraps midnight). Requires Steroids Off, no active TT, and a delayed "
    "bolus delivered in the last 60 minutes. Then BG 6.5\u20139.0 mmol, COB 0\u20138 g, IOB 1.2\u20135.5 U, "
    "S60 < 600, S180 < 1000, last normal bolus or last carbs at least 40 minutes ago, and delta / "
    "short-average / long-average all inside one shared 0.15\u20130.25, 0.20\u20130.30 or 0.25\u20130.35 mmol "
    "band (not mixed across bands). Own 5-minute throttle. Distinct from HighDaytimeBrake "
    "(plateaued high, 4.0 mmol TT). The LoReb last-30-min stamp is not a gate."
)
insert_after_prefix(
    "Updated 19 Sep 2026. HighDaytimeBrake and HighEveNightBrake reworked",
    para_xml("StuckRisingSlowly (updated 21 Sep 2026).", SRS),
    "stuckrising",
    "unique",
)

CHANGELOG = (
    "StuckRisingSlowly described in the body (not only the automations list): 08:00\u201301:00, "
    "BG 6.5\u20139.0, delayed bolus in the last hour, 4.2 mmol TT for 5 min; LoReb stamp dropped as a gate."
)
insert_after_prefix(
    "2026-09-20. Carb split rounding-leftover loop fixed",
    para_xml("2026-09-21.", CHANGELOG, indent=False),
    "changelog",
    "unique",
)

with zipfile.ZipFile(SRC) as zin, zipfile.ZipFile(OUT, "w") as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == "word/document.xml":
            data = x.encode("utf-8")
        zout.writestr(item, data, compress_type=item.compress_type)
print(OUT)
