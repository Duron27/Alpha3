"""Mirror of ShaderManager::setShaderSource for OpenGL ES: appends the linked library shaders to the main one.
    merge_linked.py main.glsl [lib.glsl ...]  >  merged.glsl
A library cannot repeat the #version line. Separate shader objects may declare the same uniform, one shader may not,
so every plain uniform declaration is made to be skipped when an earlier one is active (the preprocessor decides,
whatever the #if nesting)."""
import os
import re
import sys

UNIFORM = re.compile(r"^(\s*)uniform\s+[\w\s]+?\s+(\w+)\s*(\[[^\]]*\])?\s*;\s*$")


def once(line):
    m = UNIFORM.match(line)
    if not m or "=" in line:
        return line
    guard = "OMW_UNIFORM_" + m.group(2)
    return f"#ifndef {guard}\n#define {guard}\n{line}\n#endif"


def fold(text, drop_version):
    lines = []
    for line in text.split("\n"):
        if drop_version and line.strip().startswith("#version"):
            line = ""
        lines.append(once(line))
    return "\n".join(lines)


out = fold(open(sys.argv[1]).read(), False)
libs = [lib for lib in sys.argv[2:] if os.path.exists(lib)]
for lib in libs:
    out += "\n// ---- " + lib + "\n" + fold(open(lib).read(), True) + "\n"
sys.stdout.write(out)
