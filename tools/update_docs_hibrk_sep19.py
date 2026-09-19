"""19 Sep 2026: bring the manual's HighDaytimeBrake / HighEveNightBrake (HiBrk*) text and the Full CarePortal reference
in line with the plugin after today's brake rework. New dated/versioned files only (v28 and DRAFT v2 are kept).

Manual  v28 -> v29 : raw document.xml edit (python-docx cannot open this manual).
CarePortal Full v2 -> v3 : python-docx.  The Abbreviated reference needs no change (its HiBrk descriptions are short and
                           never described the old boost), so no new Abbreviated file is written."""
import glob
import re
import zipfile
from datetime import datetime
from html import escape
from pathlib import Path

from docx import Document

AAA = Path(r"C:\winword\aaa")
stamp = f"{datetime.now():%H%M}"

# ------------------------------------------------------------------------------------------------ manual
SRC = Path(sorted(glob.glob(str(AAA / "AutoISF Operations FULL Manual revised Saturday, September 19th, 2026 mydoc v28 * fully sorted careportal tables.docx")))[-1])
OUT = SRC.with_name(f"AutoISF Operations FULL Manual revised Saturday, September 19th, 2026 mydoc v29 {stamp} fully sorted careportal tables.docx")
x = zipfile.ZipFile(SRC).read("word/document.xml").decode("utf-8")
PARA_RE = re.compile(r"<w:p[ >].*?</w:p>", re.S)


def ptext(p):
    return "".join(re.findall(r"<w:t[^>]*>([^<]*)</w:t>", p))


def find_one(prefix, label):
    hits = [m for m in PARA_RE.finditer(x) if ptext(m.group(0)).startswith(prefix)]
    assert len(hits) == 1, (label, len(hits))
    return hits[0]


def replace_para_text(prefix, new_text, label):
    """Single-run rewrite of one paragraph, keeping its pPr and first run's rPr."""
    global x
    m = find_one(prefix, label)
    para = m.group(0)
    open_tag = re.match(r"<w:p\b[^>]*>", para).group(0)
    ppr = re.search(r"<w:pPr>.*?</w:pPr>", para, re.S)
    run = re.search(r"<w:r(?: [^>]*)?>.*?</w:r>", para, re.S).group(0)
    rpr = re.search(r"<w:rPr>.*?</w:rPr>", run, re.S)
    new = f'{open_tag}{ppr.group(0) if ppr else ""}<w:r>{rpr.group(0) if rpr else ""}<w:t xml:space="preserve">{escape(new_text, quote=False)}</w:t></w:r></w:p>'
    x = x[: m.start()] + new + x[m.end():]
    print("ok:", label)


def insert_after(prefix, new_para_xml, label):
    global x
    m = find_one(prefix, label)
    x = x[: m.end()] + new_para_xml + x[m.end():]
    print("ok:", label)


def para_xml(lead, body, indent=True):
    ppr = '<w:pPr><w:ind w:left="720"/><w:jc w:val="both"/></w:pPr>' if indent else '<w:pPr><w:jc w:val="both"/></w:pPr>'
    return (f"<w:p>{ppr}<w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">{escape(lead, quote=False)} </w:t></w:r>"
            f"<w:r><w:t xml:space=\"preserve\">{escape(body, quote=False)}</w:t></w:r></w:p>")


UPDATE_BODY = (
    "HighDaytimeBrake and HighEveNightBrake reworked after the 19 Sep 19:57 and 20:10 fires (BG 9.6 / 9.3, IOB 3.9 / 3.5, "
    "short-average delta -0.02 / -0.05, raw UKF15 -0.19 / -0.15) that preceded a low of 4.6 mmol/L at 21:27; a 10-day "
    "Client-AIV review of 132 distinct fires found 29 (21%) went below 5.0 mmol/L within 2.5 hours. This supersedes the "
    "description above and the 4 and 6 Sep updates. (1) Entry: Delta, SDelta and LDelta must all be above 0 (upper bounds "
    "unchanged: below 0.1, 0.1 and 0.3 mmol/L), raw UKF delta5 and delta15 must both be above 0, and live HP (hypo "
    "prediction, same formula as the AIV HP column) must be at least 6.5 mmol/L; a missing UKF or HP value blocks the fire. "
    "All earlier gates still apply. (2) Throttle: each brake fires at most once per 30 minutes, and neither brake fires "
    "within 30 minutes of the other; an early cut-short does not shorten that wait. (3) Bands: the day mid band is now "
    "7.5 to 9.0 mmol/L (was above 6.5) and never reaches 9.0 or above, so from 22:00 the night brake alone owns 9.0 and "
    "above. (4) Action: a 4.0 mmol/L target for 2 minutes (was 5) plus the pp weight raised to its high value; the SMB "
    "delivery-ratio boost (BMild +0.15 or +0.075, x2, x1.5) is no longer applied. (5) Cut-short: the target is cancelled "
    "when any of the three deltas is 0 or below (or above its upper bound), or when the 5-minute IOB change reaches 1.0 U; "
    "UKF deltas are entry-only. Not yet verified on a device."
)
insert_after("Updated 6 Sep 2026. 60-min no-bolus lock and 30-min re-arm removed", para_xml("Updated 19 Sep 2026.", UPDATE_BODY), "HiBrk update paragraph")

replace_para_text(
    "HighEveNightBrake/HighDaytimeBrake fired",
    "HighEveNightBrake/HighDaytimeBrake fired \u2014 plateaued-high brake: 4.0 mmol TT for 2 min + pp weight raised (no SMB delivery boost since 19 Sep 2026)",
    "glossary row",
)
replace_para_text(
    "Cuts the night HiBrk 4.0 TT short when 5-min IOB change is already a burst",
    "Cuts the night HiBrk 4.0 TT short when the 5-min IOB change reaches 1.0 U or any of Delta / SDelta / LDelta is no longer above 0 "
    "(or is above its upper bound; changed 19 Sep 2026). Graph4: HBCut.",
    "Appendix D HiBrkCut",
)
replace_para_text(
    "Daytime high band (06:00\u201322:00, BGL \u22659.0): 4.0@5min.",
    "Daytime high band (06:00\u201322:00, BGL \u22659.0). Since 19 Sep 2026: TT 4.0 mmol for 2 min + pp weight raised, no SMB delivery-ratio boost. "
    "Needs Delta/SDelta/LDelta > 0, raw UKF5/15 > 0, HP \u2265 6.5, at most one fire per 30 min (shared with night). Graph4: HBDay.",
    "Appendix D HiBrkDay",
)
replace_para_text(
    "Cuts the daytime/mid HiBrk 4.0 TT short. Graph4: HBDCt.",
    "Cuts the daytime/mid HiBrk 4.0 TT short (same triggers as HiBrkCut). Graph4: HBDCt.",
    "Appendix D HiBrkDayCut",
)
replace_para_text(
    "Day/eve mid band (06:00\u201301:30, BGL >6.5",
    "Day/eve mid band (06:00\u201301:30, BGL 7.5\u20139.0 since 19 Sep 2026, was >6.5; NOMJremains or MJ3, not 50recent). "
    "Same action and gates as HiBrkDay. User name HiBrkMid. Graph4: HBMid.",
    "Appendix D HiBrkDayMid",
) if False else None

# Appendix D HiBrkDayMid text contains an escaped '>' (&gt;) in the XML text, so match on the plain prefix
m = [mm for mm in PARA_RE.finditer(x) if ptext(mm.group(0)).startswith("Day/eve mid band (06:00\u201301:30, BGL &gt;6.5")]
assert len(m) == 1, len(m)
replace_para_text(
    "Day/eve mid band (06:00\u201301:30, BGL &gt;6.5",
    "Day/eve mid band (06:00\u201301:30, BGL 7.5\u20139.0 since 19 Sep 2026, was >6.5; NOMJremains or MJ3, not 50recent). "
    "Same action and gates as HiBrkDay. User name HiBrkMid. Graph4: HBMid.",
    "Appendix D HiBrkDayMid",
)
replace_para_text(
    "Night/eve high brake (22:00\u201306:00, BGL \u22659.0): 4.0 mmol TT @ 5 min.",
    "Night/eve high brake (22:00\u201306:00, BGL \u22659.0). Since 19 Sep 2026: TT 4.0 mmol for 2 min + pp weight raised, no SMB delivery-ratio boost. "
    "Same gates as HiBrkDay. Missing from the 6 Sep FULL Appendix D extract.",
    "Appendix D HiBrk",
)

CHANGELOG = (
    "HiBrk / HiBrkDay / HiBrkDayMid reworked: all-deltas-above-0 + raw UKF + HP \u2265 6.5 gates, 30-min lockout shared by both brakes, "
    "day mid band 7.5\u20139.0, TT 4.0 mmol for 2 min + pp weight (no SMB delivery boost) \u2014 see \u00a73f. Delayed bolus now checks every "
    "5 min (Db5\u2013Db80) with a looser gate and 80% while moving (\u00a78). Only the last Warsaw/FPU sub-dose writes a 0U marker."
)
insert_after("Fixed PpWeightRevertUnder8_5: it had no grace period at all", para_xml("2026-09-19.", CHANGELOG, indent=False), "changelog paragraph")

with zipfile.ZipFile(SRC) as zin, zipfile.ZipFile(OUT, "w") as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == "word/document.xml":
            data = x.encode("utf-8")
        zout.writestr(item, data, compress_type=item.compress_type)
print(OUT)

# ------------------------------------------------------------------------------------------------ CarePortal Full v3
SRC_F = Path(sorted(glob.glob(str(AAA / "AutoISF CarePortal Note Code Full Reference mydoc Sep 19 26 DRAFT updates v2 *.docx")))[-1])
OUT_F = AAA / f"AutoISF CarePortal Note Code Full Reference mydoc Sep 19 26 DRAFT updates v3 {stamp}.docx"
NEW_DESC = {
    "HiBrk": "Evening or night high-glucose brake started (BG \u2265 9.0; since 19 Sep 2026: TT 4.0 mmol for 2 min + pp weight raised, no SMB delivery boost)",
    "HBCut": "Evening or night high-brake TT ended early (a delta no longer above 0, or IOB +1.0 U in 5 min)",
    "HBDay": "Daytime high-band brake started (BG \u2265 9.0; same action and gates as HiBrk)",
    "HBDCt": "Daytime high-brake TT ended early (a delta no longer above 0, or IOB +1.0 U in 5 min)",
    "HBMid": "Daytime middle-band brake started (BG 7.5\u20139.0 since 19 Sep 2026; same action and gates as HiBrk)",
}
doc = Document(SRC_F)
changed = 0
for row in doc.tables[0].rows:
    code = row.cells[0].text.strip()
    if code in NEW_DESC:
        cell = row.cells[2]
        for p in cell.paragraphs[1:]:
            p._p.getparent().remove(p._p)
        p0 = cell.paragraphs[0]
        if p0.runs:
            p0.runs[0].text = NEW_DESC[code]
            for extra in p0.runs[1:]:
                extra._r.getparent().remove(extra._r)
        else:
            p0.add_run(NEW_DESC[code])
        changed += 1
assert changed == 5, changed
doc.save(OUT_F)
print(OUT_F, "rows changed:", changed)
