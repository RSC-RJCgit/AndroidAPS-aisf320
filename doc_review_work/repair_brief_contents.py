import copy
import sys
import zipfile
from pathlib import Path

from lxml import etree


W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
NS = {"w": W, "r": R}


def qn(namespace, local):
    return f"{{{namespace}}}{local}"


def paragraph_text(paragraph):
    return "".join(paragraph.xpath(".//w:t/text()", namespaces=NS)).strip()


def heading_level(paragraph):
    style = paragraph.find("w:pPr/w:pStyle", NS)
    if style is None:
        return None
    value = style.get(qn(W, "val"), "")
    if value.lower() == "heading1":
        return 1
    if value.lower() == "heading2":
        return 2
    return None


def clean_heading(text):
    return text.replace("[↑ TOC]", "").strip()


def add_bookmark(paragraph, name, bookmark_id):
    start = etree.Element(qn(W, "bookmarkStart"))
    start.set(qn(W, "id"), str(bookmark_id))
    start.set(qn(W, "name"), name)
    end = etree.Element(qn(W, "bookmarkEnd"))
    end.set(qn(W, "id"), str(bookmark_id))
    ppr = paragraph.find("w:pPr", NS)
    insert_at = 1 if ppr is not None else 0
    paragraph.insert(insert_at, start)
    paragraph.append(end)


def existing_bookmark(paragraph):
    starts = paragraph.xpath(".//w:bookmarkStart[@w:name]", namespaces=NS)
    return starts[0].get(qn(W, "name")) if starts else None


def make_toc_entry(template, text, anchor):
    paragraph = copy.deepcopy(template)
    for hyperlink in paragraph.xpath(".//w:hyperlink", namespaces=NS):
        hyperlink.set(qn(W, "anchor"), anchor)
        texts = hyperlink.xpath(".//w:t", namespaces=NS)
        if texts:
            texts[0].text = text
            if text.startswith(" "):
                texts[0].set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
            for extra in texts[1:]:
                extra.text = ""
        return paragraph
    raise RuntimeError("TOC template has no hyperlink")


def main(source, destination):
    source = Path(source)
    destination = Path(destination)
    with zipfile.ZipFile(source, "r") as zin:
        parts = {name: zin.read(name) for name in zin.namelist()}

    root = etree.fromstring(parts["word/document.xml"])
    body = root.find("w:body", NS)
    paragraphs = [child for child in body if child.tag == qn(W, "p")]

    toc_paragraph = next(
        p for p in paragraphs
        if p.xpath(".//w:bookmarkStart[@w:name='TOC_main']", namespaces=NS)
    )
    toc_index = list(body).index(toc_paragraph)
    section_one = next(
        p for p in list(body)[toc_index + 1:]
        if heading_level(p) == 1 and clean_heading(paragraph_text(p)).startswith("1. Requirements")
    )
    section_one_index = list(body).index(section_one)

    level1_template = next(
        p for p in list(body)[toc_index + 1:section_one_index]
        if paragraph_text(p).startswith("Introduction:")
    )
    level2_template = next(
        p for p in list(body)[toc_index + 1:section_one_index]
        if paragraph_text(p).lstrip().startswith("a. It has a master")
    )

    all_ids = [int(x) for x in root.xpath(".//w:bookmarkStart/@w:id", namespaces=NS) if x.isdigit()]
    next_id = max(all_ids, default=0) + 1

    intro_candidates = [
        p for p in paragraphs[:paragraphs.index(toc_paragraph)]
        if heading_level(p) == 1 and clean_heading(paragraph_text(p)).startswith("Introduction:")
    ]
    intro = intro_candidates[-1]
    screenshots = next(
        p for p in paragraphs[:paragraphs.index(toc_paragraph)]
        if heading_level(p) == 1 and clean_heading(paragraph_text(p)) == "Screenshots"
    )

    current_headings = []
    after_toc = False
    for p in paragraphs:
        if p is section_one:
            after_toc = True
        if not after_toc:
            continue
        level = heading_level(p)
        if level is None:
            continue
        text = clean_heading(paragraph_text(p))
        if text == "End" or not text:
            continue
        current_headings.append((level, text, p))

    selected = [(1, clean_heading(paragraph_text(intro)), intro)] + current_headings + [
        (1, clean_heading(paragraph_text(screenshots)), screenshots)
    ]

    generated = 1
    entries = []
    for level, text, paragraph in selected:
        anchor = existing_bookmark(paragraph)
        if not anchor:
            if text.startswith("Introduction:"):
                anchor = "sec001"
            elif text == "Screenshots":
                anchor = "screenshots"
            elif text.startswith("Appendix G"):
                anchor = "appendix_g_live_settings"
            else:
                anchor = f"toc_current_{generated:03d}"
                generated += 1
            add_bookmark(paragraph, anchor, next_id)
            next_id += 1
        entries.append((level, text, anchor))

    # Preserve older introduction links whose detailed appendix targets were removed during the
    # manual's reorganisation. Image links now land on the retained Screenshots section; the former
    # Automation States appendix summary lands on the current section 1 explanation.
    existing_names = {
        b.get(qn(W, "name")) for b in root.xpath(".//w:bookmarkStart[@w:name]", namespaces=NS)
    }
    for alias in (
        "img_overview_stacked",
        "img_bolus_wizard",
        "img_import_settings",
        "img_list1_popup",
        "img_history_basal",
    ):
        if alias not in existing_names:
            add_bookmark(screenshots, alias, next_id)
            next_id += 1
    if "sec15_automation_states" not in existing_names:
        add_bookmark(section_one, "sec15_automation_states", next_id)
        next_id += 1

    # Remove every stale TOC entry while retaining the Contents paragraph/bookmark itself.
    for child in list(body)[toc_index + 1:section_one_index]:
        body.remove(child)

    insert_at = list(body).index(toc_paragraph) + 1
    for level, text, anchor in entries:
        display = text if level == 1 else f"  {text}"
        paragraph = make_toc_entry(level1_template if level == 1 else level2_template, display, anchor)
        body.insert(insert_at, paragraph)
        insert_at += 1

    parts["word/document.xml"] = etree.tostring(
        root, xml_declaration=True, encoding="UTF-8", standalone="yes"
    )

    # Word image hyperlinks to bookmarks are stored as hyperlink relationships whose target begins
    # with '#'. Marking them External prevents OPC readers from treating '#bookmark' as a missing ZIP
    # part (word/#bookmark) while preserving Word's internal-jump semantics.
    rel_name = "word/_rels/document.xml.rels"
    rel_root = etree.fromstring(parts[rel_name])
    for rel in rel_root.findall(qn(PKG_REL, "Relationship")):
        if rel.get("Type", "").endswith("/hyperlink") and rel.get("Target", "").startswith("#"):
            rel.set("TargetMode", "External")
    parts[rel_name] = etree.tostring(
        rel_root, xml_declaration=True, encoding="UTF-8", standalone="yes"
    )

    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as zout:
        for name, data in parts.items():
            zout.writestr(name, data)

    print(f"wrote\t{destination}")
    print(f"toc_entries\t{len(entries)}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
