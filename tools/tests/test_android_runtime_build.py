import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SPEC = importlib.util.spec_from_file_location("runtime_build", Path(__file__).parents[1] / "build-android-runtime.py")
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)


class NativeRuntimeBuildTest(unittest.TestCase):
    def test_release_inputs_and_source_patches_are_pinned(self):
        lock = json.loads((BUILD.NATIVE / "source-lock.json").read_text())
        self.assertEqual("arm64-v8a", lock["abi"])
        self.assertEqual(30, lock["api"])
        self.assertEqual("28.2.13676358", lock["ndkVersion"])
        for source in lock["sources"]:
            self.assertTrue(source["url"].startswith("https://"))
            self.assertRegex(source["sha256"], r"^[a-f0-9]{64}$")
            self.assertGreater(source["archiveBytes"], 0)
        for change in lock["patches"]:
            self.assertEqual(change["sha256"], BUILD.digest(BUILD.NATIVE / change["path"]))

    def test_source_zip_rejects_path_escape(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source.zip"
            with zipfile.ZipFile(source, "w") as data:
                data.writestr("../escape.c", "bad")
            with self.assertRaisesRegex(RuntimeError, "Unexpected source archive path"):
                BUILD.extract(source, {"format": "zip", "directory": "source"}, root / "work")
            self.assertFalse((root / "escape.c").exists())

    def test_source_documentation_symlink_is_not_materialized(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source.zip"
            with zipfile.ZipFile(source, "w") as data:
                entry = zipfile.ZipInfo("source/CLAUDE.md")
                entry.external_attr = 0o120777 << 16
                data.writestr(entry, "AGENTS.md")
                data.writestr("source/main.c", "int main(void){return 0;}")
            BUILD.extract(source, {"format": "zip", "directory": "source"}, root / "work")
            self.assertFalse((root / "work/source/CLAUDE.md").exists())
            self.assertTrue((root / "work/source/main.c").is_file())

    def test_cached_source_hash_failure_is_not_silently_replaced(self):
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp)
            source = {"name": "test", "sha256": "a" * 64, "format": "zip", "archiveBytes": 3}
            file = cache / ("test-" + "a" * 64 + ".zip")
            file.write_bytes(b"bad")
            with self.assertRaisesRegex(RuntimeError, "integrity verification"):
                BUILD.archive(source, cache, True)
            self.assertEqual(b"bad", file.read_bytes())

    def test_offline_missing_source_fails_before_network(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = {"name": "test", "sha256": "a" * 64, "format": "zip"}
            with self.assertRaisesRegex(RuntimeError, "offline cache"):
                BUILD.archive(source, Path(tmp), True)

    def test_loader_workaround_offset_uses_official_symbols(self):
        output = "1: 0000005400000000 0 NOTYPE GLOBAL DEFAULT 1 _start\n2: 0000005400000038 0 NOTYPE GLOBAL DEFAULT 1 pokedata_workaround\n"
        with tempfile.TemporaryDirectory() as tmp, patch.object(BUILD.subprocess, "check_output", return_value=output):
            destination = Path(tmp) / "loader-info.c"
            BUILD.loader_info(Path("loader"), Path("readelf"), destination)
            self.assertIn("offset_to_pokedata_workaround=56;", destination.read_text())

    def test_loader_workaround_requires_both_symbols(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(BUILD.subprocess, "check_output", return_value=""):
            with self.assertRaisesRegex(RuntimeError, "workaround symbols"):
                BUILD.loader_info(Path("loader"), Path("readelf"), Path(tmp) / "loader-info.c")

    def test_elf_rejects_four_kib_load_alignment(self):
        outputs = ["ELF64 AArch64 DYN", "LOAD 0 0 0 0 0 R E 0x1000\n/system/bin/linker64", "(FLAGS_1) Flags: PIE"]
        with patch.object(BUILD.subprocess, "check_output", side_effect=outputs):
            with self.assertRaisesRegex(RuntimeError, "below 16 KiB"):
                BUILD.elf_info(Path("payload"), Path("readelf"), True)

    def test_elf_rejects_unbundled_dependency(self):
        outputs = ["ELF64 AArch64 DYN", "LOAD 0 0 0 0 0 R E 0x4000\n/system/bin/linker64", "(FLAGS_1) Flags: PIE\n(NEEDED) Shared library: [libtalloc.so]"]
        with patch.object(BUILD.subprocess, "check_output", side_effect=outputs):
            with self.assertRaisesRegex(RuntimeError, "Unpackaged native dependency"):
                BUILD.elf_info(Path("payload"), Path("readelf"), True)

    def test_elf_rejects_non_pie_host_executable(self):
        outputs = ["ELF64 AArch64 EXEC", "LOAD 0 0 0 0 0 R E 0x4000", ""]
        with patch.object(BUILD.subprocess, "check_output", side_effect=outputs):
            with self.assertRaisesRegex(RuntimeError, "PIE executable"):
                BUILD.elf_info(Path("payload"), Path("readelf"), True)

    def test_catalog_is_bound_to_real_sources_and_required_tools(self):
        catalog = json.loads((BUILD.ROOT / "app/src/main/assets/runtime/catalog.json").read_text())
        lock = BUILD.NATIVE / "source-lock.json"
        self.assertEqual(BUILD.digest(lock), catalog["engine"]["sourceLockSha256"])
        self.assertEqual({"sh", "node", "npm", "python3", "git"}, {x["name"] for x in catalog["tools"]})
        self.assertNotIn("\\\\n", catalog["tools"][0]["versionArguments"][1])
        self.assertEqual("v24.22.2", catalog["tools"][1]["version"])
        self.assertEqual("11.19.1", catalog["tools"][2]["version"])


if __name__ == "__main__":
    unittest.main()
