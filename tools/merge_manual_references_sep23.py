from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from copy import deepcopy
from datetime import datetime
import json
from lxml import etree as E

ROOT = Path(r'C:\winword\aaa')
WORK = Path(r'C:\Users\arjay\AppData\Local\Temp\autoisf-manual-v32')
BASE = ROOT / 'AutoISF Operations FULL Manual revised Monday, September 21st, 2026 mydoc v31 0028 fully sorted careportal tables.docx'
CARE = ROOT / 'AutoISF CarePortal Note Code Full Reference mydoc Sep 20 26 DRAFT updates v4 2143.docx'
AUTO = ROOT / 'AutoISF Automations List mydoc Sep 21 26 0009 code registry triggers v12.docx'
W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
NS = {'w': W, 'r': R}
def q(s): return '{'+W+'}'+s
def text(e): return ''.join(e.xpath('.//w:t/text()', namespaces=NS))
def xml(b): return E.fromstring(b)
def encode(e): return E.tostring(e, xml_declaration=True, encoding='UTF-8', standalone=True)
def para(t, style=None):
    p=E.Element(q('p'))
    if style:
        E.SubElement(E.SubElement(p,q('pPr')),q('pStyle')).set(q('val'),style)
    E.SubElement(E.SubElement(p,q('r')),q('t')).text=t
    return p

with ZipFile(BASE) as z: parts={n:z.read(n) for n in z.namelist()}
doc=xml(parts['word/document.xml']); body=doc.find(q('body'))
styles=xml(parts['word/styles.xml'])
audit={}
def imported(path,prefix):
    with ZipFile(path) as z:
        src=xml(z.read('word/document.xml')); ss=xml(z.read('word/styles.xml'))
    nodes=[deepcopy(e) for e in src.find(q('body')) if e.tag!=q('sectPr')]
    nodes=nodes[1:]  # Keep the manual's own section heading.
    while nodes and nodes[-1].tag==q('p') and not text(nodes[-1]).strip(): nodes.pop()
    mapping={s.get(q('styleId')):prefix+s.get(q('styleId')) for s in ss.findall(q('style'))}
    for s in ss.findall(q('style')):
        s=deepcopy(s); s.set(q('styleId'),mapping[s.get(q('styleId'))]); s.attrib.pop(q('default'),None)
        name=s.find(q('name'))
        if name is not None: name.set(q('val'),prefix+name.get(q('val')))
        for e in s.iter():
            if e.tag in [q('basedOn'),q('next'),q('link')] and e.get(q('val')) in mapping:
                e.set(q('val'),mapping[e.get(q('val'))])
        styles.append(s)
    for node in nodes:
        assert not node.xpath('.//@r:id|.//@r:embed|.//@r:link|.//w:numId|.//w:footnoteReference|.//w:endnoteReference',namespaces=NS)
        for e in node.iter():
            if e.tag in [q('pStyle'),q('rStyle'),q('tblStyle')] and e.get(q('val')) in mapping:
                e.set(q('val'),mapping[e.get(q('val'))])
        # Imported tables must fit the full manual's 7-inch text area.
        for tbl in ([node] if node.tag==q('tbl') else node.findall('.//'+q('tbl'))):
            grid=tbl.find(q('tblGrid')); widths=[int(c.get(q('w'))) for c in grid]
            scale=min(1.0,10080/sum(widths))
            for c in grid: c.set(q('w'),str(round(int(c.get(q('w')))*scale)))
            for cw in tbl.findall('.//'+q('tcW')):
                if cw.get(q('type'))=='dxa': cw.set(q('w'),str(round(int(cw.get(q('w')))*scale)))
            pr=tbl.find(q('tblPr'))
            tw=pr.find(q('tblW'))
            if tw is not None: tw.set(q('w'),'10080');tw.set(q('type'),'dxa')
            for height in tbl.findall('.//'+q('trHeight')):
                if height.get(q('hRule'))=='exact':height.set(q('hRule'),'atLeast')
    audit[prefix]={'source':str(path),'table_rows':[len(n.findall(q('tr'))) for n in nodes if n.tag==q('tbl')]}
    return nodes

def replace_section(start_text,end_text,nodes):
    start=next(i for i,e in enumerate(body) if e.tag==q('p') and text(e).startswith(start_text))
    end=next(i for i,e in enumerate(body) if i>start and e.tag==q('p') and text(e).startswith(end_text))
    heading=body[start]
    # Preserve targets of existing links into the replaced material.
    for e in list(body)[start+1:end]:
        for mark in e.xpath('.//w:bookmarkStart|.//w:bookmarkEnd',namespaces=NS):heading.append(deepcopy(mark))
        body.remove(e)
    for offset,n in enumerate(nodes,1):body.insert(start+offset,n)

care=imported(CARE,'CareRef_')
care.insert(0,para('Full reference supplied as DRAFT updates v4, 20 September 2026 at 21:43. This replaces the previous compact and full CarePortal tables.'))
replace_section('Appendix D — Alphabetical CarePortal Note Code Reference','Appendix E —',care)
autos=imported(AUTO,'AutoRef_')
autos.insert(0,para('Triggers registry DRAFT v12, 21 September 2026 at 00:09. The 183 coded automations are listed alphabetically.'))
replace_section('AutoISF Coded Automations - Current Code Registry','Appendix A —',autos)

now=datetime.now()
version=f'Version 32 | Updated {now:%d %B %Y at %H:%M}'
body.insert(1,para(version))
# Repair existing fragment-only hyperlink relationships so standard DOCX readers can open the file.
rels=xml(parts['word/_rels/document.xml.rels']);repaired=[]
for rel in list(rels):
    target=rel.get('Target','')
    if target.startswith('#') and rel.get('Type','').endswith('/hyperlink'):
        for link in doc.xpath('//w:hyperlink[@r:id=$rid]',namespaces=NS,rid=rel.get('Id')):
            link.attrib.pop('{'+R+'}id',None);link.set(q('anchor'),target[1:])
        repaired.append(target);rels.remove(rel)
parts['word/_rels/document.xml.rels']=encode(rels)
parts['word/document.xml']=encode(doc);parts['word/styles.xml']=encode(styles)
name=f'AutoISF Operations FULL Manual mydoc {now:%b %d %y} v32 {now:%H%M} CarePortal and Automations references.docx'
out=WORK/name
with ZipFile(out,'w',ZIP_DEFLATED) as z:
    for n,b in parts.items():z.writestr(n,b)
# Verify every source table cell is present, in order, after the transplant.
for path in [CARE,AUTO]:
    with ZipFile(path) as z: src=xml(z.read('word/document.xml'))
    table=src.find('.//'+q('tbl')); expected=table.xpath('.//w:t/text()',namespaces=NS)
    assert any(t.xpath('.//w:t/text()',namespaces=NS)==expected for t in doc.findall('.//'+q('tbl'))),path
assert len(doc.xpath('//w:drawing',namespaces=NS))==33
from docx import Document
Document(out)  # The repaired OPC relationships must be readable.
audit.update(output=str(out),version=version,repaired_fragment_links=repaired)
(WORK/'merge-audit.json').write_text(json.dumps(audit,indent=2),encoding='utf-8')
print(json.dumps(audit,indent=2))
