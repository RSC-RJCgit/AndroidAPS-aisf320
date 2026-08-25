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


def text(p):
    return "".join(p.xpath(".//w:t/text()", namespaces=NS)).replace("[↑ TOC]", "").strip()


def level(p):
    style = p.find("w:pPr/w:pStyle", NS)
    if style is None:
        return None
    return {"heading1": 1, "heading2": 2, "heading3": 3}.get(style.get(qn(W, "val"), "").lower())


def bookmark(p):
    starts = p.xpath(".//w:bookmarkStart[@w:name]", namespaces=NS)
    return starts[0].get(qn(W, "name")) if starts else None


def add_bookmark(p, name, ident):
    start = etree.Element(qn(W, "bookmarkStart"))
    start.set(qn(W, "id"), str(ident))
    start.set(qn(W, "name"), name)
    end = etree.Element(qn(W, "bookmarkEnd"))
    end.set(qn(W, "id"), str(ident))
    ppr = p.find("w:pPr", NS)
    p.insert(1 if ppr is not None else 0, start)
    p.append(end)


def toc_entry(template, label, anchor):
    p = copy.deepcopy(template)
    link = p.find(".//w:hyperlink", NS)
    link.set(qn(W, "anchor"), anchor)
    nodes = link.xpath(".//w:t", namespaces=NS)
    nodes[0].text = label
    if label.startswith(" "):
        nodes[0].set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
    for node in nodes[1:]:
        node.text = ""
    return p


def main(source, destination):
    with zipfile.ZipFile(source) as zin:
        parts = {name: zin.read(name) for name in zin.namelist()}
    root = etree.fromstring(parts["word/document.xml"])
    body = root.find("w:body", NS)
    children = list(body)
    paras = [p for p in children if p.tag == qn(W, "p")]

    # In the full manual, TOC_main is deliberately placed on the opening heading,
    # while the visible Contents list starts later after the screenshot section.
    toc_start = next(
        p for p in paras
        if text(p).startswith("Introduction: Most significant")
        and p.xpath(".//w:hyperlink[@w:anchor='sec001']", namespaces=NS)
    )
    section1 = next(p for p in paras[paras.index(toc_start) + 1:] if level(p) == 1 and text(p).startswith("1. Requirements"))
    start_i, section1_i = children.index(toc_start), children.index(section1)
    toc_block = children[start_i:section1_i]
    l1_template = toc_start
    l2_template = next(p for p in toc_block if text(p).lstrip().startswith("a. It has a master"))

    used_ids = [int(v) for v in root.xpath(".//w:bookmarkStart/@w:id", namespaces=NS) if v.isdigit()]
    next_id = max(used_ids, default=0) + 1
    before_toc = paras[:paras.index(toc_start)]
    intro = [p for p in before_toc if level(p) == 1 and text(p).startswith("Introduction:")][-1]
    screenshots = next(p for p in before_toc if level(p) == 1 and text(p) == "Screenshots")

    selected = [(1, text(intro), intro)]
    in_main = False
    for p in paras:
        if p is section1:
            in_main = True
        if not in_main:
            continue
        if level(p) == 1 and text(p).startswith("Appendix C"):
            break
        if level(p) in (1, 2) and text(p):
            selected.append((level(p), text(p), p))

    def find_heading(prefix, wanted_level=None):
        return next(p for p in paras if (wanted_level is None or level(p) == wanted_level) and text(p).startswith(prefix))

    appendix_specs = [
        (1, "Appendix C", "Appendix C — Contents"),
        (2, "List 1 and List 2 command/temporary-target reference", "a. List 1 and List 2 command/temporary-target reference"),
        (2, "AutoISF Coded Automations - Current Code Registry", "b. AutoISF Coded Automations - Current Code Registry"),
        (1, "Appendix A", None), (2, "a. Version tag", None), (2, "b. Per-device backup", None),
        (1, "Appendix B", None), (1, "Appendix D", None), (1, "Appendix E", None),
        (1, "Appendix F", None), (2, "a. MJ", None), (2, "b. Steroids", None),
        (2, "c. LowBG", None), (2, "d. Steps", None), (2, "e. Profile", None),
        (2, "f. Sleeping", None), (2, "g. Removed 2026-08-22", None), (1, "Appendix G", None),
    ]
    for lev, prefix, display in appendix_specs:
        target_level = None if prefix in ("List 1 and List 2 command/temporary-target reference", "AutoISF Coded Automations - Current Code Registry") else lev
        p = find_heading(prefix, target_level)
        selected.append((lev, display or text(p), p))

    generated = 1
    entries = []
    for lev, label, p in selected:
        anchor = bookmark(p)
        if not anchor:
            anchor = "appendix_g_live_settings" if label.startswith("Appendix G") else f"toc_manual_{generated:03d}"
            generated += 1
            add_bookmark(p, anchor, next_id)
            next_id += 1
        entries.append((lev, label, anchor))

    names = {b.get(qn(W, "name")) for b in root.xpath(".//w:bookmarkStart[@w:name]", namespaces=NS)}
    image_aliases = {link.get(qn(W, "anchor")) for link in root.xpath(".//w:hyperlink[@w:anchor]", namespaces=NS) if link.get(qn(W, "anchor"), "").startswith("img_")}
    for alias in sorted(image_aliases - names):
        add_bookmark(screenshots, alias, next_id)
        next_id += 1
    if "Top" not in names:
        add_bookmark(paras[0], "Top", next_id)

    for child in toc_block:
        body.remove(child)
    insert_at = start_i
    for lev, label, anchor in entries:
        display = label if lev == 1 else f"  {label}"
        body.insert(insert_at, toc_entry(l1_template if lev == 1 else l2_template, display, anchor))
        insert_at += 1

    parts["word/document.xml"] = etree.tostring(root, xml_declaration=True, encoding="UTF-8", standalone="yes")
    rel_name = "word/_rels/document.xml.rels"
    rels = etree.fromstring(parts[rel_name])
    for rel in rels.findall(qn(PKG_REL, "Relationship")):
        if rel.get("Type", "").endswith("/hyperlink") and rel.get("Target", "").startswith("#"):
            rel.set("TargetMode", "External")
    parts[rel_name] = etree.tostring(rels, xml_declaration=True, encoding="UTF-8", standalone="yes")

    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as zout:
        for name, data in parts.items():
            zout.writestr(name, data)
    print(f"wrote\t{destination}")
    print(f"toc_entries\t{len(entries)}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
