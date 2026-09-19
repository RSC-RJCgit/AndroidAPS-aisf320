"""Add the delayed-bolus 'Db' check-note codes to the Full and Abbreviated CarePortal note-code reference docs.

Sources are the 19 Sep DRAFT docs (which already carry the 7 spliced codes). Writes NEW files stamped with the
current time (never overwrites). Codes come from DelayedBolusWorker.kt / BolusWizard.kt (19 Sep 2026)."""
import copy
from datetime import datetime
from pathlib import Path

from docx import Document
from docx.table import _Row
from docx.text.paragraph import Paragraph

AAA = Path(r"C:\winword\aaa")
SRC_FULL = AAA / "AutoISF CarePortal Note Code Full Reference mydoc Sep 19 26 DRAFT updates.docx"
SRC_SHRINK = AAA / "AutoISF CarePortal Note Code Abbreviated Reference mydoc Sep 19 26 DRAFT updates.docx"
stamp = f"{datetime.now():%H%M}"
OUT_FULL = AAA / f"AutoISF CarePortal Note Code Full Reference mydoc Sep 19 26 DRAFT updates v2 {stamp}.docx"
OUT_SHRINK = AAA / f"AutoISF CarePortal Note Code Abbreviated Reference mydoc Sep 19 26 DRAFT updates v2 {stamp}.docx"

ANCHOR_BEFORE = "DelOf"  # new rows land immediately before this alphabetical neighbour

FULL_ENTRIES = [
    ("Db0", "Db0",
     "Delayed-bolus onset: written by the wizard when the automatic delayed bolus is scheduled (50% profile, "
     "recent-50, or the Walking soon checkbox). SMBs are blocked for 85 minutes or until the sequence ends."),
    ("DbN", "DbN (N=5\u201380, step 5)",
     "Delayed-bolus check N minutes after the wizard bolus (one check every 5 minutes, up to 80). Suffixes: "
     "\u2018wait\u2019 = criteria not met yet (BG>5.0, delta>0.10, short-avg>0.10 and long-avg>0 mmol/L all "
     "required); \u2018<dose>U\u2019 (e.g. Db40 0.65U) = delayed dose delivered; \u2018covered\u2019 = nothing "
     "further needed (0.00U after the COB scale / live InsReq cap), sequence ends; \u2018end\u2019 = 80 minutes "
     "reached without a dose; \u2018Live steps unavailable\u2019 = mirrored steps missing, no automatic dose. "
     "Before 19 Sep 2026 these were Db10\u2026Db80 in 10-minute steps."),
]

SHRINK_ENTRIES = [
    ("Db0", "Db0", "Delayed-bolus onset (SMBs blocked 85 min)"),
    ("DbN", "DbN (N=5\u201380)", "Delayed-bolus check at +N min (5-min steps): wait / <dose>U / covered / end"),
]


def set_cell_text(cell, text):
    for p in cell.paragraphs[1:]:
        p._p.getparent().remove(p._p)
    p0 = cell.paragraphs[0]
    runs = p0.runs
    if runs:
        runs[0].text = text
        for extra in runs[1:]:
            extra._r.getparent().remove(extra._r)
    else:
        p0.add_run(text)


def full():
    doc = Document(SRC_FULL)
    table = doc.tables[0]
    anchor = next(r for r in table.rows if r.cells[0].text.strip() == ANCHOR_BEFORE)
    for code, display, desc in FULL_ENTRIES:
        new_tr = copy.deepcopy(anchor._tr)
        anchor._tr.addprevious(new_tr)
        row = _Row(new_tr, table)
        for cell, text in zip(row.cells[:3], [code, f"\u2022 {display}", desc]):
            set_cell_text(cell, text)
    doc.save(OUT_FULL)


def shrink():
    doc = Document(SRC_SHRINK)
    anchor = next(p for p in doc.paragraphs if p.text.split("\t", 1)[0].strip() == ANCHOR_BEFORE)
    for code, display, desc in SHRINK_ENTRIES:
        elem = copy.deepcopy(anchor._p)
        anchor._p.addprevious(elem)
        para = Paragraph(elem, anchor._parent)
        text = f"{code}\t{display}\t{desc}"
        if para.runs:
            para.runs[0].text = text
            for extra in para.runs[1:]:
                extra._r.getparent().remove(extra._r)
        else:
            para.add_run(text)
    doc.save(OUT_SHRINK)


full()
shrink()
print(OUT_FULL)
print(OUT_SHRINK)
