"""Negative evidence checks for the real Android capture verifier, not device QA."""

import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET


spec = importlib.util.spec_from_file_location("verify_web_ui", Path(__file__).resolve().parents[1] / "ci/verify-web-ui.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class SidebarEvidenceTest(unittest.TestCase):
    def node(self, parent, label, bounds, clickable=False, kind="android.view.View", **attributes):
        return ET.SubElement(parent, "node", {
            "package": verify.APP_ID, "class": kind, "content-desc": label, "bounds": bounds,
            "enabled": "true", "clickable": str(clickable).lower(), "focusable": str(clickable).lower(),
            **attributes,
        })

    def hierarchy(self, opened=False, ime=False):
        root = ET.Element("hierarchy")
        viewport = self.node(root, "", "[0,24][720,900]" if ime else "[0,24][720,1480]", kind="android.webkit.WebView")
        if opened:
            self.node(viewport, "关闭导航", "[488,40][584,136]", clickable=True)
            nav = self.node(viewport, "工作与功能", "[0,160][608,1120]")
            for index, label in enumerate(verify.SIDEBAR_DESTINATIONS):
                top = 184 + index * 104
                control = self.node(nav, "", f"[24,{top}][584,{top + 96}]", clickable=True)
                self.node(control, label, f"[72,{top + 28}][360,{top + 68}]", kind="android.widget.TextView")
        else:
            self.node(viewport, "打开导航", "[24,40][120,136]", clickable=True)
            section = self.node(viewport, "工作输入", "[8,680][712,880]" if ime else "[8,1280][712,1464]")
            self.node(section, "", "[30,704][600,840]" if ime else "[30,1304][600,1424]",
                      clickable=True, kind="android.widget.EditText", focused=str(ime).lower(), password="false")
        return root, viewport

    def control(self, root, label):
        return next(iter(verify.accessible_controls(root, label).values()))

    def test_closed_sidebar_keeps_one_reachable_menu_entry(self):
        root, _ = self.hierarchy()
        result = verify.navigation_identity(root, False)
        self.assertFalse(result["drawer_open"])
        self.assertEqual(["打开导航"], result["labels"])

    def test_left_sidebar_requires_all_actual_destinations(self):
        root, _ = self.hierarchy(opened=True)
        result = verify.navigation_identity(root, True)
        self.assertTrue(result["drawer_open"])
        self.assertEqual(["关闭导航", *verify.SIDEBAR_DESTINATIONS], result["labels"])

    def test_duplicate_menu_entry_is_rejected(self):
        root, viewport = self.hierarchy()
        self.node(viewport, "打开导航", "[144,40][240,136]", clickable=True)
        with self.assertRaises(AssertionError):
            verify.navigation_identity(root, False)

    def test_drawer_that_failed_to_close_is_rejected(self):
        root, viewport = self.hierarchy()
        self.node(viewport, "关闭导航", "[488,40][584,136]", clickable=True)
        with self.assertRaises(AssertionError):
            verify.navigation_identity(root, False)

    def test_unfocusable_and_clipped_entries_are_rejected(self):
        for attributes in ({"focusable": "false"}, {"enabled": "false"}, {"bounds": "[24,4][120,100]"}):
            with self.subTest(attributes=attributes):
                root, _ = self.hierarchy()
                self.control(root, "打开导航").attrib.update(attributes)
                with self.assertRaises(AssertionError):
                    verify.navigation_identity(root, False)

    def test_missing_or_noninteractive_destination_is_rejected(self):
        for label in verify.SIDEBAR_DESTINATIONS:
            with self.subTest(label=label):
                root, _ = self.hierarchy(opened=True)
                self.control(root, label).set("clickable", "false")
                with self.assertRaises(AssertionError):
                    verify.navigation_identity(root, True)

    def test_horizontal_tabs_cannot_impersonate_the_sidebar(self):
        root, _ = self.hierarchy(opened=True)
        for index, label in enumerate(verify.SIDEBAR_DESTINATIONS[:3]):
            self.control(root, label).set("bounds", f"[{24 + index * 180},184][{184 + index * 180},280]")
        with self.assertRaises(AssertionError):
            verify.navigation_identity(root, True)

    def test_obscured_center_hit_is_rejected(self):
        root, viewport = self.hierarchy(opened=True)
        target = self.control(root, "任务")
        self.node(viewport, "遮挡控件", target.get("bounds"), clickable=True)
        with self.assertRaises(AssertionError):
            verify.navigation_identity(root, True)

    def test_control_from_another_webview_is_rejected(self):
        root, viewport = self.hierarchy(opened=True)
        another = self.node(root, "", "[0,24][720,1480]", kind="android.webkit.WebView")
        target = self.control(root, "设置")
        parents = {child: parent for parent in root.iter() for child in parent}
        parents[target].remove(target)
        another.append(target)
        with self.assertRaises(AssertionError):
            verify.navigation_identity(root, True)

    def test_ime_requires_actual_service_state_and_reachable_work_input(self):
        for visible in (True, False):
            with self.subTest(visible=visible), tempfile.TemporaryDirectory() as directory:
                root, _ = self.hierarchy(ime=visible)
                xml = Path(directory) / "current.xml"
                ET.ElementTree(root).write(xml)
                flag = str(visible).lower()
                result = verify.ime_identity(xml, f"mInputShown={flag}\nmDecorViewVisible={flag}", visible)
                self.assertEqual(visible, result["visible"])
                self.assertEqual(["打开导航"], result["navigation"]["labels"])
                for invalid in ("mInputShown=true", "mInputShown=true\nmDecorViewVisible=false",
                                "mInputShown=true mInputShown=false\nmDecorViewVisible=true"):
                    with self.assertRaises(AssertionError):
                        verify.ime_identity(xml, invalid, True)

    def test_ime_cannot_use_unrelated_or_offscreen_editor(self):
        for invalid in ("different-section", "clipped", "secret"):
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as directory:
                root, _ = self.hierarchy(ime=True)
                editor = next(node for node in root.iter("node") if node.get("class") == "android.widget.EditText")
                if invalid == "different-section":
                    next(node for node in root.iter("node") if node.get("content-desc") == "工作输入").set("content-desc", "其他输入")
                elif invalid == "clipped":
                    editor.set("bounds", "[30,880][600,1000]")
                else:
                    editor.set("password", "true")
                xml = Path(directory) / "current.xml"
                ET.ElementTree(root).write(xml)
                with self.assertRaises(AssertionError):
                    verify.ime_identity(xml, "mInputShown=true\nmDecorViewVisible=true", True)


if __name__ == "__main__":
    unittest.main()
