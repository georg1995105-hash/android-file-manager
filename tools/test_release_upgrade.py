import unittest
import xml.etree.ElementTree as ET
from release_upgrade import find_node


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


if __name__ == "__main__":
    unittest.main()
