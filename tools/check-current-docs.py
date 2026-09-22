"""Offline checks for current entry points; never connects to models or databases."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
current = [ROOT / "README.md"] + [p for p in (ROOT / "docs").glob("*.md")
    if not p.read_text(encoding="utf-8").startswith("> 历史版本/阶段资料")]
for path in current:
    text = path.read_text(encoding="utf-8")
    for link in re.findall(r"\]\(([^)]+)\)", text):
        if "://" in link or link.startswith("#"):
            continue
        target = link.split("#", 1)[0]
        assert (path.parent / target).exists(), (path, link)
    assert "POST /api/incidents" not in text, path
    assert "当前包括 Incident Investigation" not in text, path
    assert "USER,INCIDENT_OPERATOR" not in text, path
    assert "docs/sql/ordercare" not in text or "退役" in text, path

retired_sql = list((ROOT / "docs/sql").glob("ordercare-*.sql")) + [
    ROOT / "docs/sql/unified-agent-workbench-m1-c.sql"]
for path in retired_sql:
    assert all(not line.strip() or line.lstrip().startswith("--")
               for line in path.read_text(encoding="utf-8").splitlines()), path
assert not (ROOT / "scripts/ordercare/prepare-m05-full-flow.ps1").exists()
assert not (ROOT / "data/rag-docs/ordercare-recovery-sop-v1.md").exists()
assert (ROOT / "data/rag-docs/incident-response.md").exists()
script = (ROOT / "tools/workbench-m3-d-evidence.ps1").read_text(encoding="utf-8")
all_block = script.split('"All" {', 1)[1].split("}", 1)[0]
assert "Invoke-RoutingModelEval" not in all_block
assert "Invoke-FaultRecovery" not in all_block
assert "$env:RAG_POSTGRES_URL))" not in script
assert '[string]$DbUrl = ""' in script
assert "-EnableLiveModelEval" in script and "-IsolatedTestDatabase" in script
print(f"PASS: {len(current)} current documents, local links, retired SQL/script and corpus boundaries, offline All")
