#!/usr/bin/env python3
"""Verify a live API's executable glibc mappings on its Linux Docker host.

Run with permission to read the process's maps and root. Arguments are the
container's host PID and a JSON object of image-relative ELF paths to SHA-256.
"""
import hashlib
import json
from pathlib import Path
import struct
import sys


def executable_segments(path):
    with path.open("rb") as stream:
        header = stream.read(64)
        if header[:5] != b"\x7fELF\x02" or header[5] not in {1, 2}:
            raise RuntimeError("Expected a 64-bit ELF library")
        endian = "<" if header[5] == 1 else ">"
        machine = struct.unpack_from(endian + "H", header, 18)[0]
        offset = struct.unpack_from(endian + "Q", header, 32)[0]
        entry_size, count = struct.unpack_from(endian + "HH", header, 54)
        segments = []
        for index in range(count):
            stream.seek(offset + index * entry_size)
            kind, flags, file_offset, _, _, file_size, _, _ = struct.unpack(
                endian + "IIQQQQQQ", stream.read(56))
            if kind == 1 and flags & 1 and file_size:
                segments.append((file_offset, file_offset + file_size))
    if not segments:
        raise RuntimeError("ELF executable load segments missing")
    return machine, segments


def verify_process(pid, expected, proc_root=Path("/proc")):
    if pid <= 0 or len(expected) != 2:
        raise RuntimeError("Provide the API host PID and both expected glibc ELF digests")
    process = proc_root / str(pid)
    command = (process / "cmdline").read_bytes().split(b"\0")
    if not any(Path(word.decode()).name == "java" for word in command if word):
        raise RuntimeError("API Java command missing")
    if b"/app/testhub-platform-api.jar" not in command:
        raise RuntimeError("API JAR argument missing")

    executor = (process / "exe").readlink().name
    qemu_machines = {22: "qemu-s390x", 21: "qemu-ppc64le", 243: "qemu-riscv64",
                     62: "qemu-x86_64", 183: "qemu-aarch64"}
    mappings = {name: [] for name in expected}
    gconv_modules = set()
    for row in (process / "maps").read_text().splitlines():
        fields = row.split(maxsplit=5)
        if len(fields) != 6:
            continue
        name = fields[5].removeprefix("/")
        if "/gconv/" in name and name.endswith(".so"):
            gconv_modules.add(name)
        if name not in expected:
            continue
        start, end = (int(value, 16) for value in fields[0].split("-"))
        offset = int(fields[2], 16)
        mappings[name].append((offset, offset + end - start, fields[1]))

    mapped = {}
    for name, checksum in expected.items():
        source = process / "root" / name
        digest = hashlib.sha256()
        with source.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != checksum:
            raise RuntimeError(f"Mapped ELF differs from verified image: {name}")
        machine, segments = executable_segments(source)
        emulated = executor.startswith("qemu-")
        if emulated and executor != qemu_machines.get(machine):
            raise RuntimeError("QEMU executor does not match ELF machine type")
        # QEMU translates guest instructions into a separate JIT code buffer.
        # Its host mappings of guest executable segments can be read-only.
        permission = "r" if emulated else "x"
        code_maps = [(start, end, flags) for start, end, flags in mappings[name] if permission in flags]
        used_permissions = set()
        for start, end in segments:
            covering = [flags for left, right, flags in code_maps if left <= start and right >= end]
            if not covering:
                raise RuntimeError(f"ELF executable load segment is not mapped by the API: {name}")
            used_permissions.update(covering)
        mapped[name] = {"permissions": sorted(used_permissions),
                        "sha256": digest.hexdigest(),
                        "executable_file_ranges": segments,
                        "mapping_mode": "qemu_file_backed_code" if emulated else "executable_file_mapping"}
    return {"mapped_elf": mapped, "host_executor": executor,
            "loaded_gconv_modules": sorted(gconv_modules),
            "api_java_and_jar_arguments_verified": True}


if __name__ == "__main__":
    print(json.dumps(verify_process(int(sys.argv[1]), json.loads(sys.argv[2]))))
