#!/usr/bin/env python3
"""Builds the Lua mod creator's API catalog from the engine's own Lua documentation.

The engine documents its Lua API as LuaDoc comments (resources/lua_api/openmw/*.lua, the built-in interfaces in
resources/vfs/scripts/omw, openmw_aux in resources/vfs/openmw_aux, multiplayer's openmw.net and openmw.modstore) and its
engine handlers and built-in events in docs/source/reference/lua-scripting/*.rst. This reads them all into one JSON
file the launcher ships (composeResources/files/lua_api.json): every package, interface, type, field and function with
its arguments, results, description, usage examples and the script kinds it may be used in, so the creator's menus and
its checker come from the same API the game runs.

    python3 lua_api_catalog.py --resources <engine resources> --docs <engine docs/source/reference/lua-scripting>
        --multiplayer <support/multiplayer/tree/files/lua_api> --out <lua_api.json>

Run it again when the engine is updated (tools/lua_api_catalog.py --help for the defaults, which are this checkout's).
"""
import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
ALL_CONTEXTS = ["global", "local", "player", "menu", "load"]

# Engine handlers the engine calls that no documentation describes (found in apps/openmw/mwlua/*.hpp). Handlers whose
# name starts with an underscore are the built-in scripts' own and are left out.
EXTRA_HANDLERS = {
    "onMainMenuButton": (["id"], ["menu"], "A button added with ui.addMainMenuButton was clicked; `id` is the button's id."),
}
SOURCE_CONTEXTS = {"globalscripts": ["global"], "localscripts": ["local", "player"], "playerscripts": ["player"],
                   "menuscripts": ["menu"], "loadscripts": ["load"]}


def with_docs_patch(api, docs, patch):
    """The API docs and reference pages with multiplayer's docs.patch applied (the Android build does not apply it)."""
    tmp = tempfile.mkdtemp(prefix="lua_api_")
    shutil.copytree(api, os.path.join(tmp, "files", "lua_api"))
    shutil.copytree(docs, os.path.join(tmp, "docs", "source", "reference", "lua-scripting"))
    if patch and os.path.isfile(patch):
        res = subprocess.run(["git", "apply", "--include=files/lua_api/openmw/*",
                              "--include=docs/source/reference/lua-scripting/engine_handlers.rst", patch],
                             cwd=tmp, capture_output=True, text=True)
        if res.returncode != 0:
            print("docs.patch did not apply (already applied?): " + res.stderr.strip(), file=sys.stderr)
    return tmp, os.path.join(tmp, "files", "lua_api"), os.path.join(tmp, "docs", "source", "reference", "lua-scripting")


def source_handlers(src):
    """Engine handlers by the scripts' kind, from the engine's sources: what the engine really calls."""
    out = {}
    for path in glob.glob(os.path.join(src, "apps", "openmw", "mwlua", "*scripts.hpp")):
        kind = SOURCE_CONTEXTS.get(os.path.splitext(os.path.basename(path))[0])
        if not kind:
            continue
        for name in re.findall(r'EngineHandlerList\s+\w+\{\s*"(\w+)"\s*\}', open(path, encoding="utf-8").read()):
            out.setdefault(name, set()).update(kind)
    return out


def doc_blocks(text):
    """The LuaDoc comment blocks of a file: runs of `--` lines that start with `---`, without the dashes."""
    blocks, cur = [], None
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("---"):
            if cur is not None:
                blocks.append(cur)
            rest = s.lstrip("-").strip()
            # a line of dashes alone (the stdlib's separator) starts a block with no text
            cur = [rest] if rest else []
        elif s.startswith("--") and cur is not None:
            body = s[2:]
            cur.append(body[1:] if body.startswith(" ") else body)
        else:
            if cur is not None:
                blocks.append(cur)
            cur = None
    if cur is not None:
        blocks.append(cur)
    return blocks


TAG = re.compile(r"^@(\w+)\s*(.*)$")
PARENT = re.compile(r"^\[\s*parent\s*=\s*#(\w+)\s*\]\s*(.*)$")


def split_type(rest):
    """`#string name text` or `openmw.core#GameObject name text` or `name text` -> (type, remainder)."""
    m = re.match(r"^((?:[\w.]+)?#[\w.<>,#\s]*?[\w>])(?:\s+|$)(.*)$", rest)
    if m and "#" in m.group(1):
        t = m.group(1)
        # #list<#X> and #map<#a, #b> hold spaces only inside the brackets
        if t.count("<") != t.count(">"):
            depth, i = 0, 0
            for i, c in enumerate(rest):
                if c == "<":
                    depth += 1
                elif c == ">":
                    depth -= 1
                elif c.isspace() and depth == 0 and i > 0:
                    break
            return rest[:i].strip(), rest[i:].strip()
        return t, m.group(2)
    return None, rest


def norm_type(t, module_id):
    """A type reference made absolute: `#GameObject` in openmw.core -> `openmw.core#GameObject`."""
    if not t:
        return None
    t = t.strip()
    if t.startswith("#"):
        base = t[1:]
        if base in ("string", "number", "boolean", "table", "function", "any", "nil", "userdata", "integer"):
            return base
        if base.startswith("list<") or base.startswith("map<"):
            inner = base[base.index("<") + 1:-1]
            parts = [norm_type(p.strip(), module_id) or "any" for p in inner.split(",")]
            return base[:base.index("<")] + "<" + ",".join(parts) + ">"
        return module_id + "#" + base
    return t


def text_of(lines):
    return "\n".join(lines).strip()


class Catalog:
    def __init__(self):
        self.modules = {}
        self.types = {}

    def type(self, key):
        return self.types.setdefault(key, {"doc": "", "fields": [], "fns": []})

    def read(self, path, module_id, kind, require, access):
        try:
            text = open(path, encoding="utf-8").read()
        except OSError:
            return
        module_name, cur_type = None, None
        for block in doc_blocks(text):
            desc, tags = [], []
            for line in block:
                m = TAG.match(line.strip())
                if m:
                    tags.append([m.group(1), m.group(2).strip()])
                elif tags and line.startswith("  ") and tags[-1][0] in ("field", "param", "return", "function"):
                    tags[-1][1] += " " + line.strip()  # a tag's text continued on the next line
                elif tags and tags[-1][0] == "usage":
                    tags[-1][1] += "\n" + line
                else:
                    desc.append(line)
            names = [t[0] for t in tags]
            doc = text_of(desc)
            if "module" in names:
                module_name = dict(tags)["module"].split()[0]
                contexts = dict(tags).get("context", "global|local|player|menu|load")
                key = module_id + "#" + module_name
                mod = self.modules.setdefault(module_id, {
                    "id": module_id, "name": module_name, "kind": kind, "require": require,
                    "access": access or module_name, "contexts": [], "doc": "", "type": key, "usage": [],
                })
                for c in contexts.split("|"):
                    c = c.strip()
                    if c and c not in mod["contexts"]:
                        mod["contexts"].append(c)
                if doc and not mod["doc"]:
                    mod["doc"] = doc
                mod["usage"] += [u for n, u in tags if n == "usage" and u.strip()]
                for n, v in tags:
                    if n == "extends":
                        self.type(key)["extends"] = norm_type(v.split()[0], module_id)
                self.type(key)
                cur_type = key
                continue
            if module_name is None:
                continue
            if "type" in names:
                tname = dict(tags)["type"].split()[0]
                cur_type = module_id + "#" + tname
                t = self.type(cur_type)
                if doc and not t["doc"]:
                    t["doc"] = doc
                for n, v in tags:
                    if n == "extends":
                        t["extends"] = norm_type(v.split()[0], module_id)
                    elif n == "field":
                        pm = PARENT.match(v)
                        if pm:
                            self.add_field(self.type(module_id + "#" + pm.group(1)), pm.group(2), "", module_id)
                        else:
                            self.add_field(t, v, "", module_id)
                    elif n in ("list", "map"):
                        t[n] = norm_type(v.strip("<> "), module_id)
                continue
            if "function" in names:
                rest = dict(tags)["function"]
                m = PARENT.match(rest)
                if not m:
                    continue
                parent, fname = m.group(1), m.group(2).split()[0]
                # a description written on the tag line itself (the Settings interface does this)
                inline = m.group(2)[len(fname):].strip().lstrip(",").strip()
                fn = {"n": fname, "d": (inline + ("\n" + doc if doc else "")).strip() if inline else doc, "p": [], "r": []}
                for n, v in tags:
                    if n == "param":
                        t, r = split_type(v)
                        pname, _, pdoc = r.partition(" ")
                        if pname in ("self", "Self") or (t is None and pname == "" and r.strip() in ("self", "Self")):
                            fn["m"] = True
                            continue
                        p = {"n": pname or "arg", "t": norm_type(t, module_id) or "any"}
                        if pdoc.strip():
                            p["d"] = pdoc.strip()
                        if re.search(r"\boptional\b|\(optional\)|can be nil|or nil\b", pdoc, re.I):
                            p["o"] = True
                        fn["p"].append(p)
                    elif n == "return":
                        t, r = split_type(v)
                        for i, part in enumerate((t or "").split(",") if t else [None]):
                            ret = {"t": norm_type(part, module_id) if part else "any"}
                            if r and i == 0:
                                ret["d"] = r
                            fn["r"].append(ret)
                    elif n == "usage" and v.strip():
                        fn.setdefault("u", []).append(v.strip("\n"))
                if not fn["r"]:
                    del fn["r"]
                self.type(module_id + "#" + parent)["fns"].append(fn)
                continue
            if "field" in names:
                for n, v in tags:
                    if n != "field":
                        continue
                    m = PARENT.match(v)
                    if m:
                        self.add_field(self.type(module_id + "#" + m.group(1)), m.group(2), doc, module_id,
                                       [u for k, u in tags if k == "usage"])
                    elif cur_type:
                        self.add_field(self.type(cur_type), v, doc, module_id)

    @staticmethod
    def add_field(t, rest, doc, module_id, usage=()):
        ty, r = split_type(rest)
        name, _, fdoc = r.partition(" ")
        if not name:
            return
        f = {"n": name, "t": norm_type(ty, module_id) or "any"}
        d = (fdoc.strip() + ("\n" + doc if doc and fdoc.strip() else doc)).strip()
        if d:
            f["d"] = d
        if usage:
            f["u"] = [u.strip("\n") for u in usage if u.strip()]
        t["fields"] = [x for x in t["fields"] if x["n"] != name] + [f]


def handlers(docs):
    """Engine handlers from engine_handlers.rst, with the script kinds of the section each is listed in."""
    out = {}
    text = open(os.path.join(docs, "engine_handlers.rst"), encoding="utf-8").read()
    contexts = ALL_CONTEXTS
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        badges = re.findall(r"\|bdg-ctx-(\w+)\|", line)
        if badges:
            contexts = ["global", "local", "player", "menu", "load"] if badges == ["all"] else badges
            if "local" in contexts and "player" not in contexts:
                contexts = contexts + ["player"]  # player scripts are local scripts
        m = re.match(r"^\s*\* - \|?\s*(\w+)\((.*?)\)?\s*(?:->\s*(\w+))?\s*$", line)
        if m:
            name, args = m.group(1), m.group(2)
            j = i + 1
            while j < len(lines) and not lines[j].strip().startswith("- "):
                args += lines[j].strip().lstrip("|").strip()  # a signature written over two lines
                j += 1
            args = args.rstrip(")")
            desc = []
            first = lines[j].strip()[2:].lstrip("| ").strip() if j < len(lines) else ""
            desc.append(first)
            k = j + 1
            while k < len(lines) and lines[k].startswith("      ") and lines[k].strip().startswith("|"):
                desc.append(lines[k].strip().lstrip("| ").strip())
                k += 1
            d = re.sub(r"`([^`<]+?) <[^>]+>`_", r"\1", " ".join(desc))
            d = d.replace("``", "`")
            params = [a.strip() for a in args.split(",") if a.strip()]
            h = out.setdefault(name, {"n": name, "p": params, "contexts": [], "d": d})
            if m.group(3):
                h["r"] = m.group(3)
            for c in contexts:
                if c not in h["contexts"]:
                    h["contexts"].append(c)
            i = k
            continue
        i += 1
    return list(out.values())


def events(docs):
    """The built-in events from events.rst: name, section and description (with its first example)."""
    out = []
    text = open(os.path.join(docs, "events.rst"), encoding="utf-8").read()
    section, cur, code = "", None, None
    lines = text.splitlines()
    for idx, line in enumerate(lines):
        if idx + 1 < len(lines) and re.match(r"^-{3,}$", lines[idx + 1]):
            section = line.strip()
            continue
        m = re.match(r"^\*\*(.+?)\*\*\s*$", line.strip())
        if m:
            cur = {"names": [n.strip() for n in m.group(1).split(",")], "section": section, "d": "", "example": ""}
            out.append(cur)
            code = None
            continue
        if cur is None:
            continue
        if line.strip().startswith(".. code-block"):
            code = [] if not cur["example"] else None
            continue
        if code is not None:
            if line.startswith("    ") or not line.strip():
                code.append(line[4:])
                cur["example"] = "\n".join(code).strip()
                continue
            code = None
        if line.strip() and not re.match(r"^[-=]{3,}$", line) and not line.startswith(".."):
            cur["d"] = (cur["d"] + " " + line.strip().replace("``", "`")).strip()
    res, seen = [], set()
    for e in out:
        for n in e["names"]:
            if n in seen:
                continue
            seen.add(n)
            res.append({"n": n, "section": e["section"], "d": e["d"], "example": e["example"]})
    return res


def main():
    cxx = sorted(glob.glob(os.path.join(ROOT, "androidApp/.cxx/*/*/arm64-v8a/openmw-upstream/openmw_upstream-prefix/src/openmw_upstream")))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--resources", default=os.path.join(ROOT, "androidApp/src/main/assets/libopenmw-upstream/resources"))
    ap.add_argument("--docs", default=os.path.join(cxx[-1], "docs/source/reference/lua-scripting") if cxx else None)
    ap.add_argument("--multiplayer", default=os.path.join(ROOT, "cpp/openmw-upstream/support/multiplayer/tree/files/lua_api"))
    ap.add_argument("--docs-patch", default=os.path.join(ROOT, "cpp/openmw-upstream/support/multiplayer/docs.patch"))
    ap.add_argument("--extra", default=os.path.join(HERE, "lua_api_extra"))
    ap.add_argument("--out", default=os.path.join(ROOT, "composeApp/src/commonMain/composeResources/files/lua_api.json"))
    a = ap.parse_args()
    if not a.docs or not os.path.isdir(a.docs):
        sys.exit("no engine docs folder (build the engine once, or pass --docs)")
    a.docs_orig = a.docs

    cat = Catalog()
    tmp, api, a.docs = with_docs_patch(os.path.join(a.resources, "lua_api"), a.docs, a.docs_patch)
    src = os.path.normpath(os.path.join(a.docs_orig, "..", "..", "..", ".."))
    for path in sorted(glob.glob(os.path.join(api, "openmw", "*.lua"))):
        name = os.path.splitext(os.path.basename(path))[0]
        cat.read(path, "openmw." + name, "package", "openmw." + name, name)
    for path in sorted(glob.glob(os.path.join(a.multiplayer, "openmw", "*.lua"))):
        name = os.path.splitext(os.path.basename(path))[0]
        cat.read(path, "openmw." + name, "package", "openmw." + name, name)
        if "openmw." + name in cat.modules:
            cat.modules["openmw." + name]["multiplayer"] = True
    # functions the engine has that no documentation covers
    for path in sorted(glob.glob(os.path.join(a.extra, "openmw", "*.lua"))):
        name = os.path.splitext(os.path.basename(path))[0]
        cat.read(path, "openmw." + name, "package", "openmw." + name, name)
    for path in sorted(glob.glob(os.path.join(a.resources, "vfs", "openmw_aux", "*.lua"))):
        name = os.path.splitext(os.path.basename(path))[0]
        if name.endswith("config"):
            continue
        cat.read(path, "openmw_aux." + name, "aux", "openmw_aux." + name, "aux_" + name)
    for path in sorted(glob.glob(os.path.join(a.resources, "vfs", "scripts", "omw", "**", "*.lua"), recursive=True)):
        text = open(path, encoding="utf-8").read()
        m = re.search(r"--\s*@module\s+(\w+)", text)
        if not m:
            continue
        name = m.group(1)
        cat.read(path, "interfaces." + name, "interface", "openmw.interfaces", "I." + name)
    for path in sorted(glob.glob(os.path.join(api, "*.doclua"))):
        name = os.path.splitext(os.path.basename(path))[0]
        if name == "global":
            cat.read(path, "lua.global", "lua", "", "")
        else:
            cat.read(path, "lua." + name, "lua", "", name)

    interface_table(a.docs, cat.modules)
    # drop empty types; keep functions in documented order
    types = {k: v for k, v in cat.types.items() if v["fields"] or v["fns"] or v.get("doc") or v.get("extends")}
    for t in types.values():
        if not t["doc"]:
            del t["doc"]
        if not t["fields"]:
            del t["fields"]
        if not t["fns"]:
            del t["fns"]
    found = handlers(a.docs)
    known = {h["n"] for h in found}
    for name, kinds in sorted(source_handlers(src).items()):
        if name in known or name.startswith("_"):
            continue
        params, ctx, doc = EXTRA_HANDLERS.get(name, ([], sorted(kinds), "Called by the engine; not described in its documentation."))
        found.append({"n": name, "p": params, "contexts": ctx, "d": doc, "undocumented": True})
    built_in_events = events(a.docs)
    shutil.rmtree(tmp, ignore_errors=True)
    result = {
        "source": "OpenMW Lua API documentation (generated by cpp/openmw-upstream/tools/lua_api_catalog.py)",
        "apiRevision": api_revision(a.docs_orig),
        "modules": sorted(cat.modules.values(), key=lambda m: (["package", "interface", "aux", "lua"].index(m["kind"]), m["id"])),
        "types": types,
        "handlers": found,
        "events": built_in_events,
    }
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"))
    fns = sum(len(t.get("fns", [])) for t in types.values())
    print(f"{a.out}: {len(result['modules'])} modules, {len(types)} types, {fns} functions, "
          f"{len(result['handlers'])} handlers, {len(result['events'])} events")


def api_revision(docs):
    """OPENMW_LUA_API_REVISION from the engine's CMakeLists.txt (docs/source/reference/lua-scripting is 4 levels down)."""
    try:
        cmake = os.path.join(docs, "..", "..", "..", "..", "CMakeLists.txt")
        m = re.search(r"OPENMW_LUA_API_REVISION\s+(\d+)", open(cmake, encoding="utf-8").read())
        return int(m.group(1)) if m else 0
    except OSError:
        return 0


def interface_table(docs, modules):
    """The interfaces' script kinds and summaries from tables/interfaces.rst: some interface files say no @context."""
    try:
        text = open(os.path.join(docs, "tables", "interfaces.rst"), encoding="utf-8").read()
    except OSError:
        return
    for m in re.finditer(r":doc:`(\w+) <[^>]+>`\s*\n\s*- (.*?)\n\s*- (.*?)\n", text):
        mod = modules.get("interfaces." + m.group(1))
        if not mod:
            continue
        mod["contexts"] = re.findall(r"bdg-ctx-(\w+)", m.group(2))
        mod["summary"] = m.group(3).strip()


if __name__ == "__main__":
    main()
