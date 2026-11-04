import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from xiaoman_cli.yamlsub import (  # noqa: E402
    FrontmatterError, YamlError, parse, split_frontmatter, to_plain,
)


def plain(text):
    return to_plain(parse(text))


class ScalarTest(unittest.TestCase):
    def test_types(self):
        self.assertEqual(plain("a: 1\nb: 1.5\nc: true\nd: null\ne: ~\nf:\ng: hello world"),
                         {"a": 1, "b": 1.5, "c": True, "d": None, "e": None, "f": None, "g": "hello world"})

    def test_quoted(self):
        self.assertEqual(plain('a: "x \\"y\\" \\u4e2d\\n"\nb: \'it\'\'s\'\nc: "1"'),
                         {"a": 'x "y" 中\n', "b": "it's", "c": "1"})

    def test_raw_text_kept(self):
        root = parse("wait: 1500\nq: '1500'\nn:")
        self.assertEqual(root.value["wait"].raw, "1500")
        self.assertEqual(root.value["wait"].value, 1500)
        self.assertEqual(root.value["q"].raw, "1500")
        self.assertIsNone(root.value["n"].raw)

    def test_comments(self):
        self.assertEqual(plain("name: taxi   # comment\nurl: 'a#b'\nx: a#b\n# whole line\ny: \"#\""),
                         {"name": "taxi", "url": "a#b", "x": "a#b", "y": "#"})

    def test_uri_values(self):
        self.assertEqual(plain('search: "amapuri://route/plan/?dname={destination}"\nraw: amapuri://x?a=1'),
                         {"search": "amapuri://route/plan/?dname={destination}", "raw": "amapuri://x?a=1"})

    def test_colon_space_in_plain_value_is_error(self):
        with self.assertRaises(YamlError):
            parse("description: a: b")

    def test_cjk_colon_is_fine(self):
        self.assertEqual(plain("description: 打车：回家"), {"description": "打车：回家"})

    def test_multiline_plain_folds(self):
        self.assertEqual(plain("description: one\n  two\nname: x"), {"description": "one two", "name": "x"})

    def test_block_scalars(self):
        self.assertEqual(plain("a: |\n  l1\n  l2\nb: >-\n  f1\n  f2\nc: x"),
                         {"a": "l1\nl2\n", "b": "f1 f2", "c": "x"})


class CollectionTest(unittest.TestCase):
    def test_inline_and_block_lists(self):
        self.assertEqual(plain("apps: [com.a.b, 'x y', \"中\"]\nmem:\n  - 地址/家\n  - 偏好/车型\nsame:\n- a\n- b\nempty: []"),
                         {"apps": ["com.a.b", "x y", "中"], "mem": ["地址/家", "偏好/车型"],
                          "same": ["a", "b"], "empty": []})

    def test_one_level_map(self):
        self.assertEqual(plain('deeplinks:\n  search: "amapuri://a"\n  home: b://c\nnext: 1'),
                         {"deeplinks": {"search": "amapuri://a", "home": "b://c"}, "next": 1})

    def test_flow_steps(self):
        text = (
            "steps:\n"
            "  - launch: com.autonavi.minimap\n"
            "  - type: { target: 目的地, text: \"{destination}\" }\n"
            "  - wait: 1500\n"
            "  - type:\n"
            "      target: x\n"
            "      text: y\n"
        )
        self.assertEqual(plain(text), {"steps": [
            {"launch": "com.autonavi.minimap"},
            {"type": {"target": "目的地", "text": "{destination}"}},
            {"wait": 1500},
            {"type": {"target": "x", "text": "y"}},
        ]})

    def test_unquoted_brace_is_flow_mapping(self):
        self.assertEqual(plain("text: {destination}"), {"text": {"destination": None}})

    def test_nested_flow(self):
        self.assertEqual(plain("a: [1, [2, 3], {k: v}]"), {"a": [1, [2, 3], {"k": "v"}]})

    def test_multi_key_seq_item(self):
        self.assertEqual(plain("- a: 1\n  b: 2\n- c"), [{"a": 1, "b": 2}, "c"])

    def test_line_numbers(self):
        root = parse("a: 1\n\n# c\nsteps:\n  - tap: x\n  - wait: 2\n", line_offset=1)
        self.assertEqual(root.value["a"].line, 2)
        steps = root.value["steps"]
        self.assertEqual(steps.key_line, 5)
        self.assertEqual([s.line for s in steps.value], [6, 7])
        self.assertEqual(steps.value[1].value["wait"].key_line, 7)

    def test_errors(self):
        for bad in ("a: [1, 2", "a: {x: 1", "a: 'x", "a:\n\t- b", "a: &x 1", "a: 1\n   b: 2"):
            with self.subTest(bad=bad):
                with self.assertRaises(YamlError):
                    parse(bad)

    def test_error_line(self):
        with self.assertRaises(YamlError) as cm:
            parse("a: 1\nb: [x\n", line_offset=1)
        self.assertEqual(cm.exception.line, 3)

    def test_duplicates(self):
        with self.assertRaises(YamlError):
            parse("a: 1\na: 2")
        dups = []
        root = parse("a: 1\nb: {x: 1, x: 2}\na: 2", duplicates=dups)
        self.assertEqual(to_plain(root), {"a": 1, "b": {"x": 1}})
        self.assertEqual(dups, [("x", 2), ("a", 3)])

    def test_empty(self):
        self.assertIsNone(plain(""))
        self.assertIsNone(plain("# only a comment\n"))


class FrontmatterTest(unittest.TestCase):
    def test_split(self):
        fm, first, body = split_frontmatter("\ufeff---\nname: x\n---\n# Body\n")
        self.assertEqual(fm, "name: x")
        self.assertEqual(first, 2)
        self.assertEqual(body, "# Body")

    def test_missing(self):
        with self.assertRaises(FrontmatterError):
            split_frontmatter("name: x\n")
        with self.assertRaises(FrontmatterError):
            split_frontmatter("---\nname: x\n")


if __name__ == "__main__":
    unittest.main()
