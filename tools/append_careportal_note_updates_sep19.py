from pathlib import Path

from docx import Document
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Pt

SOURCE_COMPLETE = Path(r"C:\winword\ccc\CarePortal Note Code mydoc complete Latest.docx")
SOURCE_SHRINK = Path(r"C:\winword\ccc\CarePortal Note Code mydoc shrink Latest.docx")
OUTPUT_COMPLETE = Path(r"C:\winword\aaa\AutoISF CarePortal Note Code Full Reference mydoc Sep 19 26 DRAFT updates.docx")
OUTPUT_SHRINK = Path(r"C:\winword\aaa\AutoISF CarePortal Note Code Abbreviated Reference mydoc Sep 19 26 DRAFT updates.docx")

# (code, description) -- 9 codes confirmed present in OpenAPSAutoISFPlugin.kt today (2026-09-19 audit)
# but absent from both existing CarePortal note-code docs. Source line references in the audit
# writeup; not repeated here to keep the table itself clean.
NEW_ENTRIES = [
    ("LoReb", "recentLowReboundGuardFiredThisCycle (DetermineBasalAutoISF.kt): halves microBolus after a "
              "recent low; no throttle, 60-minute lookback."),
    ("bat>1", "\"AllOK Batt\": battery has recovered back above the critical 1% threshold; clears the "
              "low-battery notification and automation state."),
    ("Bt<1%", "Battery critical: below 1%. Raises an urgent notification and a graph annotation."),
    ("MJs2", "List1 direct action (MjStateMj2TT): manually sets MJ state to MJ2."),
    ("MJsAc", "List1 direct action (MjStateActiveTT): manually sets MJ state to MJ active."),
    ("MoreMJ2", "Hypoalarm-path variant of MoreMJ: advances MJ state to MJ2 specifically when a hypo alarm "
                "is active (more cautious than the plain MoreMJ progression)."),
    ("EDSR", "EarlyDawnSlowRise (04:30-08:00): sustained slow-rise entry criteria; action is SMBdel +0.5, "
             "a 30-minute switch to the TierC-slot profile, and a 30-minute TT."),
    ("LStOn", "ApsAutoIsfUseLiveStepsOnVirtual toggled ON: VirtualPump now mirrors the loop phone's live "
              "step counts instead of reading its own (non-existent) local pedometer."),
    ("LStOff", "ApsAutoIsfUseLiveStepsOnVirtual toggled OFF: VirtualPump reverts to its own local step "
               "source."),
]

NOTE_ON_EXCLUDED = (
    "Not included above: the three \"VirtualPseudoWizard FAILED/SUCCESS ...\" notes. These are full "
    "sentence-length SMS-style notes built with String.format()/interpolated result text, not short "
    "fixed codes, so they don't fit this table's Code/Description convention -- flagged here for "
    "awareness rather than added as a row."
)


def set_cell_width(cell, width):
    tc_pr = cell._tc.get_or_add_tcPr()
    tc_w = tc_pr.find(qn("w:tcW"))
    if tc_w is None:
        tc_w = OxmlElement("w:tcW")
        tc_pr.append(tc_w)
    tc_w.set(qn("w:w"), str(width))
    tc_w.set(qn("w:type"), "dxa")


def configure_table(table, widths, font_size=9.0):
    table.autofit = False
    table.style = "Normal Table"
    tbl_pr = table._tbl.tblPr
    tbl_w = tbl_pr.find(qn("w:tblW"))
    if tbl_w is None:
        tbl_w = OxmlElement("w:tblW")
        tbl_pr.append(tbl_w)
    tbl_w.set(qn("w:w"), str(sum(widths)))
    tbl_w.set(qn("w:type"), "dxa")
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


def append_updates(source_path: Path, output_path: Path):
    doc = Document(source_path)
    heading = doc.add_paragraph("New/updated entries -- 2026-09-19 audit (DRAFT, not yet merged into the main alphabetical table above)", style="Heading 2")
    for run in heading.runs:
        run.font.underline = True
    doc.add_paragraph(
        f"Direct extraction of every addCarePortalNote()/compactSettingNote() code currently in "
        f"OpenAPSAutoISFPlugin.kt found {len(NEW_ENTRIES)} codes genuinely in source today but not yet "
        f"present in this document. Everything else already checked (142 of 152 extracted codes) was "
        f"already present and correct -- this is a small patch, not a rebuild."
    )
    table = doc.add_table(rows=1, cols=2)
    for cell, value in zip(table.rows[0].cells, ["Code", "Description"]):
        cell.text = value
    for code, desc in NEW_ENTRIES:
        row = table.add_row().cells
        row[0].text = code
        row[1].text = desc
    configure_table(table, [1400, 8100])
    doc.add_paragraph(NOTE_ON_EXCLUDED)
    doc.save(output_path)


append_updates(SOURCE_COMPLETE, OUTPUT_COMPLETE)
append_updates(SOURCE_SHRINK, OUTPUT_SHRINK)
print(OUTPUT_COMPLETE)
print(OUTPUT_SHRINK)
