import sys
import zipfile

from lxml import etree


path = sys.argv[1]
with zipfile.ZipFile(path) as archive:
    root = etree.fromstring(archive.read("word/document.xml"))

ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
w = ns["w"]
bookmarks = {
    element.get(f"{{{w}}}name")
    for element in root.xpath(".//w:bookmarkStart", namespaces=ns)
}

print(f"bookmarks\t{len(bookmarks)}")
print("hyperlinks")
for index, hyperlink in enumerate(root.xpath(".//w:hyperlink[@w:anchor]", namespaces=ns)):
    text = "".join(hyperlink.xpath(".//w:t/text()", namespaces=ns))
    anchor = hyperlink.get(f"{{{w}}}anchor")
    status = "OK" if anchor in bookmarks else "BROKEN"
    print(f"{index}\t{status}\t{text}\t{anchor}")

print("headings")
for index, paragraph in enumerate(root.xpath(".//w:p", namespaces=ns)):
    style_element = paragraph.find("w:pPr/w:pStyle", ns)
    style = style_element.get(f"{{{w}}}val") if style_element is not None else ""
    if style and style.lower().startswith("heading"):
        text = "".join(paragraph.xpath(".//w:t/text()", namespaces=ns))
        print(f"{index}\t{style}\t{text}")

print("paragraphs")
for index, paragraph in enumerate(root.xpath(".//w:p", namespaces=ns)):
    style_element = paragraph.find("w:pPr/w:pStyle", ns)
    style = style_element.get(f"{{{w}}}val") if style_element is not None else ""
    text = "".join(paragraph.xpath(".//w:t/text()", namespaces=ns))
    anchors = [h.get(f"{{{w}}}anchor") for h in paragraph.xpath(".//w:hyperlink[@w:anchor]", namespaces=ns)]
    names = [b.get(f"{{{w}}}name") for b in paragraph.xpath(".//w:bookmarkStart", namespaces=ns)]
    if index < 230:
        print(f"{index}\t{style}\t{text}\tanchors={anchors}\tbookmarks={names}")
