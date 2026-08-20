from pathlib import Path

from docx import Document
from docx.shared import Pt


SOURCE = Path(r"C:\winword\aaa\AutoISF Operations Manual mydoc Aug 21 26 List confirmation popups documented.docx")
OUTPUT = Path("AutoISF Operations Manual mydoc Aug 21 26 subheading line spacing standardized.docx")
SUBHEADING_STYLES = ("Heading 2", "Heading 3")


document = Document(SOURCE)

# A full 12-point line of white space above and below every subheading. Apply it
# to both the styles and existing paragraphs so old direct formatting cannot
# leave individual headings inconsistent.
for style_name in SUBHEADING_STYLES:
    style = document.styles[style_name]
    style.paragraph_format.space_before = Pt(12)
    style.paragraph_format.space_after = Pt(12)
    style.paragraph_format.keep_with_next = True

changed = 0
for paragraph in document.paragraphs:
    if paragraph.style.name in SUBHEADING_STYLES:
        paragraph.paragraph_format.space_before = Pt(12)
        paragraph.paragraph_format.space_after = Pt(12)
        paragraph.paragraph_format.keep_with_next = True
        changed += 1

document.save(OUTPUT)
print(OUTPUT.resolve())
print(f"subheadings={changed}")
