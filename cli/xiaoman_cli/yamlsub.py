"""A small YAML subset parser, enough for SKILL.md frontmatter and flow.yaml.

Supported:
  * block mappings and block sequences (including ``- key: value`` items and
    sequences indented at the same level as their parent key)
  * plain, single-quoted and double-quoted scalars; ``#`` comments
  * flow sequences ``[a, b]`` and flow mappings ``{ target: x, text: "y" }``, nested
  * literal ``|`` and folded ``>`` block scalars (with ``-``/``+`` chomping)
  * plain scalars resolve to null, bool, int and float like YAML 1.2 core schema

Not supported (reported as errors): anchors/aliases, tags, multi-line flow
collections, multi-line plain scalars, complex keys.

Every value is wrapped in a :class:`Node` carrying the 1-based line number it
starts on, so that linters can point at the right line. ``to_plain`` strips them.
"""

from __future__ import annotations

import re

__all__ = ["Node", "YamlError", "parse", "to_plain", "split_frontmatter", "FrontmatterError"]


class YamlError(Exception):
    def __init__(self, message: str, line: int):
        super().__init__(message)
        self.message = message
        self.line = line

    def __str__(self) -> str:
        return "line %d: %s" % (self.line, self.message)


class Node:
    """A parsed value: ``value`` is a scalar, a list of Nodes or a dict of str -> Node.

    ``raw`` is the scalar's text before type resolution (None for null scalars and
    collections), which is how a snakeyaml ScalarNode sees it. ``key_line`` is the
    line of the mapping key when this node is a mapping value.
    """

    __slots__ = ("value", "line", "raw", "key_line")

    def __init__(self, value, line: int, raw=None, key_line=None):
        self.value = value
        self.line = line
        self.raw = raw
        self.key_line = key_line if key_line is not None else line

    @property
    def is_scalar(self) -> bool:
        return not isinstance(self.value, (list, dict))

    @property
    def is_null(self) -> bool:
        return self.value is None

    def __repr__(self) -> str:
        return "Node(%r, line=%d)" % (self.value, self.line)


def to_plain(node):
    """Recursively unwrap Nodes into plain Python values."""
    if node is None:
        return None
    v = node.value
    if isinstance(v, list):
        return [to_plain(n) for n in v]
    if isinstance(v, dict):
        return {k: to_plain(n) for k, n in v.items()}
    return v


# --------------------------------------------------------------------------- scalars

_INT_RE = re.compile(r"^[-+]?[0-9]+$")
_FLOAT_RE = re.compile(r"^[-+]?(\.[0-9]+|[0-9]+(\.[0-9]*)?)([eE][-+]?[0-9]+)?$")
_DQ_ESCAPES = {
    "0": "\0", "a": "\a", "b": "\b", "t": "\t", "\t": "\t", "n": "\n", "v": "\v",
    "f": "\f", "r": "\r", "e": "\x1b", " ": " ", '"': '"', "/": "/", "\\": "\\",
    "N": "\u0085", "_": " ", "L": " ", "P": " ",
}


def _plain_node(text: str, line: int) -> Node:
    value = resolve_plain(text)
    return Node(value, line, raw=None if value is None else text)


def resolve_plain(s: str):
    if s in ("", "~", "null", "Null", "NULL"):
        return None
    if s in ("true", "True", "TRUE"):
        return True
    if s in ("false", "False", "FALSE"):
        return False
    if _INT_RE.match(s):
        return int(s)
    if _FLOAT_RE.match(s) and any(c.isdigit() for c in s):
        return float(s)
    return s


class _Inline:
    """Recursive-descent parser for one line's value: scalars and flow collections."""

    def __init__(self, text: str, line: int, duplicates=None):
        self.s = text
        self.i = 0
        self.line = line
        self.duplicates = duplicates

    def error(self, message: str):
        raise YamlError(message, self.line)

    def ws(self):
        while self.i < len(self.s) and self.s[self.i] in " \t":
            self.i += 1

    def peek(self) -> str:
        return self.s[self.i] if self.i < len(self.s) else ""

    def parse_document_value(self) -> Node:
        self.ws()
        node = self.value(flow=False)
        self.ws()
        if self.i < len(self.s):
            self.error("unexpected text after value: %r" % self.s[self.i:])
        return node

    def value(self, flow: bool) -> Node:
        self.ws()
        c = self.peek()
        if c == "[":
            return self.flow_seq()
        if c == "{":
            return self.flow_map()
        if c == '"':
            v = self.double_quoted()
            return Node(v, self.line, raw=v)
        if c == "'":
            v = self.single_quoted()
            return Node(v, self.line, raw=v)
        if c in ("&", "*", "!"):
            self.error("anchors, aliases and tags are not supported")
        if c in ("|", ">") and not flow:
            self.error("block scalar indicator is not allowed here")
        if c == "@" or c == "`":
            self.error("plain scalars cannot start with %r; quote the value" % c)
        return _plain_node(self.plain(flow), self.line)

    def plain(self, flow: bool, key: bool = False) -> str:
        start = self.i
        s = self.s
        while self.i < len(s):
            c = s[self.i]
            if flow and c in ",]}":
                break
            if key and c == ":" and (self.i + 1 == len(s) or s[self.i + 1] in " \t,]}"):
                break
            if not key and c == ":" and (self.i + 1 == len(s) or s[self.i + 1] in " \t"):
                self.error("unexpected ': ' in a plain value; quote the value")
            self.i += 1
        return s[start:self.i].strip()

    def double_quoted(self) -> str:
        assert self.s[self.i] == '"'
        self.i += 1
        out = []
        s = self.s
        while True:
            if self.i >= len(s):
                self.error("unterminated double-quoted string")
            c = s[self.i]
            if c == '"':
                self.i += 1
                return "".join(out)
            if c == "\\":
                self.i += 1
                if self.i >= len(s):
                    self.error("unterminated escape in double-quoted string")
                e = s[self.i]
                if e in _DQ_ESCAPES:
                    out.append(_DQ_ESCAPES[e])
                    self.i += 1
                elif e in "xuU":
                    n = {"x": 2, "u": 4, "U": 8}[e]
                    digits = s[self.i + 1:self.i + 1 + n]
                    if len(digits) != n or not all(d in "0123456789abcdefABCDEF" for d in digits):
                        self.error("invalid \\%s escape" % e)
                    out.append(chr(int(digits, 16)))
                    self.i += 1 + n
                else:
                    self.error("invalid escape \\%s" % e)
                continue
            out.append(c)
            self.i += 1

    def single_quoted(self) -> str:
        assert self.s[self.i] == "'"
        self.i += 1
        out = []
        s = self.s
        while True:
            if self.i >= len(s):
                self.error("unterminated single-quoted string")
            c = s[self.i]
            if c == "'":
                if self.i + 1 < len(s) and s[self.i + 1] == "'":
                    out.append("'")
                    self.i += 2
                    continue
                self.i += 1
                return "".join(out)
            out.append(c)
            self.i += 1

    def flow_seq(self) -> Node:
        self.i += 1  # [
        items = []
        while True:
            self.ws()
            c = self.peek()
            if c == "":
                self.error("unterminated flow sequence (multi-line flow collections are not supported)")
            if c == "]":
                self.i += 1
                return Node(items, self.line)
            items.append(self.value(flow=True))
            self.ws()
            c = self.peek()
            if c == ",":
                self.i += 1
            elif c != "]":
                self.error("expected ',' or ']' in flow sequence")

    def flow_map(self) -> Node:
        self.i += 1  # {
        result = {}
        while True:
            self.ws()
            c = self.peek()
            if c == "":
                self.error("unterminated flow mapping (multi-line flow collections are not supported)")
            if c == "}":
                self.i += 1
                return Node(result, self.line)
            if c == '"':
                key = self.double_quoted()
            elif c == "'":
                key = self.single_quoted()
            else:
                key = self.plain(flow=True, key=True)
            if key == "":
                self.error("empty key in flow mapping")
            self.ws()
            if self.peek() == ":":
                self.i += 1
                self.ws()
                if self.peek() in (",", "}"):
                    value = Node(None, self.line)
                else:
                    value = self.value(flow=True)
            else:
                value = Node(None, self.line)
            if key in result:
                if self.duplicates is None:
                    self.error("duplicate key %r" % key)
                self.duplicates.append((key, self.line))
            else:
                result[key] = value
            self.ws()
            c = self.peek()
            if c == ",":
                self.i += 1
            elif c != "}":
                self.error("expected ',' or '}' in flow mapping")


# --------------------------------------------------------------------------- block structure


class _Line:
    __slots__ = ("idx", "no", "indent", "text", "tab")

    def __init__(self, idx, no, indent, text, tab):
        self.idx = idx
        self.no = no
        self.indent = indent
        self.text = text
        self.tab = tab


def strip_comment(s: str) -> str:
    """Remove a trailing ``# comment`` that is outside quotes."""
    quote = None
    prev = " "
    i = 0
    while i < len(s):
        c = s[i]
        if quote == '"':
            if c == "\\":
                i += 2
                continue
            if c == '"':
                quote = None
        elif quote == "'":
            if c == "'":
                quote = None
        else:
            if c in "\"'" and prev in " \t[{,:-":
                quote = c
            elif c == "#" and prev in " \t":
                return s[:i].rstrip()
        prev = c
        i += 1
    return s.rstrip()


def _is_seq_item(text: str) -> bool:
    return text == "-" or text.startswith("- ") or text.startswith("-\t")


def _split_key(text: str, line: int):
    """Return (key, rest) if ``text`` is ``key: rest``, else None."""
    if not text or text[0] in "[{":
        return None
    if text[0] in "\"'":
        p = _Inline(text, line)
        key = p.double_quoted() if text[0] == '"' else p.single_quoted()
        rest = text[p.i:].lstrip(" \t")
        if rest == ":" or rest.startswith(": ") or rest.startswith(":\t"):
            return key, rest[1:].strip()
        return None
    if text[0] == "?":
        raise YamlError("complex keys are not supported", line)
    for i, c in enumerate(text):
        if c == ":" and (i + 1 == len(text) or text[i + 1] in " \t"):
            return text[:i].rstrip(), text[i + 1:].strip()
        if c == " " and i + 1 < len(text) and text[i + 1] == "#":
            return None
    return None


class _Block:
    def __init__(self, text: str, line_offset: int, duplicates=None):
        self.duplicates = duplicates
        self.raw = text.splitlines()
        self.offset = line_offset
        self.lines = []
        for idx, raw in enumerate(self.raw):
            stripped = raw.lstrip(" ")
            indent = len(raw) - len(stripped)
            tab = stripped.startswith("\t")
            content = strip_comment(stripped)
            if not content.strip():
                continue
            if indent == 0 and content in ("---", "..."):
                continue
            self.lines.append(_Line(idx, idx + 1 + line_offset, indent, content, tab))
        self.pos = 0

    def cur(self):
        if self.pos >= len(self.lines):
            return None
        line = self.lines[self.pos]
        if line.tab:
            raise YamlError("tabs are not allowed for indentation", line.no)
        return line

    def parse(self) -> Node:
        first = self.cur()
        if first is None:
            return Node(None, 1 + self.offset)
        node = self.block(first.indent)
        rest = self.cur()
        if rest is not None:
            raise YamlError("unexpected content (check indentation)", rest.no)
        return node

    def block(self, indent: int) -> Node:
        line = self.cur()
        if _is_seq_item(line.text):
            return self.seq(indent)
        if _split_key(line.text, line.no) is not None:
            return self.mapping(indent)
        # a lone scalar / flow collection
        self.pos += 1
        node = _Inline(line.text, line.no, self.duplicates).parse_document_value()
        return self.fold_plain(node, line.text, indent - 1)

    def mapping(self, indent: int) -> Node:
        result = {}
        start = self.cur().no
        while True:
            line = self.cur()
            if line is None or line.indent < indent:
                break
            if line.indent > indent:
                raise YamlError("unexpected indentation", line.no)
            if _is_seq_item(line.text):
                raise YamlError("unexpected sequence item inside a mapping", line.no)
            kv = _split_key(line.text, line.no)
            if kv is None:
                raise YamlError("expected 'key: value'", line.no)
            key, rest = kv
            if key == "":
                raise YamlError("empty key", line.no)
            self.pos += 1
            value = self.value(rest, indent, line, in_mapping=True)
            value.key_line = line.no
            if key in result:
                if self.duplicates is None:
                    raise YamlError("duplicate key %r" % key, line.no)
                self.duplicates.append((key, line.no))
            else:
                result[key] = value
        return Node(result, start)

    def seq(self, indent: int) -> Node:
        items = []
        start = self.cur().no
        while True:
            line = self.cur()
            if line is None or line.indent < indent:
                break
            if line.indent > indent:
                raise YamlError("unexpected indentation", line.no)
            if not _is_seq_item(line.text):
                break
            rest = line.text[1:].lstrip(" \t")
            if rest and (_is_seq_item(rest) or _split_key(rest, line.no) is not None):
                # compact nested collection: "- key: value" or "- - x"
                inner = line.indent + (len(line.text) - len(rest))
                self.lines[self.pos] = _Line(line.idx, line.no, inner, rest, False)
                items.append(self.block(inner))
            else:
                self.pos += 1
                items.append(self.value(rest, indent, line, in_mapping=False))
        return Node(items, start)

    def value(self, rest: str, indent: int, line: _Line, in_mapping: bool) -> Node:
        if rest == "":
            nxt = self.cur()
            if nxt is not None and nxt.indent > indent:
                return self.block(nxt.indent)
            if in_mapping and nxt is not None and nxt.indent == indent and _is_seq_item(nxt.text):
                return self.seq(indent)
            return Node(None, line.no)
        if rest[0] in "|>":
            return self.block_scalar(rest, indent, line)
        node = _Inline(rest, line.no, self.duplicates).parse_document_value()
        return self.fold_plain(node, rest, indent)

    def fold_plain(self, node: Node, text: str, indent: int) -> Node:
        """Fold continuation lines (indented deeper than ``indent``) into a plain scalar."""
        nxt = self.cur()
        if nxt is None or nxt.indent <= indent:
            return node
        if not node.is_scalar or text[:1] in "\"'":
            raise YamlError("unexpected indentation (multi-line quoted or flow values are not supported)", nxt.no)
        parts = [text.strip()]
        while True:
            nxt = self.cur()
            if nxt is None or nxt.indent <= indent:
                break
            if _is_seq_item(nxt.text) or _split_key(nxt.text, nxt.no) is not None:
                raise YamlError("mapping values are not allowed here (check indentation)", nxt.no)
            parts.append(nxt.text.strip())
            self.pos += 1
        return _plain_node(" ".join(parts), node.line)

    def block_scalar(self, header: str, indent: int, line: _Line) -> Node:
        m = re.match(r"^([|>])([-+]?)([1-9]?)([-+]?)$", header)
        if not m:
            raise YamlError("invalid block scalar header %r" % header, line.no)
        style = m.group(1)
        chomp = m.group(2) or m.group(4)
        explicit = int(m.group(3)) if m.group(3) else None
        # collect raw lines following the header line
        body = []
        idx = line.idx + 1
        content_indent = indent + explicit if explicit else None
        while idx < len(self.raw):
            raw = self.raw[idx]
            if raw.strip() == "":
                body.append("")
                idx += 1
                continue
            ind = len(raw) - len(raw.lstrip(" "))
            if content_indent is None:
                if ind <= indent:
                    break
                content_indent = ind
            if ind < content_indent:
                break
            body.append(raw[content_indent:])
            idx += 1
        last_idx = idx - 1
        while self.pos < len(self.lines) and self.lines[self.pos].idx <= last_idx:
            self.pos += 1
        # trailing blank lines belong to chomping
        trailing = 0
        while body and body[-1] == "":
            body.pop()
            trailing += 1
        if style == "|":
            text = "\n".join(body)
        else:
            parts = []
            for b in body:
                if b == "":
                    parts.append("\n")
                elif parts and not parts[-1].endswith("\n"):
                    parts.append(" " + b)
                else:
                    parts.append(b)
            text = "".join(parts)
        if body:
            if chomp == "-":
                pass
            elif chomp == "+":
                text += "\n" * (1 + trailing)
            else:
                text += "\n"
        return Node(text, line.no, raw=text)


def parse(text: str, line_offset: int = 0, duplicates=None) -> Node:
    """Parse a YAML-subset document. ``line_offset`` is added to reported line numbers.

    Duplicate mapping keys raise YamlError, unless a ``duplicates`` list is given:
    then ``(key, line)`` pairs are appended to it and the first value is kept."""
    if text.startswith("\ufeff"):
        text = text[1:]
    return _Block(text, line_offset, duplicates).parse()


# --------------------------------------------------------------------------- frontmatter


class FrontmatterError(Exception):
    def __init__(self, message: str, line: int):
        super().__init__(message)
        self.message = message
        self.line = line


def split_frontmatter(text: str):
    """Split ``---``-delimited frontmatter from a Markdown file.

    Returns ``(yaml_text, yaml_first_line, body)`` where ``yaml_first_line`` is the
    1-based line number of the first frontmatter line in the file.
    """
    if text.startswith("\ufeff"):
        text = text[1:]
    lines = text.splitlines()
    if not lines or lines[0].rstrip() != "---":
        raise FrontmatterError("SKILL.md must start with a '---' frontmatter line", 1)
    for i in range(1, len(lines)):
        if lines[i].rstrip() in ("---", "..."):
            return "\n".join(lines[1:i]), 2, "\n".join(lines[i + 1:])
    raise FrontmatterError("frontmatter is not closed with '---'", 1)
