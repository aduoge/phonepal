"""Skill folder validation with the same rules and messages as the app.

Mirrors core-skills/src/main/kotlin/dev/xiaoman/skills/{SkillParser,FlowParser,Yaml}.kt:
every problem the app would report is reported here, in the same order, at the same
line. Like the app, flow.yaml is only checked once SKILL.md itself is valid (the flow
is checked against the skill's deeplinks and apps). Format reference:
core-api/src/main/kotlin/dev/xiaoman/api/Skills.kt.
"""

from __future__ import annotations

import io
import os
import re

from .yamlsub import FrontmatterError, YamlError, parse, split_frontmatter

NAME_RE = re.compile(r"^[a-z0-9-]+$")
# Yaml.kt DEEPLINK_FORM: `scheme://rest`, {placeholders} allowed in the rest
DEEPLINK_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.-]*://\S+$")
# FlowParser.kt PACKAGE_NAME: two or more dot-separated Java identifiers
PACKAGE_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$")
CHANNELS = ("api", "cli", "deeplink", "gui", "deeplink-then-gui", "mixed")
KEYS = ("name", "description", "apps", "channel", "confirm_before", "memory", "deeplinks")
STEP_KINDS = ("launch", "deeplink", "ai", "confirm", "tap", "type", "wait", "assert")
MAX_WAIT_MILLIS = 600000
_LONG_RE = re.compile(r"^[+-]?[0-9]+$")


class Problem:
    __slots__ = ("path", "line", "message")

    def __init__(self, path: str, line, message: str):
        self.path = path
        self.line = line or 1
        self.message = message

    def __str__(self) -> str:
        return "%s:%d: %s" % (self.path, self.line, self.message)

    def __repr__(self) -> str:
        return "Problem(%r)" % str(self)


class SkillInfo:
    """Lint result of one folder; ``name`` is set when SKILL.md is valid."""

    def __init__(self, directory: str):
        self.directory = directory
        self.name = None
        self.problems = []

    @property
    def ok(self) -> bool:
        return not self.problems


class _Doc:
    """Problem collector bound to one file, like YamlDoc in the app."""

    def __init__(self, path: str, problems: list):
        self.path = path
        self.problems = problems

    def problem(self, message: str, line=None):
        self.problems.append(Problem(self.path, line, message))

    def compose(self, text: str, line_offset: int = 0):
        dups = []
        try:
            root = parse(text, line_offset=line_offset, duplicates=dups)
        except YamlError as e:
            self.problem("invalid YAML: " + e.message, e.line)
            return None, False
        for key, line in dups:
            self.problem("duplicate key '%s'" % key, line)
        return (None if root.value is None and root.raw is None else root), True

    def string(self, key: str, node):
        """A non-null scalar's text, or None with a problem."""
        if node.is_scalar and node.raw is not None:
            return node.raw
        self.problem("'%s' must be a string" % key, node.key_line)
        return None

    def string_list(self, key: str, node):
        """List of strings; a single string is a one-element list, null is empty."""
        if node.is_scalar:
            return [] if node.raw is None else [node.raw]
        if isinstance(node.value, list):
            items = []
            for item in node.value:
                if not item.is_scalar or item.raw is None:
                    self.problem("'%s' items must be strings" % key, item.line)
                    return None
                items.append(item.raw.strip())
            if any(not i for i in items):
                self.problem("'%s' items must not be empty" % key, node.key_line)
                return None
            return items
        self.problem("'%s' must be a list of strings" % key, node.key_line)
        return None


def _read(path: str) -> str:
    with io.open(path, "r", encoding="utf-8") as f:
        return f.read()


def _brace_hint(*nodes) -> str:
    # `text: {destination}` is a flow mapping in YAML, not a placeholder string
    if any(n is not None and isinstance(n.value, dict) for n in nodes):
        return " (quote values that start with '{', e.g. \"{destination}\")"
    return ""


def lint_skill(directory: str) -> SkillInfo:
    info = SkillInfo(directory)
    if not os.path.isdir(directory):
        info.problems.append(Problem(directory, None, "not a directory"))
        return info
    folder = os.path.basename(os.path.normpath(os.path.abspath(directory)))
    skill_md = os.path.join(directory, "SKILL.md")
    if not os.path.isfile(skill_md):
        info.problems.append(Problem(directory, None, "missing SKILL.md"))
        return info
    try:
        text = _read(skill_md)
    except (OSError, UnicodeDecodeError) as e:
        info.problems.append(Problem(skill_md, None, "cannot read: %s" % e))
        return info

    skill = _lint_skill_md(skill_md, text, info.problems)
    if skill is None:
        return info
    info.name = skill["name"]
    if skill["name"] != folder:
        info.problems.append(Problem(skill_md, _name_line(text), "name '%s' does not match folder name '%s'"
                                     % (skill["name"], folder)))
    flow_yaml = os.path.join(directory, "flow.yaml")
    if os.path.isfile(flow_yaml):
        try:
            flow_text = _read(flow_yaml)
        except (OSError, UnicodeDecodeError) as e:
            info.problems.append(Problem(flow_yaml, None, "cannot read: %s" % e))
            return info
        lint_flow(flow_yaml, flow_text, skill["deeplinks"], skill["apps"], info.problems)
    return info


def _name_line(text: str):
    if text.startswith("\ufeff"):
        text = text[1:]
    for i, line in enumerate(text.split("\n")):
        if line.startswith("name:"):
            return i + 1
    return None


def _lint_skill_md(path: str, text: str, problems: list):
    """Returns a dict of the parsed skill when valid, else None (problems appended)."""
    doc = _Doc(path, problems)
    start = len(problems)
    try:
        fm_text, fm_line, _body = split_frontmatter(text)
    except FrontmatterError:
        doc.problem("missing frontmatter: SKILL.md must start with a '---' line and close it with another '---'", 1)
        return None
    root, parsed = doc.compose(fm_text, line_offset=fm_line - 1)
    if not parsed:
        return None
    if root is not None and not isinstance(root.value, dict):
        doc.problem("frontmatter must be a mapping of keys", root.line)
        return None
    if len(problems) > start:
        return None
    entries = root.value if root is not None else {}
    for key, node in entries.items():
        if key not in KEYS:
            doc.problem("unknown key '%s'; expected one of %s" % (key, ", ".join(KEYS)), node.key_line)

    name = None
    if "name" not in entries:
        doc.problem("missing required key 'name'", 1)
    else:
        raw = doc.string("name", entries["name"])
        if raw is not None:
            name = raw.strip()
            if not NAME_RE.match(name):
                doc.problem("name '%s' must match [a-z0-9-]+" % name, entries["name"].key_line)

    description = None
    if "description" not in entries:
        doc.problem("missing required key 'description'", 1)
    else:
        raw = doc.string("description", entries["description"])
        if raw is not None:
            description = raw.strip()
            if not description:
                doc.problem("'description' must not be empty", entries["description"].key_line)
            elif "\n" in description:
                doc.problem("'description' must be a single line", entries["description"].key_line)

    apps = []
    if "apps" in entries:
        lst = doc.string_list("apps", entries["apps"])
        if lst is not None:
            for app in lst:
                if not PACKAGE_RE.match(app):
                    doc.problem("apps: '%s' is not a package name" % app, entries["apps"].key_line)
            apps = lst

    if "channel" in entries:
        wire = doc.string("channel", entries["channel"])
        if wire is not None and wire.strip().lower() not in CHANNELS:
            doc.problem("unknown channel '%s'; expected one of %s" % (wire, ", ".join(CHANNELS)),
                        entries["channel"].key_line)

    for key in ("confirm_before", "memory"):
        if key in entries:
            doc.string_list(key, entries[key])

    deeplinks = {}
    if "deeplinks" in entries:
        deeplinks = _lint_deeplinks(doc, entries["deeplinks"])

    if len(problems) > start or name is None or description is None:
        return None
    return {"name": name, "apps": apps, "deeplinks": deeplinks}


def _lint_deeplinks(doc: _Doc, node) -> dict:
    if node.is_scalar and node.raw is None:
        return {}
    if not isinstance(node.value, dict):
        doc.problem("'deeplinks' must be a mapping of name: \"scheme://...\"", node.key_line)
        return {}
    result = {}
    for key, link in node.value.items():
        value = link.raw.strip() if link.is_scalar and link.raw is not None else None
        if not key.strip():
            doc.problem("deeplinks: names must not be empty", link.key_line)
        elif value is None:
            doc.problem("deeplinks: '%s' must be a string%s" % (key, _brace_hint(link)), link.key_line)
        elif not DEEPLINK_RE.match(value):
            doc.problem("deeplinks: '%s' = '%s' is not a scheme:// URI" % (key, value), link.key_line)
        else:
            result[key] = value
    return result


def lint_flow(path: str, text: str, deeplinks=None, apps=(), problems=None):
    """Check flow.yaml text; returns the problems list."""
    problems = [] if problems is None else problems
    deeplinks = deeplinks or {}
    doc = _Doc(path, problems)
    root, parsed = doc.compose(text)
    if not parsed:
        return problems
    if root is None:
        doc.problem("flow.yaml is empty; expected 'steps:'")
        return problems
    if not isinstance(root.value, dict):
        doc.problem("flow.yaml must be a mapping with 'steps:'", root.line)
        return problems
    for key, node in root.value.items():
        if key != "steps":
            doc.problem("unknown key '%s'; only 'steps' is allowed" % key, node.key_line)
    steps = root.value.get("steps")
    if steps is None:
        doc.problem("missing 'steps:' list", root.line)
        return problems
    if not isinstance(steps.value, list):
        doc.problem("'steps' must be a list", steps.key_line)
        return problems
    if not steps.value:
        doc.problem("'steps' is empty", steps.key_line)
    for number, step in enumerate(steps.value, 1):
        _lint_step(doc, number, step, deeplinks, list(apps))
    return problems


def _lint_step(doc: _Doc, number: int, step, deeplinks: dict, apps: list):
    if not isinstance(step.value, dict) or len(step.value) != 1:
        doc.problem("step %d: expected one 'kind: value' pair, e.g. '- tap: 确认呼叫'" % number, step.line)
        return
    (kind, node), = step.value.items()

    def bad(message):
        doc.problem("step %d (%s): %s" % (number, kind, message), node.key_line)

    def text():
        if node.is_scalar and node.raw is not None and node.raw.strip():
            return node.raw.strip()
        return None

    if kind == "launch":
        pkg = text()
        if pkg is None:
            bad("expected a package name")
        elif not PACKAGE_RE.match(pkg):
            bad("'%s' is not a package name" % pkg)
        elif apps and pkg not in apps:
            bad("'%s' is not in the skill's apps" % pkg)
    elif kind == "deeplink":
        value = text()
        if value is None:
            bad("expected a deeplinks key or a URI")
        elif value not in deeplinks and not DEEPLINK_RE.match(value):
            bad("'%s' is neither a key of the skill's deeplinks nor a scheme:// URI" % value)
    elif kind in ("ai", "confirm", "tap", "assert"):
        if text() is None:
            bad({
                "ai": "expected a sub-goal text",
                "confirm": "expected a question text",
                "tap": "expected a target description",
                "assert": "expected the text the screen must show",
            }[kind] + _brace_hint(node))
    elif kind == "wait":
        raw = node.raw.strip() if node.is_scalar and node.raw is not None else ""
        if not _LONG_RE.match(raw) or not 0 <= int(raw) <= MAX_WAIT_MILLIS:
            bad("expected milliseconds as an integer 0..%d" % MAX_WAIT_MILLIS)
    elif kind == "type":
        if not _type_ok(node):
            hint = _brace_hint(*node.value.values()) if isinstance(node.value, dict) else ""
            bad("expected { target: ..., text: ... }" + hint)
    else:
        bad("unknown step kind; expected one of %s" % ", ".join(STEP_KINDS))


def _type_ok(node) -> bool:
    if not isinstance(node.value, dict):
        return False
    fields = node.value
    if set(fields) - {"target", "text"}:
        return False
    target = fields.get("target")
    text = fields.get("text")
    if target is None or not target.is_scalar or target.raw is None or not target.raw.strip():
        return False
    if text is None or not text.is_scalar or text.raw is None:
        return False
    return True


def lint_paths(directories):
    """Lint several skill folders. Returns a list of SkillInfo."""
    return [lint_skill(d) for d in directories]
