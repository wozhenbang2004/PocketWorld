import hashlib
import importlib.util
import io
import json
from pathlib import Path
import unittest
import warnings
import zipfile

spec = importlib.util.spec_from_file_location("verify_web_ui", Path(__file__).resolve().parents[1] / "ci/verify-web-ui.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class PackagedWebReceiptTest(unittest.TestCase):
    def archive(self, extra=None, mutate=None, duplicate=False):
        files = {"index.html": b'<html data-pocket-ui="library-web">', "assets/main.js": b'js',
                 "fonts/main.woff2": b'font', "licenses/main.txt": b'license'}
        receipt = {"kind": "pocketworld-library-web", "files": [
            {"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()} for name, data in files.items()]}
        if mutate:
            mutate(files, receipt)
        if extra:
            files.update(extra)
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as bundle:
            for name, value in files.items():
                bundle.writestr("assets/pocket-ui/" + name, value)
            bundle.writestr("assets/pocket-ui/build-identity.json", json.dumps(receipt))
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    bundle.writestr("assets/pocket-ui/index.html", files["index.html"])
        return zipfile.ZipFile(io.BytesIO(data.getvalue()))

    def test_exact_packaged_file_set(self):
        with self.archive() as bundle:
            self.assertEqual(5, len(verify.verify_packaged_web(bundle)))

    def test_unlisted_merged_resource_is_rejected(self):
        for name in ("extra.js", "assets/build-identity.json"):
            with self.subTest(name=name), self.archive(extra={name: b'extra'}) as bundle:
                with self.assertRaisesRegex(AssertionError, "differ"):
                    verify.verify_packaged_web(bundle)

    def test_modified_packaged_file_is_rejected(self):
        with self.archive(mutate=lambda files, receipt: files.update({"assets/main.js": b'xx'})) as bundle:
            with self.assertRaisesRegex(AssertionError, "hash"):
                verify.verify_packaged_web(bundle)

    def test_duplicate_zip_entry_is_rejected(self):
        with self.archive(duplicate=True) as bundle:
            with self.assertRaisesRegex(AssertionError, "Duplicate"):
                verify.verify_packaged_web(bundle)

    def test_duplicate_manifest_path_is_rejected(self):
        with self.archive(mutate=lambda files, receipt: receipt["files"].append(receipt["files"][0])) as bundle:
            with self.assertRaisesRegex(AssertionError, "Duplicate"):
                verify.verify_packaged_web(bundle)


if __name__ == "__main__":
    unittest.main()
