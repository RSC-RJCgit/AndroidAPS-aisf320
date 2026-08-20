from copy import deepcopy
from pathlib import Path

from docx import Document


SOURCE = Path(r"C:\winword\aaa\AutoISF Idiosyncrasies and Operations Manual mydoc Aug 21 26 introduction then contents.docx")
OUTPUT = Path(r"C:\Users\arjay\StudioProjects\AaAPS3422a320\AutoISF_Operations_Manual_mydoc_Aug21_2026_intro_automation_items.docx")

doc = Document(SOURCE)
existing = next(p for p in doc.paragraphs if p.text == "Self-healing coded Automation States.")
existing.text = "This is now self-healing for the coded automations' own states."

mechanism = existing.insert_paragraph_before("Mechanism For Ignoring Or Invoking Native Automations.", style="Normal")
num_pr = existing._p.xpath("./w:pPr/w:numPr")
if num_pr:
    mechanism._p.get_or_add_pPr().append(deepcopy(num_pr[0]))

doc.save(OUTPUT)
print(OUTPUT)
