"""Append string / plural resources to values/<file> (English) and values-zh/<file> (Simplified Chinese).

Usage (from python):
    from addstr import add
    add("strings_library.xml", [
        ("name", "English", "中文"),
        ("plural_name", {"one": "%1$d item", "other": "%1$d items"}, {"other": "%1$d 项"}),
    ])
Existing entries with the same name are replaced.
"""
import os
import re

RES = "/home/ubuntu/KavitaInk/app/src/main/res"


def esc(s: str) -> str:
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("'", "\\'").replace('"', '\\"')
    s = s.replace("\n", "\\n")
    if s.startswith("@") or s.startswith("?"):
        s = "\\" + s
    return s


def _write(path: str, entries):
    if os.path.exists(path):
        s = open(path, encoding="utf-8").read()
    else:
        s = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
    for name, val in entries:
        s = re.sub(r'\n?\s*<string name="%s">.*?</string>' % re.escape(name), "", s, flags=re.S)
        s = re.sub(r'\n?\s*<plurals name="%s">.*?</plurals>' % re.escape(name), "", s, flags=re.S)
        if isinstance(val, dict):
            items = "".join('        <item quantity="%s">%s</item>\n' % (q, esc(v)) for q, v in val.items())
            block = '    <plurals name="%s">\n%s    </plurals>\n' % (name, items)
        else:
            block = '    <string name="%s">%s</string>\n' % (name, esc(val))
        s = s.replace("</resources>", block + "</resources>")
    s = re.sub(r"\n{3,}", "\n\n", s)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w", encoding="utf-8").write(s)


def add(fname: str, entries):
    _write(os.path.join(RES, "values", fname), [(n, en) for n, en, _ in entries])
    _write(os.path.join(RES, "values-zh", fname), [(n, zh) for n, _, zh in entries])


def rewrite(path: str, pairs):
    """Apply exact (old, new) replacements to a source file; each old must occur at least once."""
    s = open(path, encoding="utf-8").read()
    for old, new in pairs:
        if old not in s:
            raise SystemExit("not found in %s:\n%s" % (path, old))
        s = s.replace(old, new)
    open(path, "w", encoding="utf-8").write(s)
