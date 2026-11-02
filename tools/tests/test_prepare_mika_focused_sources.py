import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "mika_sources", Path(__file__).parents[1] / "prepare-mika-focused-sources.py"
)
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)


class FocusedSourcePreparationTest(unittest.TestCase):
    def fixture(self, selected):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / "tools").mkdir()
        (root / "tools/mika-focused-tests.txt").write_text("\n".join(selected) + "\n")
        self.write(root, "app/src/test/java/offline/Negative.java",
                   "package dev.pocketengine.features.local.runtime;\n"
                   "public class NativeOfflineUnavailableTest {}")
        return root

    def write(self, root, path, content):
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        return path

    def test_package_identity_and_recursive_import_wildcard_and_same_package_helpers(self):
        root = self.fixture(["chosen.Selected"])
        selected = self.write(root, "display-helper/test/wrong/Selected.java", """
package chosen;
import static fixtures.Factory.create;
import wildcard.*;
public class Selected { SamePackage a; Used b; }
""")
        same = self.write(root, "app/src/test/java/elsewhere/Local.java",
                          "package chosen; class SamePackage { fixtures.Last c; }")
        factory = self.write(root, "app/src/pendingAdb/test/Factory.java",
                             "package fixtures; public class Factory { static Object create(){return null;} }")
        last = self.write(root, "app/src/test/java/Last.java",
                          "package fixtures; public class Last {}")
        used = self.write(root, "app/src/test/java/Used.java",
                          "package wildcard; public class Used {}")
        unused = self.write(root, "app/src/test/java/Unused.java",
                            "package wildcard; public class Unused {}")
        result = BUILD.prepare(root)
        self.assertEqual(selected, result["selectedClassSources"]["chosen.Selected"])
        self.assertIn(BUILD.OFFLINE_CLASS, result["selectedClasses"])
        self.assertEqual({same, factory, last, used}, set(result["dependencySources"]))
        self.assertNotIn(unused, result["javaSources"])
        self.assertTrue(all(not Path(p).is_absolute() for p in result["javaSources"]))
        before = {p: (root / p).read_bytes() for p in result["javaSources"]}
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, BUILD.main(["--repo", str(root)]))
        saved = json.loads((root / "app/build/mika-focused-tests/sources.json").read_text())
        self.assertEqual(result, saved)
        self.assertEqual(before, {p: (root / p).read_bytes() for p in before})

    def test_inline_import_and_kotlin_alias_follow_real_java_fixture(self):
        root = self.fixture(["chosen.Selected", "kotlinchosen.SelectedK"])
        java = self.write(root, "app/src/test/java/Selected.java",
                          "package chosen; import fixture.JavaFixture; public class Selected { JavaFixture f; }")
        helper = self.write(root, "app/src/test/java/Fixture.java",
                            "package fixture; class JavaFixture {}")
        kotlin = self.write(root, "app/src/test/kotlin/UnrelatedDirectory.kt", """
package kotlinchosen
import fixture.JavaFixture as NativeFixture
import ktfixture.newFixture as make
class SelectedK { val sample = make(); val fixture: NativeFixture? = null }
""")
        kt_helper = self.write(root, "app/src/test/kotlin/HelperBundle.kt",
                               "package ktfixture\nfun newFixture() = fixture.JavaFixture()\n")
        result = BUILD.prepare(root)
        self.assertIn(java, result["javaSources"])
        self.assertIn(helper, result["javaSources"])
        self.assertEqual({kotlin, kt_helper}, set(result["kotlinSources"]))
        edges = {(edge["from"], edge["to"]) for edge in result["dependencies"]}
        self.assertIn((java, helper), edges)
        self.assertTrue(result["scope"]["unitKotlinSelectionChanged"] is False)

    def test_comments_strings_and_local_nested_types_do_not_expand_the_closure(self):
        root = self.fixture(["chosen.Selected"])
        self.write(root, "app/src/test/java/Selected.java", '''
package chosen;
/* import traps.Wrong; class Pretend {} */
public class Selected {
    class Local {}
    Local local;
    String ignored = "Ghost.class traps.Wrong";
    String block = """
        import traps.Another;
        Ghost unused;
        """;
    // Ghost ignored; traps.Wrong reference;
}
''')
        self.write(root, "app/src/test/java/Ghost.java", "package chosen; class Ghost {}")
        self.write(root, "app/src/test/java/Local.java", "package chosen; class Local {}")
        self.write(root, "app/src/test/java/Wrong.java", "package traps; class Wrong {}")
        result = BUILD.prepare(root)
        self.assertEqual([], result["dependencySources"])
        self.assertEqual(2, result["counts"]["selectedClasses"])

    def test_missing_selected_class_is_an_error_and_writes_no_manifest(self):
        root = self.fixture(["chosen.Missing"])
        self.write(root, "app/src/test/java/chosen/Missing.java",
                   "package different; class Missing {}")
        with contextlib.redirect_stderr(io.StringIO()) as error:
            status = BUILD.main(["--repo", str(root)])
        self.assertEqual(1, status)
        self.assertIn("Selected class has no declared source", error.getvalue())
        self.assertFalse((root / "app/build/mika-focused-tests/sources.json").exists())

    def test_duplicate_input_or_ambiguous_source_cannot_silently_drop_a_class(self):
        root = self.fixture(["chosen.Selected", "chosen.Selected"])
        with self.assertRaisesRegex(BUILD.SourceError, "duplicate selected class"):
            BUILD.prepare(root)
        (root / "tools/mika-focused-tests.txt").write_text("chosen.Selected\n")
        for path in ["app/src/test/java/A.java", "display-helper/test/B.java"]:
            self.write(root, path, "package chosen; class Selected {}")
        with self.assertRaisesRegex(BUILD.SourceError, "Ambiguous test-source symbol"):
            BUILD.prepare(root)


if __name__ == "__main__":
    unittest.main()
