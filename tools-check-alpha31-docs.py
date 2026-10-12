"""Release-doc contract for the alpha.31 containment milestone."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent
checks = {
    "gradle.properties": ["version=0.1.0-alpha.31"],
    "README.md": ["0.1.0-alpha.31", "CONTAINMENT-UPDATE.md", "GUI"],
    "CHANGELOG.md": ["0.1.0-alpha.31", "nitrogen", "airlock"],
    "CONTAINMENT-UPDATE.md": ["Worked example", "concrete basin", "enclosed suppression tank", "corium", "GUI"],
    "PROJECT-LOG.md": ["alpha.31", "Phase F"],
    "DISCORD-CHANGELOG-ALPHA31.txt": ["alpha.31", "vault door", "wetwell vent"],
}
problems = []
for name, terms in checks.items():
    path = ROOT / name
    if not path.exists():
        problems.append(f"missing {name}")
        continue
    text = path.read_text(encoding="utf-8").lower()
    problems.extend(f"{name} missing {term}" for term in terms if term.lower() not in text)
if problems:
    raise SystemExit("\n".join(problems))
print("alpha.31 release docs: OK")
