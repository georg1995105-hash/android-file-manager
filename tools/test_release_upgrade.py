import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch
import subprocess
from release_upgrade import find_node, failure_evidence, wait_node


class AccessibilityTest(unittest.TestCase):
    def test_exact_action_is_preferred_to_explanatory_text(self):
        tree = ET.fromstring('<hierarchy><node text="Заметки хранятся на телефоне" bounds="[0,0][50,50]"/>'
                             '<node text="Заметки" bounds="[100,100][200,200]"/></hierarchy>')
        self.assertEqual(find_node(tree, "Заметки"), (150, 150))

    def test_merged_compose_note_can_be_opened(self):
        tree = ET.fromstring('<hierarchy><node text="release-upgrade-fixture&#10;note body" '
                             'bounds="[0,100][200,200]"/></hierarchy>')
        self.assertEqual(find_node(tree, "release-upgrade-fixture"), (100, 150))

    def test_hidden_nodes_are_never_tapped(self):
        tree = ET.fromstring('<hierarchy><node text="Назад" bounds="[0,0][0,0]"/>'
                             '<node content-desc="Назад" bounds="[0,20][60,80]"/></hierarchy>')
        self.assertEqual(find_node(tree, "Назад"), (30, 50))

    def test_failure_diagnostics_do_not_read_a_real_device(self):
        result = subprocess.CompletedProcess(["adb", "get-serialno"], 0, stdout=b"owner-real-device\n")
        with patch("release_upgrade.subprocess.run", return_value=result) as run:
            failure_evidence()
        self.assertEqual(run.call_count, 1)
        self.assertEqual(run.call_args.args[0], ["adb", "get-serialno"])

    def test_transient_hierarchy_error_is_retried_without_skipping_the_assertion(self):
        xml = '<hierarchy><node text="fixture" bounds="[10,10][30,30]"/></hierarchy>'
        with patch("release_upgrade.adb", side_effect=[RuntimeError("not idle yet"), "dumped", xml]), \
                patch("release_upgrade.time.sleep"):
            self.assertEqual(wait_node("fixture"), (20, 20))


if __name__ == "__main__":
    unittest.main()
