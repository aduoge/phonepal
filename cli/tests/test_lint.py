import os
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

from xiaoman_cli.lint import lint_flow, lint_skill  # noqa: E402

FIXTURE = os.path.join(HERE, "fixtures", "skills", "ride-home")

GOOD_MD = """---
name: {name}
description: 打车回家
apps: [com.autonavi.minimap]
deeplinks:
  search: "amapuri://route/plan/?dname={{destination}}"
---
Body.
"""


class LintTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="xiaoman-lint-")

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def skill(self, folder, skill_md, flow=None):
        d = os.path.join(self.tmp, folder)
        os.makedirs(d)
        with open(os.path.join(d, "SKILL.md"), "w", encoding="utf-8") as f:
            f.write(skill_md)
        if flow is not None:
            with open(os.path.join(d, "flow.yaml"), "w", encoding="utf-8") as f:
                f.write(flow)
        return d

    def messages(self, info):
        return [(os.path.basename(p.path), p.line, p.message) for p in info.problems]


class SkillMdTest(LintTestCase):
    def test_fixture_is_clean(self):
        info = lint_skill(FIXTURE)
        self.assertEqual(info.problems, [])
        self.assertEqual(info.name, "ride-home")

    def test_problem_format(self):
        d = self.skill("x", "---\nname: x\n---\n")
        info = lint_skill(d)
        self.assertEqual([str(p) for p in info.problems],
                         ["%s:1: missing required key 'description'" % os.path.join(d, "SKILL.md")])

    def test_missing_skill_md(self):
        d = os.path.join(self.tmp, "empty")
        os.makedirs(d)
        self.assertEqual([p.message for p in lint_skill(d).problems], ["missing SKILL.md"])

    def test_missing_frontmatter(self):
        info = lint_skill(self.skill("x", "# no frontmatter\n"))
        self.assertIn("missing frontmatter", info.problems[0].message)

    def test_invalid_yaml_line(self):
        info = lint_skill(self.skill("x", "---\nname: x\napps: [a\n---\n"))
        self.assertEqual(len(info.problems), 1)
        self.assertEqual(info.problems[0].line, 3)
        self.assertTrue(info.problems[0].message.startswith("invalid YAML: "))

    def test_name_rules(self):
        info = lint_skill(self.skill("Taxi", "---\nname: Taxi\ndescription: d\n---\n"))
        self.assertEqual(self.messages(info), [("SKILL.md", 2, "name 'Taxi' must match [a-z0-9-]+")])
        info = lint_skill(self.skill("folder", "---\ndescription: d\nname: other\n---\n"))
        self.assertEqual(self.messages(info),
                         [("SKILL.md", 3, "name 'other' does not match folder name 'folder'")])
        self.assertEqual(info.name, "other")
        info = lint_skill(self.skill("n", "---\nname:\ndescription: d\n---\n"))
        self.assertEqual(self.messages(info), [("SKILL.md", 2, "'name' must be a string")])

    def test_description_rules(self):
        info = lint_skill(self.skill("a", "---\nname: a\ndescription: \"\"\n---\n"))
        self.assertEqual(self.messages(info), [("SKILL.md", 3, "'description' must not be empty")])
        info = lint_skill(self.skill("b", "---\nname: b\ndescription: |\n  one\n  two\n---\n"))
        self.assertEqual(self.messages(info), [("SKILL.md", 3, "'description' must be a single line")])
        info = lint_skill(self.skill("c", "---\nname: c\ndescription: [x]\n---\n"))
        self.assertEqual(self.messages(info), [("SKILL.md", 3, "'description' must be a string")])

    def test_unknown_and_duplicate_keys(self):
        info = lint_skill(self.skill("a", "---\nname: a\ndescription: d\nversion: 2\nname: a\n---\n"))
        msgs = [m for _, _, m in self.messages(info)]
        self.assertEqual(msgs[0], "duplicate key 'name'")
        info = lint_skill(self.skill("b", "---\nname: b\ndescription: d\nversion: 2\n---\n"))
        self.assertEqual(self.messages(info)[0][1:], (4, "unknown key 'version'; expected one of name, "
                                                         "description, apps, channel, confirm_before, memory, deeplinks"))

    def test_channel(self):
        info = lint_skill(self.skill("a", "---\nname: a\ndescription: d\nchannel: GUI\n---\n"))
        self.assertEqual(info.problems, [])
        info = lint_skill(self.skill("b", "---\nname: b\ndescription: d\nchannel: web\n---\n"))
        self.assertEqual(self.messages(info), [("SKILL.md", 4, "unknown channel 'web'; expected one of "
                                                "api, cli, deeplink, gui, deeplink-then-gui, mixed")])

    def test_string_lists(self):
        ok = "---\nname: a\ndescription: d\napps: com.x.y\nmemory: 地址/家\nconfirm_before:\n---\n"
        self.assertEqual(lint_skill(self.skill("a", ok)).problems, [])
        bad = "---\nname: b\ndescription: d\nconfirm_before: [x, [y]]\nmemory: {a: b}\napps: [com.x.y, chrome]\n---\n"
        self.assertEqual(self.messages(lint_skill(self.skill("b", bad))), [
            ("SKILL.md", 6, "apps: 'chrome' is not a package name"),
            ("SKILL.md", 4, "'confirm_before' items must be strings"),
            ("SKILL.md", 5, "'memory' must be a list of strings"),
        ])
        empty = "---\nname: c\ndescription: d\nmemory: [\"\", x]\n---\n"
        self.assertEqual(self.messages(lint_skill(self.skill("c", empty))),
                         [("SKILL.md", 4, "'memory' items must not be empty")])

    def test_deeplinks(self):
        md = "---\nname: a\ndescription: d\ndeeplinks:\n  ok: \"x://y?{q}\"\n  bad: http//x\n  sp: \"a:// b\"\n---\n"
        self.assertEqual(self.messages(lint_skill(self.skill("a", md))), [
            ("SKILL.md", 6, "deeplinks: 'bad' = 'http//x' is not a scheme:// URI"),
            ("SKILL.md", 7, "deeplinks: 'sp' = 'a:// b' is not a scheme:// URI"),
        ])
        md = "---\nname: b\ndescription: d\ndeeplinks: [x://y]\n---\n"
        self.assertEqual(self.messages(lint_skill(self.skill("b", md))),
                         [("SKILL.md", 4, "'deeplinks' must be a mapping of name: \"scheme://...\"")])

    def test_flow_skipped_when_skill_md_invalid(self):
        d = self.skill("a", "---\nname: a\n---\n", flow="steps: []\n")
        self.assertEqual([os.path.basename(p.path) for p in lint_skill(d).problems], ["SKILL.md"])


class FlowTest(LintTestCase):
    def flow(self, text, deeplinks=None, apps=("com.autonavi.minimap",)):
        problems = lint_flow("flow.yaml", text, deeplinks or {"search": "amapuri://x"}, list(apps))
        return [(p.line, p.message) for p in problems]

    def test_good(self):
        self.assertEqual(self.flow("steps:\n  - deeplink: search\n  - wait: '0'\n  - type: {target: a, text: ''}\n"), [])

    def test_structure(self):
        self.assertEqual(self.flow(""), [(1, "flow.yaml is empty; expected 'steps:'")])
        self.assertEqual(self.flow("- tap: x"), [(1, "flow.yaml must be a mapping with 'steps:'")])
        self.assertEqual(self.flow("name: x\n"), [(1, "unknown key 'name'; only 'steps' is allowed"),
                                                  (1, "missing 'steps:' list")])
        self.assertEqual(self.flow("steps: x\n"), [(1, "'steps' must be a list")])
        self.assertEqual(self.flow("steps: []\n"), [(1, "'steps' is empty")])

    def test_step_shapes(self):
        text = (
            "steps:\n"
            "  - tap: a\n    wait: 1\n"          # 2: two keys
            "  - click: x\n"                     # 4
            "  - tap:\n"                         # 5
            "  - ai: [x]\n"                      # 6
            "  - launch: chrome\n"               # 7
            "  - launch: com.other.app\n"        # 8
            "  - deeplink: home\n"               # 9
            "  - wait: 600001\n"                 # 10
            "  - wait: soon\n"                   # 11
            "  - type: { target: x, text: {destination} }\n"  # 12
            "  - type: { target: x, text: y, submit: true }\n"  # 13
            "  - type: x\n"                      # 14
            "  - just text\n"                    # 15
        )
        got = self.flow(text)
        self.assertEqual([line for line, _ in got], [2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15])
        msgs = dict(got)
        self.assertTrue(msgs[2].startswith("step 1: expected one 'kind: value' pair"))
        self.assertTrue(msgs[4].startswith("step 2 (click): unknown step kind"))
        self.assertEqual(msgs[5], "step 3 (tap): expected a target description")
        self.assertEqual(msgs[7], "step 5 (launch): 'chrome' is not a package name")
        self.assertEqual(msgs[8], "step 6 (launch): 'com.other.app' is not in the skill's apps")
        self.assertEqual(msgs[9], "step 7 (deeplink): 'home' is neither a key of the skill's deeplinks "
                                  "nor a scheme:// URI")
        self.assertEqual(msgs[10], "step 8 (wait): expected milliseconds as an integer 0..600000")
        self.assertIn("quote values that start with '{'", msgs[12])
        self.assertEqual(msgs[13], "step 11 (type): expected { target: ..., text: ... }")

    def test_launch_without_apps_and_literal_uri(self):
        self.assertEqual(self.flow("steps:\n  - launch: com.any.app\n  - deeplink: weixin://dl/scan\n", apps=()), [])


class ExamplesTest(unittest.TestCase):
    """The repository examples must lint clean (skipped when absent)."""

    def test_examples(self):
        root = os.path.join(os.path.dirname(os.path.dirname(HERE)), "skills", "examples")
        if not os.path.isdir(root):
            self.skipTest("skills/examples not present")
        for name in sorted(os.listdir(root)):
            d = os.path.join(root, name)
            if os.path.isdir(d) and not name.startswith("."):
                with self.subTest(skill=name):
                    self.assertEqual([str(p) for p in lint_skill(d).problems], [])


if __name__ == "__main__":
    unittest.main()
