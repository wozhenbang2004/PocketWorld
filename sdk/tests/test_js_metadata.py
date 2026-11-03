import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

SDK = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SDK))
from pocket_js import metadata


class JsMetadataTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="pocket-js-metadata-")
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.metadata_path = self.path / "project.json"

    def test_metadata_at_4kib_byte_boundary_is_accepted(self):
        raw = json.dumps(dict(id="user.custom.js", name="示例", version="1"), ensure_ascii=False).encode("utf-8")
        self.metadata_path.write_bytes(raw + b" " * (4096 - len(raw)))
        self.assertEqual(["user.custom.js", "示例", "1"], metadata(self.path))

    def test_metadata_over_4kib_is_rejected_before_utf8_decoding(self):
        self.metadata_path.write_bytes(b" " * 4096 + b"\xff")
        with self.assertRaisesRegex(ValueError, "^Project metadata exceeds4KiB$"):
            metadata(self.path)

    def test_oversized_metadata_read_stops_at_one_over_limit(self):
        opener = mock.mock_open(read_data=b" " * 65536)
        with mock.patch.object(Path, "open", opener):
            with self.assertRaisesRegex(ValueError, "^Project metadata exceeds4KiB$"):
                metadata(self.path)
        opener.assert_called_once_with("rb", buffering=0)
        opener.return_value.read.assert_called_once_with(4097)

    def test_metadata_validation_errors_are_preserved(self):
        invalid = [
            (b"\xff", UnicodeDecodeError, None),
            (b"{", json.JSONDecodeError, None),
            (b'{"id":"a","id":"b","name":"Name","version":"1"}', ValueError, "Duplicate project metadata field"),
            (b'{"id":3,"name":"Name","version":"1"}', ValueError, "project.json must contain only id,name,version strings"),
            (b'{"id":"a","name":"Name","version":"1","extra":"x"}', ValueError, "project.json must contain only id,name,version strings"),
            (b"[]", ValueError, "project.json must contain only id,name,version strings"),
        ]
        for raw, error, message in invalid:
            with self.subTest(raw=raw):
                self.metadata_path.write_bytes(raw)
                with self.assertRaises(error) as failure:
                    metadata(self.path)
                if message:
                    self.assertEqual(message, str(failure.exception))

    def test_metadata_path_errors_are_preserved(self):
        with self.assertRaises(FileNotFoundError):
            metadata(self.path)
        self.metadata_path.mkdir()
        with self.assertRaises(IsADirectoryError):
            metadata(self.path)


if __name__ == "__main__":
    unittest.main()
