"""Regression coverage for live API mapping evidence."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import struct
import unittest


SPEC = importlib.util.spec_from_file_location(
    "verify_api_glibc_maps", Path(__file__).with_name("verify-api-glibc-maps.py"))
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class ProcessMapsTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.proc = Path(self.work.name)
        self.process = self.proc / "42"
        self.process.mkdir()
        (self.process / "cmdline").write_bytes(
            b"/usr/bin/qemu-s390x\0/usr/bin/java\0-jar\0/app/testhub-platform-api.jar\0")
        (self.process / "exe").symlink_to("/usr/bin/java")
        self.expected = {}
        rows = []
        for name in ["usr/lib/s390x-linux-gnu/libc.so.6", "usr/lib/s390x-linux-gnu/ld64.so.1"]:
            file = self.process / "root" / name
            file.parent.mkdir(parents=True, exist_ok=True)
            header = bytearray(512)
            header[:6] = b"\x7fELF\x02\x02"
            struct.pack_into(">H", header, 18, 22)
            struct.pack_into(">Q", header, 32, 64)
            struct.pack_into(">HH", header, 54, 56, 1)
            struct.pack_into(">IIQQQQQQ", header, 64, 1, 5, 256, 4096, 0, 32, 32, 4096)
            file.write_bytes(header)
            self.expected[name] = hashlib.sha256(header).hexdigest()
            rows.append(f"1000-2000 r-xp 0000 00:00 1 /{name}")
        (self.process / "maps").write_text("\n".join(rows))

    def verify(self):
        return VERIFY.verify_process(42, self.expected, self.proc)

    def test_native_api_matches_both_executable_files(self):
        result = self.verify()
        self.assertEqual(set(result["mapped_elf"]), set(self.expected))
        self.assertTrue(result["api_java_and_jar_arguments_verified"])

    def test_little_endian_elf_segments(self):
        for name in self.expected:
            file = self.process / "root" / name
            header = bytearray(file.read_bytes())
            header[5] = 1
            struct.pack_into("<H", header, 18, 62)
            struct.pack_into("<Q", header, 32, 64)
            struct.pack_into("<HH", header, 54, 56, 1)
            struct.pack_into("<IIQQQQQQ", header, 64, 1, 5, 256, 4096, 0, 32, 32, 4096)
            file.write_bytes(header)
            self.expected[name] = hashlib.sha256(header).hexdigest()
        self.verify()

    def test_gconv_cache_is_separate_from_loaded_converter_libraries(self):
        file = self.process / "maps"
        file.write_text(file.read_text() + "\n1000-2000 r--p 0000 00:00 1 /usr/lib/gconv/gconv-modules.cache")
        self.assertEqual(self.verify()["loaded_gconv_modules"], [])

    def test_changed_library_is_rejected(self):
        (self.process / "root" / next(iter(self.expected))).write_bytes(b"original library")
        with self.assertRaisesRegex(RuntimeError, "differs from verified image"):
            self.verify()

    def test_nonexecutable_mapping_is_rejected(self):
        file = self.process / "maps"
        file.write_text(file.read_text().replace("r-xp", "r--p"))
        with self.assertRaisesRegex(RuntimeError, "executable load segment"):
            self.verify()

    def emulate(self, executor):
        (self.process / "exe").unlink()
        (self.process / "exe").symlink_to("/usr/bin/" + executor)
        file = self.process / "maps"
        file.write_text(file.read_text().replace("r-xp", "r--p"))

    def test_qemu_readonly_guest_code_mapping_is_accepted(self):
        self.emulate("qemu-s390x")
        result = self.verify()
        for library in result["mapped_elf"].values():
            self.assertEqual(library["mapping_mode"], "qemu_file_backed_code")
            self.assertEqual(library["permissions"], ["r--p"])

    def test_qemu_machine_mismatch_is_rejected(self):
        self.emulate("qemu-riscv64")
        with self.assertRaisesRegex(RuntimeError, "ELF machine type"):
            self.verify()

    def test_qemu_header_only_mapping_is_rejected(self):
        self.emulate("qemu-s390x")
        file = self.process / "maps"
        file.write_text(file.read_text().replace("1000-2000", "1000-1010"))
        with self.assertRaisesRegex(RuntimeError, "executable load segment"):
            self.verify()

    def test_unrelated_java_process_is_rejected(self):
        (self.process / "cmdline").write_bytes(b"java\0-jar\0/other.jar\0")
        with self.assertRaisesRegex(RuntimeError, "API JAR argument missing"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
