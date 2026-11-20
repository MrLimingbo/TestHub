#!/usr/bin/env python3
"""Check the backport against the exact Debian runtime before packaging."""
import re
import subprocess
import sys

EXPORT_COUNTS = {
    "AArch64": 6157,
    "Advanced Micro Devices X86-64": 6166,
    "IBM S/390": 6473,
    "PowerPC64": 6744,
    "RISC-V": 6157,
}
X86_FILEBUF_OVERFLOWS = (
    "_ZNSt13basic_filebufIcSt11char_traitsIcEE8overflowEi",
    "_ZNSt13basic_filebufIwSt11char_traitsIwEE8overflowEj",
)


def readelf(path, *options):
    return subprocess.check_output(["readelf", *options, path], text=True)


def exports(path):
    symbols = {}
    for line in readelf(path, "--dyn-syms", "--wide").splitlines():
        row = re.fullmatch(
            r"\s*\d+:\s+\S+\s+(\d+)\s+(\S+)\s+(\S+)\s+(\S+)\s+"
            r"(?:(\[[^\]]+\])\s+)?(\S+)\s+(\S+)(?: \(\d+\))?", line
        )
        if row:
            size, kind, binding, visibility, architecture_attribute, index, name = row.groups()
            if index != "UND":
                data_size = int(size) if kind in {"OBJECT", "TLS"} else None
                symbols[name] = (kind, binding, visibility, architecture_attribute, data_size)
    return symbols


def runtime_contract(path):
    header = readelf(path, "-hW")
    dynamic = readelf(path, "-dW")
    notes = readelf(path, "-nW")
    segments = readelf(path, "-lW")
    properties = next((section for section in notes.strip().split("\n\n")
                       if section.startswith("Displaying notes found in: .note.gnu.property\n")), "")
    return {
        "elf": re.findall(r"^\s*(?:Class|Data|Type|Machine):\s*(.+)$", header, re.MULTILINE),
        "elf_flags": re.findall(r"^\s*Flags:\s*(.+)$", header, re.MULTILINE),
        "architecture_attributes": readelf(path, "-AW").strip(),
        "soname": re.findall(r"\(SONAME\).*\[([^\]]+)\]", dynamic),
        "needed": sorted(re.findall(r"\(NEEDED\).*\[([^\]]+)\]", dynamic)),
        "glibc": sorted(set(re.findall(r"Name: (GLIBC_\S+)", readelf(path, "-VW")))),
        "gnu_properties": properties,
        "relro": "GNU_RELRO" in segments,
        "nonexec_stack": bool(re.search(r"GNU_STACK .* RW\s", segments)),
    }


def check_systemtap_probes(path):
    probes = sorted(re.findall(r"Provider: (.+)\n\s+Name: (.+)", readelf(path, "-nW")))
    required = [("libstdcxx", probe) for probe in ("catch", "rethrow", "throw")]
    if probes != required:
        raise SystemExit("Required libstdc++ SystemTap exception probes missing")


def check_x86_cet(path):
    for symbol in X86_FILEBUF_OVERFLOWS:
        assembly = subprocess.check_output(["objdump", f"--disassemble={symbol}", path], text=True)
        if not re.search(rf"<{symbol}(?:@@?[^>]+)?>:\n[^\n]*\bendbr64\b", assembly):
            raise SystemExit(f"Missing amd64 CET landing instruction in {symbol}")


def check(reference, candidate):
    expected_contract = runtime_contract(reference)
    actual_contract = runtime_contract(candidate)
    architecture = expected_contract["elf"][-1]
    expected, actual = exports(reference), exports(candidate)
    if len(expected) != EXPORT_COUNTS.get(architecture) or actual != expected:
        missing = sorted(expected.keys() - actual.keys())
        added = sorted(actual.keys() - expected.keys())
        changed = sorted(name for name in actual.keys() & expected.keys() if actual[name] != expected[name])
        raise SystemExit(f"libstdc++ ABI changed: missing {missing[:8]}, added {added[:8]}, changed {changed[:8]}")

    if expected_contract != actual_contract:
        changed = [key for key, value in expected_contract.items() if value != actual_contract[key]]
        raise SystemExit("libstdc++ runtime contract changed: " + ", ".join(changed))
    if (actual_contract["soname"] != ["libstdc++.so.6"]
            or not actual_contract["relro"] or not actual_contract["nonexec_stack"]):
        raise SystemExit("Required libstdc++ identity or hardening attributes missing")
    if architecture == "AArch64":
        if "AArch64 feature: BTI, PAC, GCS" not in actual_contract["gnu_properties"]:
            raise SystemExit("Required arm64 BTI, PAC and GCS attributes missing")
        details = "BTI, PAC, GCS"
    else:
        for path in (reference, candidate):
            check_systemtap_probes(path)
            if architecture == "Advanced Micro Devices X86-64":
                check_x86_cet(path)
        details = "GNU properties, ELF flags, architecture attributes and SystemTap probes"
    print(f"Verified {len(actual)} libstdc++ dynamic exports and matching {details}, RELRO and NX stack")


if __name__ == "__main__":
    check(*sys.argv[1:])
