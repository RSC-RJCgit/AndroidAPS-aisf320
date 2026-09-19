"""Bring the Operations FULL Manual's delayed-bolus text in line with DelayedBolusWorker.kt (19 Sep 2026):
5-minute checks (+5..+80), gate BG>5.0 / D>0.10 / SD>0.10 / LD>0, COB-scaled remainder capped by live InsReq,
100% seated / 80% still moving (no elapsed-time 90/50 haircut), Db5..Db80 check markers.

Writes a NEW dated/versioned file next to the source (never overwrites v26). Raw document.xml edit because
python-docx cannot open this manual (dangling '#img_history_basal' part reference)."""
import re
import shutil
import zipfile
from datetime import datetime
from pathlib import Path

SRC = Path(r"C:\winword\aaa\AutoISF Operations FULL Manual revised Thursday, September 17th, 2026 mydoc v26 0913 fully sorted careportal tables.docx")
now = datetime.now()
OUT = SRC.with_name(
    f"AutoISF Operations FULL Manual revised Saturday, September 19th, 2026 mydoc v27 {now:%H%M} fully sorted careportal tables.docx"
)

x = zipfile.ZipFile(SRC).read("word/document.xml").decode("utf-8")


def sub_once(old, new, label):
    global x
    n = x.count(old)
    assert n == 1, (label, n)
    x = x.replace(old, new)
    print("ok:", label)


def para_text_replace(startswith_text, new_text, label):
    """Replace the single <w:t> text of the one paragraph whose text starts with startswith_text."""
    global x
    hits = [
        m for m in re.finditer(r"<w:t(?: [^>]*)?>([^<]*)</w:t>", x)
        if m.group(1).startswith(startswith_text)
    ]
    assert len(hits) == 1, (label, len(hits))
    m = hits[0]
    x = x[: m.start(1)] + new_text + x[m.end(1):]
    print("ok:", label)


# Glossary rows
para_text_replace("Db10, Db20, ...", "Db0, Db5, Db10, ... Db80", "glossary code row")
para_text_replace(
    "DelayedBolusWorker",
    "DelayedBolusWorker's check markers: Db0 = onset, then Db5, Db10, Db15 ... Db80 (elapsed minutes, one check every 5 minutes) "
    "with the suffix wait, &lt;dose&gt;U, covered, end or Live steps unavailable.",
    "glossary description row",
)

# Main delayed-bolus paragraph
para_text_replace(
    "Automatic delayed bolus at 50% or State recent-50. This is distinct from the manual split. After the initial",
    "Automatic delayed bolus at 50% or State recent-50. This is distinct from the manual split. After the initial "
    "Wizard/Quick Wizard bolus, the worker checks every 5 minutes from +5 through +80 minutes (16 checks) while "
    "temporarily blocking SMBs (85 minutes, released early on delivery, cancellation or give-up). It requires glucose "
    "data no more than five minutes old, BG above 5.0 mmol/L, current delta above +0.10 mmol/L, short-average delta "
    "above +0.10 mmol/L, and long-average delta above 0 mmol/L (all five together). When the criteria first pass, it "
    "takes the frozen original full-required amount minus the original delivered dose, scales that gap by the fraction "
    "of the entered carbs still on board (current COB divided by the original carbs), and caps the result at the "
    "loop's live insulin requirement (InsReq) when the loop has run in the last 10 minutes. A person who is still "
    "moving (S30 at least 200 or S5 at least 100) receives 80% of that amount; otherwise 100%. If the result rounds to "
    "0U the check is recorded as covered and the sequence ends. It does not recompute the original IOB snapshot and "
    "applies no elapsed-time 90%/50% haircut. Db5, Db10, Db15 ... Db80 check markers show wait, the dose delivered "
    "(for example Db40 0.65U), covered, end, or Live steps unavailable (no automatic dose when mirrored steps are "
    "missing); SMBs are unblocked on delivery, cancellation or final expiry. Before 19 September 2026 the checks were "
    "every 10 minutes (Db10 ... Db80) with a stricter gate (BG above 4.5, short-average delta 0.15 or BG above 5.5, "
    "long-average delta above 0.05).",
    "main delayed-bolus paragraph",
)

# Screenshot caption and Walking-soon paragraph
sub_once("10-min/8-attempt schedule", "5-min/16-attempt schedule", "caption schedule")
sub_once(
    "the same +10-to-+80-minute check and 90%/50% confirmation-timing split, topping up",
    "the same +5-to-+80-minute check (every 5 minutes), sized as described above (100% seated, 80% still moving), topping up",
    "walking-soon paragraph",
)

with zipfile.ZipFile(SRC) as zin, zipfile.ZipFile(OUT, "w") as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == "word/document.xml":
            data = x.encode("utf-8")
        zout.writestr(item, data, compress_type=item.compress_type)
print(OUT)
