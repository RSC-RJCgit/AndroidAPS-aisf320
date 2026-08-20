from docx import Document
from docx.oxml.ns import qn

path = r"C:\winword\aaa\AutoISF Idiosyncrasies and Operations Manual mydoc Aug 21 26 large linked introduction_Aug 21 26_DELL17.docx"
doc = Document(path)
for index, child in enumerate(doc.element.body.iterchildren()):
    tag = child.tag.rsplit("}", 1)[-1]
    if tag == "p":
        value = "".join(child.itertext()).strip()
        if value:
            print(index, "P", repr(value[:260]))
    elif tag == "tbl":
        rows = child.findall(qn("w:tr"))
        cells = rows[0].findall(qn("w:tc")) if rows else []
        first = "".join(cells[0].itertext()) if cells else ""
        print(index, "TABLE", len(rows), len(cells), repr(first[:100]))
