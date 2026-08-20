from pathlib import Path

from docx import Document
from docx.shared import Pt


SOURCE = Path(r"C:\winword\aaa\AutoISF Operations Manual mydoc Aug 21 26 UKF battery and TT commands.docx")
OUTPUT = Path(r"C:\Users\arjay\StudioProjects\AaAPS3422a320\AutoISF Operations Manual mydoc Aug 21 26 underlined indented headings.docx")

doc = Document(SOURCE)

# Make the hierarchy intrinsic to the heading styles and also apply it directly so existing
# per-paragraph formatting cannot suppress the requested appearance.
doc.styles["Heading 1"].font.underline = True
doc.styles["Heading 2"].paragraph_format.left_indent = Pt(18)
doc.styles["Heading 3"].paragraph_format.left_indent = Pt(36)

active_level = 0
for paragraph in doc.paragraphs:
    style = paragraph.style.name
    if style == "Heading 1":
        active_level = 1
        paragraph.paragraph_format.left_indent = Pt(0)
        for run in paragraph.runs:
            run.font.underline = True
    elif style == "Heading 2":
        active_level = 2
        paragraph.paragraph_format.left_indent = Pt(18)
    elif style == "Heading 3":
        active_level = 3
        paragraph.paragraph_format.left_indent = Pt(36)
    elif style == "Normal":
        # Introduction and Appendix B navigation sit directly below Heading 1 and retain their own
        # existing list/navigation indents. Subheading body text receives one further indentation step.
        if active_level == 2:
            paragraph.paragraph_format.left_indent = Pt(36)
        elif active_level == 3:
            paragraph.paragraph_format.left_indent = Pt(54)

doc.save(OUTPUT)
print(OUTPUT)
