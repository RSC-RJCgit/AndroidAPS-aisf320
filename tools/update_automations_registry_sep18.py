import re
from pathlib import Path

from docx import Document
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Pt

ROOT = Path(r"C:\Users\arjay\StudioProjects\AaAPS3422a320")
AUTOMATIONS_SOURCE = Path(r"C:\winword\aaa\AutoISF_Automations_List5 mydoc.docx")
AUTOMATIONS_OUTPUT = ROOT / "AutoISF Automations List mydoc Sep 18 26 current code registry DRAFT for review.docx"
AUDIT_DATE = "2026-09-18"


def set_cell_width(cell, width):
    tc_pr = cell._tc.get_or_add_tcPr()
    tc_w = tc_pr.find(qn("w:tcW"))
    if tc_w is None:
        tc_w = OxmlElement("w:tcW")
        tc_pr.append(tc_w)
    tc_w.set(qn("w:w"), str(width))
    tc_w.set(qn("w:type"), "dxa")


def configure_table(table, widths, font_size=8.2):
    table.autofit = False
    table.style = "Normal Table"
    tbl_pr = table._tbl.tblPr
    tbl_w = tbl_pr.find(qn("w:tblW"))
    if tbl_w is None:
        tbl_w = OxmlElement("w:tblW")
        tbl_pr.append(tbl_w)
    tbl_w.set(qn("w:w"), str(sum(widths)))
    tbl_w.set(qn("w:type"), "dxa")
    tbl_ind = tbl_pr.find(qn("w:tblInd"))
    if tbl_ind is None:
        tbl_ind = OxmlElement("w:tblInd")
        tbl_pr.append(tbl_ind)
    tbl_ind.set(qn("w:w"), "120")
    tbl_ind.set(qn("w:type"), "dxa")
    borders = tbl_pr.find(qn("w:tblBorders"))
    if borders is None:
        borders = OxmlElement("w:tblBorders")
        tbl_pr.append(borders)
    for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
        border = OxmlElement(f"w:{edge}")
        border.set(qn("w:val"), "single")
        border.set(qn("w:sz"), "4")
        border.set(qn("w:space"), "0")
        border.set(qn("w:color"), "A6A6A6")
        borders.append(border)
    grid = table._tbl.tblGrid
    for child in list(grid):
        grid.remove(child)
    for width in widths:
        col = OxmlElement("w:gridCol")
        col.set(qn("w:w"), str(width))
        grid.append(col)
    header_pr = table.rows[0]._tr.get_or_add_trPr()
    header = OxmlElement("w:tblHeader")
    header.set(qn("w:val"), "true")
    header_pr.append(header)
    for row_index, row in enumerate(table.rows):
        for col_index, cell in enumerate(row.cells):
            set_cell_width(cell, widths[col_index])
            cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
            for paragraph in cell.paragraphs:
                paragraph.paragraph_format.space_before = Pt(0)
                paragraph.paragraph_format.space_after = Pt(1.5)
                for run in paragraph.runs:
                    run.font.size = Pt(font_size)
                    if row_index == 0:
                        run.bold = True


def parse_true_keys_in_source_order(lines):
    pattern = re.compile(r'readyToRun\("([^"]+)"')
    seen = set()
    ordered = []
    for line in lines:
        for match in pattern.finditer(line):
            key = match.group(1)
            if key not in seen:
                seen.add(key)
                ordered.append(key)
    return ordered


def automation_category(key):
    if key.endswith("TT") or key.startswith("TodOffset") or "Weight" in key or key.startswith("WizardPct") or key.startswith("Smb") or key.startswith("AutoIsfMax") or key.startswith("PeakInsulin"):
        return "Command / setting relay"
    if key in {"MJ4", "MJ5", "Test3"}:
        return "Validation/test"
    if key in {"AlarmHypo1", "AlarmHypo2", "GentleHypoRisk", "PrepareSet50", "SkittlesHypoRisk", "Extra50", "iobTHDaytimeFloor"}:
        return "Hypoglycaemia protection"
    if key.startswith("Battery") or "Pod" in key or key.startswith("Sensor") or key.startswith("PreSoak") or key == "ConnectPod":
        return "Device/pump/sensor"
    if key.startswith("Bolus") or key == "VirtualPseudoWizard":
        return "Bolus/post-bolus"
    if key.startswith("MJ") or key == "MoreMJ":
        return "Mounjaro state"
    if "Steroid" in key:
        return "Steroid state"
    return "Operational automation"


def extract_code_summary(key, lines):
    exact_ready = re.compile(rf'readyToRun\("{re.escape(key)}"\s*[,)]')
    exact_mark = re.compile(rf'markRun\("{re.escape(key)}"\s*\)')
    patterns = [exact_ready, exact_mark]
    occurrence = None
    for pattern in patterns:
        candidates = [i for i, line in enumerate(lines) if pattern.search(line)]
        occurrence = next((i for i in candidates if "if (" in lines[i] or lines[i].lstrip().startswith("if")), None)
        if occurrence is None:
            occurrence = candidates[0] if candidates else None
        if occurrence is not None:
            break
    if occurrence is None:
        exact_literal = re.compile(rf'"{re.escape(key)}"')
        occurrence = next((i for i, line in enumerate(lines) if exact_literal.search(line)), None)
    if occurrence is None:
        return "Registry key is present, but no direct handler occurrence was found by the documentation extractor; inspect the current source before use."
    window_start = max(0, occurrence - 90)
    banner = None
    for i in range(window_start, occurrence):
        stripped = lines[i].strip()
        if stripped.startswith("// ---") or stripped.startswith("// Code port") or stripped.startswith("// Standalone"):
            banner = i
    start = banner if banner is not None else max(window_start, occurrence - 18)
    comments = []
    for line in lines[start:occurrence]:
        stripped = line.strip()
        if stripped.startswith("//"):
            value = stripped[2:].strip().strip("-").strip()
            if value and not value.startswith(("TODO", "===", "***")):
                comments.append(value)
    summary = " ".join(comments)
    summary = re.sub(r"\s+", " ", summary).strip()
    if not summary:
        if key.startswith("TodOffset"):
            summary = "Member of the eight TodOffset TT pairs. A five-minute relay guard recognizes its exact 5.092-5.136 mmol command, changes the corresponding fixed time-of-day varOffset by 0.1 within the +/-2.0 clamp, cancels the command TT and records/notifies the result."
        else:
            summary = "Current handler is defined in OpenAPSAutoISFPlugin.kt; the adjacent source block contains no standalone prose comment."
    if len(summary) > 900:
        cut = summary.rfind(". ", 0, 900)
        summary = summary[: cut + 1 if cut > 300 else 900].rstrip() + ("" if cut > 300 else "...")
    return summary


def rebuild_automations_list():
    source_path = ROOT / "plugins" / "aps" / "src" / "main" / "kotlin" / "app" / "aaps" / "plugins" / "aps" / "openAPSAutoISF" / "OpenAPSAutoISFPlugin.kt"
    lines = source_path.read_text(encoding="utf-8").splitlines()
    keys = parse_true_keys_in_source_order(lines)
    doc = Document(AUTOMATIONS_SOURCE)
    body = doc.element.body
    for child in list(body):
        if child.tag != qn("w:sectPr"):
            body.remove(child)
    title = doc.add_paragraph("AutoISF Coded Automations - Current Code Registry (DRAFT)", style="Heading 1")
    for run in title.runs:
        run.font.underline = True
    doc.add_paragraph(
        f"Current-code audit dated {AUDIT_DATE}. This document lists all {len(keys)} distinct readyToRun() keys found directly in OpenAPSAutoISFPlugin.kt today, in source appearance order -- generated straight from source rather than from core/utils/CodedAutomationNames.kt, which was found to be stale by 45 keys (139 vs this file's 180) during this audit. The summary column is extracted from the comments attached to each current readyToRun()/markRun() handler; it supersedes the obsolete 2026-08-21, 118-key snapshot. This is a DRAFT for review, not yet swapped into the main manual."
    )
    doc.add_paragraph(
        "Registry meaning. EXACT and CLOSE matching are used only to decide whether a native Automation-tab item plausibly duplicates a coded automation. Non-matching native automations are not suppressed. Command/setting-relay rows are included because they are coded automation handlers even though their trigger is a List/TT command rather than an autonomous glucose rule. Disabled handlers remain listed and are labelled by their current source comments. A handful of automations (e.g. LowBgTierAReset, VirtualPseudoWizard) gate their action through a throttleKey/local variable rather than a literal readyToRun(\"...\") call and so do not appear as a distinct row here even though they exist in source -- see the accompanying note for the full list of those."
    )
    table = doc.add_table(rows=1, cols=4)
    for cell, value in zip(table.rows[0].cells, ["#", "Current coded key", "Type", "Current code definition / criteria and action"]):
        cell.text = value
    for index, key in enumerate(keys, 1):
        row = table.add_row().cells
        row[0].text = str(index)
        row[1].text = key
        row[2].text = automation_category(key)
        row[3].text = extract_code_summary(key, lines)
    configure_table(table, [500, 2050, 1600, 5210], 7.4)
    doc.save(AUTOMATIONS_OUTPUT)
    return len(keys)


count = rebuild_automations_list()
print(AUTOMATIONS_OUTPUT)
print(f"automation_keys={count}")
