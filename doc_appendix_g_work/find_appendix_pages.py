from pathlib import Path
from pypdf import PdfReader

root = Path(__file__).parent
for dirname in ("render_brief", "render_plain", "render_full"):
    pdf = next((root / dirname).glob("*.pdf"))
    reader = PdfReader(pdf)
    hits = []
    for i, page in enumerate(reader.pages, start=1):
        text = page.extract_text() or ""
        if any(term in text for term in ("Appendix G", "main_phone_snapshot_time", "split_bolus_interval")):
            hits.append(i)
    print(f"{dirname}: pages={len(reader.pages)} appendix_pages={hits}")
