"""24 Sep 2026: FULL manual v31 -> v32 (new dated/versioned file only; v31 and the Latest/DELL17 copies are untouched).

Step 1 (adapted from merge_manual_references_sep23.py): swap the manual's Appendix D CarePortal table for the new
CarePortal Full Reference v5 and its coded-automations registry section for the Automations List v13.
Step 2: add the "Updated ..." body paragraphs and the Appendix B changelog entries for everything that changed in the code
since the 20/21 Sep documents (commits aisf321UK_917 .. 934 plus the uncommitted 24 Sep working-tree edits)."""
import glob
import json
import re
from copy import deepcopy
from datetime import datetime
from html import escape
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

from lxml import etree as E

ROOT = Path(r"C:\winword\aaa")
BASE = ROOT / "AutoISF Operations FULL Manual revised Monday, September 21st, 2026 mydoc v31 0028 fully sorted careportal tables.docx"


def newest(pattern):
    files = [f for f in glob.glob(str(ROOT / pattern)) if "DELL17" not in f and "Latest" not in f]
    assert files, pattern
    return Path(sorted(files, key=lambda f: Path(f).stat().st_mtime)[-1])


CARE = newest("AutoISF CarePortal Note Code Full Reference mydoc Sep 24 26 DRAFT updates v5 *.docx")
AUTO = newest("AutoISF Automations List mydoc Sep 24 26 * code registry triggers v13.docx")
print("CARE:", CARE.name)
print("AUTO:", AUTO.name)

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
NS = {"w": W, "r": R}


def q(s):
    return "{" + W + "}" + s


def text(e):
    return "".join(e.xpath(".//w:t/text()", namespaces=NS))


def xml(b):
    return E.fromstring(b)


def encode(e):
    return E.tostring(e, xml_declaration=True, encoding="UTF-8", standalone=True)


def para(t, style=None):
    p = E.Element(q("p"))
    if style:
        E.SubElement(E.SubElement(p, q("pPr")), q("pStyle")).set(q("val"), style)
    E.SubElement(E.SubElement(p, q("r")), q("t")).text = t
    return p


with ZipFile(BASE) as z:
    parts = {n: z.read(n) for n in z.namelist()}
doc = xml(parts["word/document.xml"])
body = doc.find(q("body"))
styles = xml(parts["word/styles.xml"])
audit = {}


def imported(path, prefix):
    with ZipFile(path) as z:
        src = xml(z.read("word/document.xml"))
        ss = xml(z.read("word/styles.xml"))
    nodes = [deepcopy(e) for e in src.find(q("body")) if e.tag != q("sectPr")]
    nodes = nodes[1:]  # keep the manual's own section heading
    while nodes and nodes[-1].tag == q("p") and not text(nodes[-1]).strip():
        nodes.pop()
    mapping = {s.get(q("styleId")): prefix + s.get(q("styleId")) for s in ss.findall(q("style"))}
    for s in ss.findall(q("style")):
        s = deepcopy(s)
        s.set(q("styleId"), mapping[s.get(q("styleId"))])
        s.attrib.pop(q("default"), None)
        name = s.find(q("name"))
        if name is not None:
            name.set(q("val"), prefix + name.get(q("val")))
        for e in s.iter():
            if e.tag in [q("basedOn"), q("next"), q("link")] and e.get(q("val")) in mapping:
                e.set(q("val"), mapping[e.get(q("val"))])
        styles.append(s)
    for node in nodes:
        assert not node.xpath(".//@r:id|.//@r:embed|.//@r:link|.//w:numId|.//w:footnoteReference|.//w:endnoteReference", namespaces=NS)
        for e in node.iter():
            if e.tag in [q("pStyle"), q("rStyle"), q("tblStyle")] and e.get(q("val")) in mapping:
                e.set(q("val"), mapping[e.get(q("val"))])
        for tbl in ([node] if node.tag == q("tbl") else node.findall(".//" + q("tbl"))):
            grid = tbl.find(q("tblGrid"))
            widths = [int(c.get(q("w"))) for c in grid]
            scale = min(1.0, 10080 / sum(widths))
            for c in grid:
                c.set(q("w"), str(round(int(c.get(q("w"))) * scale)))
            for cw in tbl.findall(".//" + q("tcW")):
                if cw.get(q("type")) == "dxa":
                    cw.set(q("w"), str(round(int(cw.get(q("w"))) * scale)))
            pr = tbl.find(q("tblPr"))
            tw = pr.find(q("tblW"))
            if tw is not None:
                tw.set(q("w"), "10080")
                tw.set(q("type"), "dxa")
            for height in tbl.findall(".//" + q("trHeight")):
                if height.get(q("hRule")) == "exact":
                    height.set(q("hRule"), "atLeast")
    audit[prefix] = {"source": str(path), "table_rows": [len(n.findall(q("tr"))) for n in nodes if n.tag == q("tbl")]}
    return nodes


def replace_section(start_text, end_text, nodes):
    start = next(i for i, e in enumerate(body) if e.tag == q("p") and text(e).startswith(start_text))
    end = next(i for i, e in enumerate(body) if i > start and e.tag == q("p") and text(e).startswith(end_text))
    heading = body[start]
    for e in list(body)[start + 1:end]:  # preserve targets of existing links into the replaced material
        for mark in e.xpath(".//w:bookmarkStart|.//w:bookmarkEnd", namespaces=NS):
            heading.append(deepcopy(mark))
        body.remove(e)
    for offset, n in enumerate(nodes, 1):
        body.insert(start + offset, n)


def stamp_of(path):
    return datetime.fromtimestamp(path.stat().st_mtime)


care = imported(CARE, "CareRef_")
care.insert(0, para(f"Full reference supplied as DRAFT updates v5, {stamp_of(CARE):%d %B %Y at %H:%M}. This replaces the previous compact and full CarePortal tables."))
replace_section("Appendix D — Alphabetical CarePortal Note Code Reference", "Appendix E —", care)
autos = imported(AUTO, "AutoRef_")
with ZipFile(AUTO) as z:
    auto_src = xml(z.read("word/document.xml"))
n_autos = len(auto_src.find(".//" + q("tbl")).findall(q("tr"))) - 1
autos.insert(0, para(f"Triggers registry DRAFT v13, {stamp_of(AUTO):%d %B %Y at %H:%M}. The {n_autos} coded automations are listed alphabetically."))
replace_section("AutoISF Coded Automations - Current Code Registry", "Appendix A —", autos)

now = datetime.now()
version = f"Version 32 | Updated {now:%d %B %Y at %H:%M}"
body.insert(1, para(version))

# Repair existing fragment-only hyperlink relationships so standard DOCX readers can open the file.
rels = xml(parts["word/_rels/document.xml.rels"])
repaired = []
for rel in list(rels):
    target = rel.get("Target", "")
    if target.startswith("#") and rel.get("Type", "").endswith("/hyperlink"):
        for link in doc.xpath("//w:hyperlink[@r:id=$rid]", namespaces=NS, rid=rel.get("Id")):
            link.attrib.pop("{" + R + "}id", None)
            link.set(q("anchor"), target[1:])
        repaired.append(target)
        rels.remove(rel)
parts["word/_rels/document.xml.rels"] = encode(rels)
parts["word/styles.xml"] = encode(styles)

# ------------------------------------------------------------------ step 2: text inserts (raw XML, same helpers as update_docs_sep20.py)
x = encode(doc).decode("utf-8")
PARA_RE = re.compile(r"<w:p[ >].*?</w:p>", re.S)


def ptext(p):
    return "".join(re.findall(r"<w:t[^>]*>([^<]*)</w:t>", p))


def para_xml(lead, body_text, indent=True):
    ppr = '<w:pPr><w:ind w:left="720"/><w:jc w:val="both"/></w:pPr>' if indent else '<w:pPr><w:jc w:val="both"/></w:pPr>'
    return (f'<w:p>{ppr}<w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">{escape(lead, quote=False)} </w:t></w:r>'
            f'<w:r><w:t xml:space="preserve">{escape(body_text, quote=False)}</w:t></w:r></w:p>')


def insert_after_prefix(prefix, new_xml, label, which="unique"):
    global x
    if isinstance(new_xml, tuple):
        new_xml = "".join(new_xml)
    hits = [m for m in PARA_RE.finditer(x) if ptext(m.group(0)).startswith(prefix)]
    assert hits, (label, "no hit")
    if which == "unique":
        assert len(hits) == 1, (label, len(hits))
    for m in reversed(hits):
        x = x[: m.end()] + new_xml + x[m.end():]
    print("ok:", label, f"({len(hits)} place(s))")


D = "\u2013"  # en dash

# --- sensor-age tier "1" (20 Sep 22:39, commit 909)
insert_after_prefix(
    "NewDay1 (under 1 day", para_xml(
        "Updated 20 Sep 2026.",
        "The late-life tier \u201c1\u201d now begins at 9 days instead of 11 (calendar day 10 after 9 full days): 9\u201313 days applies the same "
        "mild adjustment as before (slope \u22120.02, offset +0.05). Tiers \u201c2\u201d (13\u201314 days) and \u201c3\u201d (14\u201315 days) are unchanged. "
        "Wherever this section still says \u201c11\u201313 days\u201d for tier \u201c1\u201d or \u201c11\u201315 days\u201d for the late tiers, read 9\u201313 and 9\u201315."),
    "sensor-age tier 1")

# --- FastRise 8.0 -> 7.5 boundary (23 Sep)
insert_after_prefix(
    "Mild fast rise. Delta between 0.25 and 0.55 mmol", para_xml(
        "Updated 23 Sep 2026.",
        "In both the moderate and the mild tier the BG boundary that separates the 0.65 / 0.85 multipliers from the harsher 0.5 / 0.75 ones "
        "moved from 8.0 to 7.5 mmol: above 7.5 (up to 8.8) the multiplier is now 0.65 (moderate) or 0.85 (mild); at or below 7.5 it is 0.5 / 0.75 "
        "with the same threshold-or-hour conditions as before. Above 8.8 nothing changed. Reason: on 22 Sep 17:56\u201317:59, on both Live and Virtual, "
        "a genuine sustained rise at BG 7.0\u20137.7 got the strictest cut the whole time while BG kept climbing, so the strictest cut was landing where "
        "less caution was warranted. Everywhere this section says \u201cbetween 8.0 and 8.8\u201d or \u201cat or below 8.0\u201d for these two tiers, read 7.5."),
    "fastrise boundary")

# --- HiBrkTwilight + HiBrkDayMid start (after the 19 Sep HiBrk rework paragraph)
insert_after_prefix(
    "Updated 19 Sep 2026. HighDaytimeBrake and HighEveNightBrake reworked", para_xml(
        "Updated 23\u201324 Sep 2026.",
        "HiBrkTwilight (dawn, TT only). A third brake now covers the dawn stretch 04:00\u201307:00 for BG above 6.5 and below 9.0 mmol, where the night brake "
        "(from 9.0) and the day mid band do not reach. It requires Steroids Off, no active temporary target, no HighEveNightBrake / HighDaytimeBrake fire "
        "within 30 minutes (and neither of those fires within 30 minutes of HiBrkTwilight), Delta / SDelta / LDelta all above 0 and below 0.1 / 0.1 / 0.3 mmol, "
        "IOB change over 5 minutes under 0.5 U, duraISF at least as large as the acce, bg and pp ISF factors, raw UKF delta5 and delta15 both positive, and "
        "HP1 (the AIV HP figure) at least 6.2 mmol. The action is a temporary target of 4.2 mmol for 2 minutes only \u2014 no SMB delivery change, "
        "pp-weight change or profile switch. Own throttle 15 minutes. While its own target is running (recognised by the target\u2019s exact timestamp, "
        "so a manual target is never cancelled) it ends the target early if a delta stops being positive and in range or IOB rises by 1.0 U or more in "
        "5 minutes, writing HiBrkTwilightCut. Notes: HiBrkTwilight (graph label HBTwi) and HiBrkTwilightCut (HBTCt); SMS text \u201cHiBrkTwilight: TT 4.2mmol@2min\u201d. "
        "History: added 21 Sep with TT 4.4 mmol and a 30-minute throttle; on 23 Sep (04:00\u201306:47 BG rose 4.9 to 6.9 then plateaued at 6.5\u20136.9 for about two hours "
        "with SMB 0.00) it was tightened to TT 4.2 and a 15-minute throttle, because the 2-minute target fired once at 05:01 and the throttle kept it off for the "
        "next half hour; on 24 Sep the HP1 gate was lowered from 6.5 to 6.2 (BG plateaued at 6.4\u20136.6 with IOB about 0.3, so HP1 sat at 6.2\u20136.3 and the "
        "6.5 gate, the only one failing on the minutes with BG above 6.5, never opened). The night and day brakes keep 6.5. The 23 Sep values are built; "
        "the 24 Sep HP1 change is in the source but not yet built or device-verified. Also since 21 Sep, HiBrkDayMid (the day 7.5\u20139.0 band) starts at 07:00 "
        "instead of 08:30 so that it abuts HiBrkTwilight, and a HiBrkTwilight fire delays both other brakes by its own 30-minute lockout."),
    "hibrktwilight")

# --- EDSR + HnAM (after the StuckRisingSlowly paragraph)
insert_after_prefix(
    "StuckRisingSlowly (updated 21 Sep 2026)", para_xml(
        "Updated 24 Sep 2026.",
        "EarlyDawnSlowRise (note EDSR) and HighNight00AM (state HnAM), the two other dawn automations touched with HiBrkTwilight. EarlyDawnSlowRise: "
        "04:30\u201308:00, no active temporary target, BG above 6.5 mmol (was 7.0) and Delta, SDelta and LDelta each above 0 and below 0.15 mmol; since 21 Sep the action "
        "is a temporary target only, and from 24 Sep it is 4.4 mmol for 5 minutes (was 30) with a 5-minute throttle (was 30), so it re-arms only while the slow rise "
        "persists and ends within about 5 minutes of the rise stopping (a 30-minute target also blocked HiBrkTwilight for the whole 30 minutes, since that needs no "
        "active target). Reason: on 24 Sep 04:30\u201306:47 BG plateaued at 6.4\u20136.6 so the 7.0 floor could never open; with 6.5 it first qualifies at 06:35 that morning. "
        "Not yet built or device-verified. HighNight00AM: the window is now 02:00\u201307:00 (was 02:00\u201304:00 until 23 Sep); the sole action is still the "
        "Standard-versus-Low profile switch that writes the Profile state HnAM, no iobTH or acce change, 60-minute throttle. Reason: on 23 Sep all three deltas "
        "cleared 0.1 mmol together only at 04:45\u201304:48, right as BG first passed 6.5, which the old 04:00 cutoff could never reach. Its firing order against "
        "HiBrkTwilight is unchanged: it still needs no active temporary target, so a live HiBrkTwilight target blocks it until that target ends."),
    "edsr + hnam")

# --- Tier 3 / BMild cycle notes (after the 6 Sep evening BMild-only paragraph)
insert_after_prefix(
    "Updated 6 Sep 2026 evening. BMild-only (not bg3, not an overlap cycle)", para_xml(
        "Updated 21\u201322 Sep 2026.",
        "Tier 3 limits and BMild/Tier 3 cycle notes. (a) Tier 3 itself is skipped, while BMild and bg3 still run normally, when a UamBst note was marked in the last "
        "20 minutes or the live SMB delivery ratio is above 0.50. The reason text shows T3skip<20min or T3skip SMBdel>x. Tier 3 still never writes the delivery "
        "ratio itself. The same 20-minute flag stops Tier 3 re-arming on every 5-minute BMild/bg3 tick after a fire. (b) The per-SMB size cap is now the smaller of "
        "the UAM Boost max bolus setting and max_iob divided by 9.5 (1.0 U at a max_iob of 9.5), instead of the setting alone. (c) Compare switch "
        "ApsAutoIsfUamBoostUnrestrictedEnabled (Settings and List 2 row 5.230 \u201cT3 unrestricted compare on/off\u201d, default OFF, not Virtual-gated): while ON, "
        "Tier 3 skips (a), the max_iob/9.5 cap and the percentage IOB ceiling/allowance, for side-by-side comparison only; the reason text shows \u201cT3 unrestricted "
        "(compare)\u201d and each flip writes T3uOn / T3uOff. (d) BMild/bg3 boost this cycle: the daytime and overnight recoveries and the Usual2, CarbsTHoff, NightAcce and "
        "SemiTwilight blocks no longer restore the SMB delivery baseline in the same cycle in which a BMild or bg3 boost has just applied (the boost\u2019s target is not visible "
        "until the next loop, so they were undoing it); Set50 and Skittles hypo restores still force the baseline. (e) Bolus-age gates: every automation that asks how long ago the last "
        "normal bolus was now ignores the 0 U NORMAL records an Omnipod Dash writes when a basal-compensation bolus is denied or cannot be confirmed; only a positive "
        "normal bolus counts (a day with none is treated as older than any gate). (f) Mild boost step and default: a 21 Sep interim change to 0.05 / 0.15 was reverted the same "
        "day; the mild boost ratio is again 0.20 by default and List 1 / TT 5.052 and 5.054 step it by \u00b10.25."),
    "tier3 group")

# --- LoReb rewrite + offset band (after the 20 Sep test-toggles paragraph, which describes LoReb's toggle)
insert_after_prefix(
    "Updated 20 Sep 2026. Two test toggles.", (
        para_xml(
            "Updated 21\u201322 Sep 2026.",
            "LoReb (post-low rebound guard) rewritten; the switch and its default are unchanged. It now needs a genuine AlarmHypo1 or AlarmHypo2 firing inside its own "
            "lookback window, read from a dedicated persisted timestamp (ApsAutoIsfLastAlarmHypoAt), not from AlarmRecent and not from any low stored BG. The window is "
            "30 minutes while BG is above 6.0 mmol and both Delta and SDelta are above 0.10 mmol (a confirmed rising recovery), otherwise 60. Two triggers: the carb branch "
            "(COB above 0, or unannounced carb absorption of at least 0.3 g per 5 minutes) and the artifact branch (COB 0, Delta above 0.40, SDelta above 0.30 and BG below 9.4 mmol; "
            "thresholds taken from the real 21 Sep post-alarm rise, first crossed at Delta 0.42 / SDelta 0.31, 28 minutes after AlarmHypo1). The old minimum-BG tests "
            "(recent low below 5.6 / 4.5 mmol) and the reversal-score test are gone. Effect on SMB: 0.10 U or less is left alone, above 0.10 up to 0.25 U is multiplied by 0.75, "
            "above 0.25 U by 0.5. While LoReb is active the FastRise size tiers are also switched off (reason text \u201cFastRise tiers OFF (LoReb 30min)\u201d or 60min) so the two "
            "cuts do not compound."),
        para_xml(
            "Updated 24 Sep 2026.",
            "Carb-bypassed offset band (source only, not yet built or device-verified). Normally offsetSoZeroSMB zeroes the SMB whenever BG is below target plus the SMB offset, "
            "but a recent carb entry (COB at least 5, or COB under 5 with carbs 120 minutes old or less) switches that exclusion off, so small SMBs still went out right at target "
            "(23 Sep 20:50\u201321:14: seven 0.05 U SMBs at BG 5.2\u20135.5 with COB 21\u201324 g, then BG fell to 4.1). While the exclusion is off but BG is still inside the offset band "
            "(below the offset target, which is the profile target plus the SMB offset, capped at 7.0 mmol), the SMB is now zero when BG is below target plus half the offset, and "
            "half size from there up to the offset target (then floored to the pump step). Skipped on BMild, bg3 and Tier 3 fire cycles: MildOffsetZero already sets the offset to "
            "0 for BMild, and Tier 3 has its own IOB allowance. The reason text shows \u201coffsetBand: ...\u201d when it acts. Frequency check on real data: one episode on 23 Sep, "
            "seven rows, 0.35 U in total.")),
    "loreb + offsetband")

# --- Wizard recent-entry rule
insert_after_prefix(
    "Session-only Quick Wizard MaxBolus adjustment.", para_xml(
        "Updated 24 Sep 2026.",
        "Low-BG recent-entry rule (Bolus Wizard dialog, Quick Wizard buttons and BolusWizard.doCalc; source only, not yet built or device-verified). It applies when all "
        "of these hold: current BG is below 6.0 mmol (the wizard BG box if used, otherwise the latest CGM value); a normal bolus or a carb entry exists within the last 60 minutes "
        "(loop SMBs and 0 U records are ignored, since SMBs arrive about once a minute and would make the rule permanently true at low BG); and BG was below 6.0 when that "
        "FIRST entry was made and has stayed below 6.0 on every stored reading from 5 minutes before it until now. When it applies: (1) COB is not used in the calculation, "
        "because the COB then still holds carbs the earlier entry\u2019s bolus already covered; the Bolus Wizard dialog and the Quick Wizard confirmation both show the COB box "
        "starting UNticked and you can re-tick it for that bolus (a manual tick or untick wins for the rest of the session, and the automatic untick is never saved to the "
        "saved COB default). (2) The maximum bolus in force is cut to 80%, rounded down to the pump step: applied to whatever limit is already in effect, so the dialog\u2019s BG-below-6 "
        "session limit gives 3.0 to 2.0 to 1.6 U. For Quick Wizard the reduced limit is applied temporarily for that press (SafetyMaxBolus is lowered and restored afterwards) and the "
        "confirmation dialog carries a limit box, so the limit is visible and can be changed there without a further dialog; changing it recalculates the confirmation text. "
        "The wizard calc-info line then reports \u201cRecentEntry<60min@BG<6 (COB on/off, MaxBolus x0.8)\u201d. When the rule stops applying the COB box returns to your saved setting."),
    "wizard rule")

# --- AIV + graph
insert_after_prefix(
    "Updated 20 Sep 2026. On VirtualPump only (not the Client app), the AIV Notes column", (
        para_xml(
            "Updated 21\u201322 Sep 2026.",
            "AIV history. (a) New MildBst column (on-screen table and CSV/TXT export, between SmbRatio and SMBi5): the stored mild-boost base ratio "
            "(ApsAutoIsfMildBoostRatio, the List 1 MildBst / Settings mild boost value), not the live SmbRatio. Every cycle now appends \u201cMildBst: <value> ;\u201d to the RT reason next to the "
            "delivery ratio, and the exporter reads it back from the nearest APSResult within 15 minutes; \u201c--\u201d on rows from before the reason line existed. (b) AdOn / AdMs "
            "(AnyDesk launch succeeded / missed) are hidden on VirtualPump, in the AIV notes and in the graph notes, and Virtual\u2019s NS sync now drops them on arrival: Virtual never "
            "launches AnyDesk, so any it saw were Live\u2019s echoes. Not toggle-gated; the Client app still shows Live\u2019s."),
        para_xml(
            "Updated 22 Sep 2026.",
            "Graph annotations. The IOB graph (graph 2, in both Overview and History Browse) gets a row of exactly 12 white insulin-delivered totals near the top of the panel, one per equal "
            "slice of the visible window (5 minutes on 1 h, 15 on 3 h, 30 on 6 h, 1 h on 12 h, 1.5 h on 18 h, 2 h on 24 h). Each total is the temp-basal absolute rate integrated per minute "
            "plus the SMBs and normal boluses in the slice, plus any overlapping extended bolus unless the pump fakes temp basals with extended boluses; shown to two decimals without the "
            "leading zero (.35), and 0 for none. The seven time-axis labels of the rolling windows now show HH:mm (they are evenly spaced and rarely on the hour, so the hour alone read "
            "as 15\u201320 minutes early against the BG curve) and the 2nd and 6th are blanked to stop them colliding. With PRE on, a rolling window now grows its end by up to one hour "
            "for the prediction dots instead of shrinking its start. New graph note labels: HiBrkTwilight is drawn HBTwi and HiBrkTwilightCut HBTCt.")),
    "aiv + graph")

# --- List 2 row 5.230 (body + Appendix E; two places)
insert_after_prefix(
    "Updated 20 Sep 2026. Two new rows: 5.226", para_xml(
        "Updated 22 Sep 2026.",
        "One new row: 5.230 \u201cT3 unrestricted compare on/off\u201d. It shows the current value, flips ApsAutoIsfUamBoostUnrestrictedEnabled immediately on the loop phone (SMS plus "
        "CarePortal note T3uOn / T3uOff) and on AAPSClient relays the TT code to the loop phone, where the invoke-time T3UnrestrictedToggleTT block applies it; the effect is described under "
        "Tier 3 UAM Boost. The stepped Boost max row now shows its current value in U."),
    "list2 5.230", which="all")

# --- Appendix B changelog
insert_after_prefix(
    "2026-09-21. StuckRisingSlowly described in the body", (
        para_xml("2026-09-21 (later).",
                 "Tier 3 skipped while a UamBst is under 20 minutes old or SMBdel is above 0.50, per-SMB cap min(setting, max_iob/9.5), BMild/bg3 boost protected from same-cycle delivery-baseline "
                 "restores, LoReb and the FastRise tiers reworked (AlarmHypo1/2 window, 0.75/0.5 trim), mild boost step and default returned to 0.25 / 0.20, AdOn/AdMs hidden on Virtual.", indent=False),
        para_xml("2026-09-22.",
                 "Tier 3 compare switch (Settings and List 2 5.230, notes T3uOn/T3uOff); MildBst AIV column; 12 insulin-total labels and HH:mm axis on the IOB graph; sensor-age tier \u201c1\u201d "
                 "starts at 9 days (20 Sep); bolus-age gates ignore 0 U Dash records; settings export now reports lost local-directory access instead of failing silently; "
                 "the Maintenance export/import actions now warn when the local AAPS directory access is lost.", indent=False),
        para_xml("2026-09-23.",
                 "FastRise moderate/mild boundary 8.0 to 7.5 mmol; HiBrkTwilight added (04:00\u201307:00, TT 4.2 for 2 min, 15-minute throttle) with notes HiBrkTwilight/HiBrkTwilightCut; "
                 "HiBrkDayMid starts 07:00; HighNight00AM window 02:00\u201307:00.", indent=False),
        para_xml("2026-09-24.",
                 "HiBrkTwilight HP1 gate 6.2; EarlyDawnSlowRise BG floor 6.5, TT 4.4 for 5 min, 5-minute throttle; carb-bypassed offset band (zero / half SMB); low-BG recent-entry rule for the Bolus "
                 "Wizard and Quick Wizard (COB box unticked, max bolus x0.8, limit box in the Quick Wizard confirmation). The last four are source only, not yet built or device-verified. "
                 "CarePortal Full Reference v5, Abbreviated v4 and the Automations registry v13 were rebuilt and swapped into Appendix D and the registry section.", indent=False)),
    "changelog")

parts["word/document.xml"] = x.encode("utf-8")
E.fromstring(parts["word/document.xml"])  # well-formedness check

name = f"AutoISF Operations FULL Manual revised {now:%A}, September {now.day}th, {now.year} mydoc v32 {now:%H%M} fully sorted careportal tables.docx"
out = ROOT / name
with ZipFile(out, "w", ZIP_DEFLATED) as z:
    for n, b in parts.items():
        z.writestr(n, b)

# verify every source table cell is present, in order, after the transplant
final = xml(parts["word/document.xml"])
for path in [CARE, AUTO]:
    with ZipFile(path) as z:
        src = xml(z.read("word/document.xml"))
    table = src.find(".//" + q("tbl"))
    expected = table.xpath(".//w:t/text()", namespaces=NS)
    assert any(t.xpath(".//w:t/text()", namespaces=NS) == expected for t in final.findall(".//" + q("tbl"))), path
assert len(final.xpath("//w:drawing", namespaces=NS)) == 33, len(final.xpath("//w:drawing", namespaces=NS))
from docx import Document  # noqa: E402

Document(out)
with ZipFile(out) as z:
    assert z.testzip() is None
audit.update(output=str(out), version=version, repaired_fragment_links=len(repaired))
print(json.dumps(audit, indent=2))
