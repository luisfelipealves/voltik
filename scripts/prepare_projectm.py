"""Remove community presets/textures from the pinned AAR before packaging."""
import os
import sys
import zipfile

path = sys.argv[1]
temporary = path + ".filtered"
prefixes = ("assets/presets/", "assets/textures/", "assets/preset-genres/", "assets/preset-attributes/")
files = {"assets/presets.idx"}
removed = 0
with zipfile.ZipFile(path) as source, zipfile.ZipFile(temporary, "w") as target:
    for entry in source.infolist():
        if entry.filename in files or entry.filename.startswith(prefixes):
            removed += 1
        else:
            target.writestr(entry, source.read(entry))
if removed == 0:
    os.remove(temporary)
    raise SystemExit("No preset assets removed; inspect the pinned AAR before distributing it.")
os.replace(temporary, path)
print(f"Removed {removed} community asset entries from projectM AAR")
