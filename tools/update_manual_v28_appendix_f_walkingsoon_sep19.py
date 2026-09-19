"""Manual v27 -> v28 (19 Sep 2026):
  1. Walking-soon paragraph: the immediate dose is cut to min(standing wiz%, 70%), not "forced to 50%".
  2. Appendix F (Automation States In Use): insert a dated currency notice + a freshly extracted "Written by" list
     (every setAutomationState() call site in OpenAPSAutoISFPlugin.kt), flag the stale line numbers/Steps premise,
     and document the AlarmHypo state and the new writers. The old per-state prose is left intact below it.
New dated/versioned file; v27 is kept."""
import glob
import re
import zipfile
from datetime import datetime
from html import escape
from pathlib import Path

SRC = Path(sorted(glob.glob(r"C:\winword\aaa\AutoISF Operations FULL Manual revised Saturday, September 19th, 2026 mydoc v27 * fully sorted careportal tables.docx"))[-1])
now = datetime.now()
OUT = SRC.with_name(
    f"AutoISF Operations FULL Manual revised Saturday, September 19th, 2026 mydoc v28 {now:%H%M} fully sorted careportal tables.docx"
)
PLUGIN = Path(r"C:\Users\arjay\StudioProjects\AaAPS3422a320\plugins\aps\src\main\kotlin\app\aaps\plugins\aps\openAPSAutoISF\OpenAPSAutoISFPlugin.kt")

x = zipfile.ZipFile(SRC).read("word/document.xml").decode("utf-8")


def sub_once(old, new, label):
    global x
    assert x.count(old) == 1, (label, x.count(old))
    x = x.replace(old, new)
    print("ok:", label)


# ---- 1. Walking soon -------------------------------------------------------------------------------------------
sub_once(
    "the immediate dose is forced to 50% of the normal Wizard percentage without changing that setting",
    "the immediate dose is cut to the smaller of the standing Wizard percentage and 70% (the moving percentage) "
    "without changing that setting",
    "walking soon immediate percent",
)

# ---- 2. Appendix F ---------------------------------------------------------------------------------------------
lines = PLUGIN.read_text(encoding="utf-8").split("\n")
total_lines = len(lines)
mark = [(i + 1, m.group(1)) for i, l in enumerate(lines) for m in re.finditer(r'markRun\("([^"]+)"', l)]
ready = [(i + 1, m.group(1)) for i, l in enumerate(lines) for m in re.finditer(r'readyToRun\("([^"]+)"', l)]

# Block labels that the heuristic below cannot derive (writers with no markRun/readyToRun of their own)
LABEL_OVERRIDE = {
    501: "handleDirectMjUserAction START",
    510: "handleDirectMjUserAction RESTORE",
    591: "handleDirectSteroidUserAction START_110",
    635: "handleDirectSteroidUserAction TURN_OFF",
    1140: "keepSteroidsOff() (Standard/Low role writes)",
    3621: "Virtual-only MJ button-note relay",
    3648: "Virtual-only Steroid note relay (SteroidsON)",
    3653: "Virtual-only Steroid note relay (SteroidsOff)",
    6602: "BolusGiven",
    8492: "Bolus2 (standalone clear block)",
    6864: "TT57Reversal (in the BolusGivenBg3 region)",
    5656: "50SetRecent",
    7410: "MoreMJ (hypoalarm path, MoreMJ2 note)",
    7415: "MoreMJ (no-recent-high path)",
}

writers = {}
for i, l in enumerate(lines):
    m = re.search(r'setAutomationState\(\s*"([^"]+)"\s*,\s*([^)]+)\)', l)
    if not m or l.strip().startswith("//"):
        continue
    n = i + 1
    state, value = m.group(1), m.group(2).strip().strip('"')
    if n in LABEL_OVERRIDE:
        label = LABEL_OVERRIDE[n]
    else:
        after = [k for k in mark if n <= k[0] <= n + 12]
        before = [k for k in ready if k[0] <= n][-1:]
        label = (after or before)[0][1]
    if value == "newState":
        value = "MJ active / NOMJremains"
    writers.setdefault(state, []).append((n, label, value))

assert sum(len(v) for v in writers.values()) == 47, sum(len(v) for v in writers.values())

STATE_ORDER = ["MJ", "Steroids", "LowBG", "AlarmHypo", "Profile"]
grouped = []
for st in STATE_ORDER:
    items = "; ".join(f'{label} → "{val}" (line {n})' for n, label, val in writers[st])
    grouped.append(f"{st}: {items}.")

P_PPR = '<w:pPr><w:spacing w:after="{after}"/><w:jc w:val="both"/></w:pPr>'


def para(text, bold_lead=None, after=80):
    runs = ""
    if bold_lead:
        runs += f'<w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">{escape(bold_lead, quote=False)} </w:t></w:r>'
    runs += f'<w:r><w:t xml:space="preserve">{escape(text, quote=False)}</w:t></w:r>'
    return f"<w:p>{P_PPR.format(after=after)}{runs}</w:p>"


block = [
    para(
        f"The per-state lists in §15a–f below were traced on 22 August 2026 against a much shorter OpenAPSAutoISFPlugin.kt "
        f"(about 5,800 lines). The file is now {total_lines:,} lines, so every “(line N)” reference in §15a–f is out of date "
        "(roughly 1.8 times too low): use the automation names, not the numbers. The “Read by” lists in §15a–f have not been "
        "re-checked since 22 August. The “Written by” picture has been re-extracted from every setAutomationState() call "
        "(all 47 call sites use literal state names and values) and is listed here with current line numbers.",
        bold_lead="Currency notice, 19 September 2026.",
    ),
    para("What has changed since the 22 August trace:", after=60),
    para(
        "• New state AlarmHypo (values AlarmRecent / NoAlarmRecent). Written: AlarmHypo1 and AlarmHypo2 set AlarmRecent; "
        "Not50Recently sets NoAlarmRecent (cleared together with LowBG). Read by: MoreMJ’s hypoalarm path (which then sets MJ2 "
        "and writes the MoreMJ2 note) and GentleHypoRisk.",
        after=60,
    ),
    para(
        "• New MJ writers: MjStateActiveTT and MjStateMj2TT (List 1 direct actions setting “MJ active” / “MJ2”), MoreMJ’s "
        "hypoalarm path (MJ2) alongside its plain path (MJ3), and, on VirtualPump only, a one-time relay of the MJ button-press "
        "notes (“MJ active” / “NOMJremains”) written on the loop phone.",
        after=60,
    ),
    para(
        "• New Steroids writers: keepSteroidsOff() (Standard/Low role writes never invent SteroidsON) and, on VirtualPump only, "
        "a one-time relay of the SteroidsON / SteroidsOff notes.",
        after=60,
    ),
    para(
        "• Profile: HighNight00AM is no longer permanently disabled; it writes the value “HnAM”, which OffHighProf reads and "
        "clears back to C100. (§15e below still says the block is dead and writes C100.)",
        after=60,
    ),
    para(
        "• Steps is no longer an externally owned state: the read site now computes StepsLow / StepsHigh directly from the step "
        "counts, so §15d’s premise (and its misconfiguration note) is superseded.",
        after=80,
    ),
    para("Current writers, by state (block name → value written, current line):", after=60),
] + [para(g, after=60) for g in grouped]

block_xml = "".join(block)

# Insert right after the Appendix F intro paragraph (the one starting "A reference for every named state")
hits = [
    pm for pm in re.finditer(r"<w:p[ >].*?</w:p>", x, flags=re.S)
    if "".join(re.findall(r"<w:t[^>]*>([^<]*)</w:t>", pm.group(0))).startswith("A reference for every named state")
]
assert len(hits) == 1, len(hits)
m = hits[0]
x = x[: m.end()] + block_xml + x[m.end():]
print("ok: appendix F currency block,", len(block), "paragraphs;", total_lines, "plugin lines")

with zipfile.ZipFile(SRC) as zin, zipfile.ZipFile(OUT, "w") as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == "word/document.xml":
            data = x.encode("utf-8")
        zout.writestr(item, data, compress_type=item.compress_type)
print(OUT)
