#!/usr/bin/env python3
"""Compare the backported Debian libc and its loader with the pinned originals."""
from pathlib import Path
import re
import runpy
import sys

elf = runpy.run_path(str(Path(__file__).with_name("check-gcc-filebuf-abi.py")))
exports = elf["exports"]
readelf = elf["readelf"]
runtime_contract = elf["runtime_contract"]


def version_definitions(path):
    sections = readelf(path, "-VW").split("Version definition section", 1)
    if len(sections) == 1:
        return []
    return [
        line.split(":", 1)[-1].strip()
        for line in sections[1].splitlines()
        if "Flags:" in line or re.search(r"Parent \d+:", line)
    ]


def check(reference, candidate):
    expected, current = exports(reference), exports(candidate)
    if not expected or expected != current:
        missing = sorted(expected.keys() - current.keys())
        added = sorted(current.keys() - expected.keys())
        changed = sorted(name for name in expected.keys() & current.keys() if expected[name] != current[name])
        raise SystemExit(f"glibc exports changed: missing {missing[:8]}, added {added[:8]}, changed {changed[:8]}")

    old_contract, new_contract = runtime_contract(reference), runtime_contract(candidate)
    differences = [field for field in old_contract if old_contract[field] != new_contract[field]]
    if differences:
        raise SystemExit("glibc ELF contract changed: " + ", ".join(differences))
    if not new_contract["relro"] or not new_contract["nonexec_stack"] or not new_contract["soname"]:
        raise SystemExit("Missing glibc SONAME, RELRO or non-executable stack")
    if version_definitions(reference) != version_definitions(candidate):
        raise SystemExit("glibc version definitions changed")

    print(f"Verified {len(current)} versioned exports and ELF properties for {Path(reference).name}")


if __name__ == "__main__":
    check(*sys.argv[1:])
