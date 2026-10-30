#!/usr/bin/env python3
"""Writes angle_sources_ios.cmake: ANGLE's source lists for iOS with the Metal back end, read from ANGLE's .gni files.

Only needed to update the lists for a new ANGLE version; the normal build does not run it. Unlike gen_sources.py (Android)
it needs neither gn nor ANGLE's DEPS: the .gni files are plain lists with `if` blocks, evaluated here for the
configuration below, and the groups are put together the way ANGLE's BUILD.gn does for

    target_os = "ios"  angle_enable_metal = true  angle_enable_vulkan = false  angle_enable_gl = false
    angle_enable_null = false  angle_enable_cl = false  angle_has_astc_encoder = false

Usage:  gen_sources_apple.py <angle root> <output .cmake> [angle commit]
"""
import os
import re
import sys

CONFIG = {
    'is_apple': True, 'is_ios': True, 'is_mac': False, 'is_android': False, 'is_linux': False, 'is_chromeos': False,
    'is_fuchsia': False, 'is_win': False, 'is_debug': False, 'angle_is_winuwp': False,
    'angle_enable_cl': False, 'angle_enable_vulkan': False, 'angle_enable_metal': True, 'angle_enable_gl': False,
    'angle_enable_null': False, 'angle_enable_msl': True, 'angle_enable_essl': False, 'angle_enable_glsl': False,
    'angle_enable_hlsl': False, 'angle_enable_wgpu': False, 'angle_ir': False, 'angle_has_astc_encoder': False,
    'angle_enable_unwind_backtrace_support': False, 'angle_has_build': False, 'angle_enable_cgl': False,
    'angle_enable_d3d9': False, 'angle_enable_d3d11': False, 'use_fuzzing_engine': False, 'use_ozone': False,
    'target_os': 'ios', 'host_os': 'mac',
}

TOKEN = re.compile(r'\s*(?:(#[^\n]*)|("(?:[^"\\]|\\.)*")|([A-Za-z_][A-Za-z0-9_]*)|(\d+)|(\+=|-=|==|!=|&&|\|\||<=|>=|[-+=!<>()\[\]{},.]))')


def tokenize(text):
    out, pos = [], 0
    while pos < len(text):
        m = TOKEN.match(text, pos)
        if not m or m.end() == pos:
            if text[pos:].strip() == '':
                break
            raise SyntaxError(f'cannot tokenize near {text[pos:pos + 40]!r}')
        pos = m.end()
        comment, string, ident, number, op = m.groups()
        if comment:
            continue
        if string is not None:
            out.append(('str', string[1:-1]))
        elif ident is not None:
            out.append(('id', ident))
        elif number is not None:
            out.append(('num', int(number)))
        elif op is not None:
            out.append(('op', op))
    return out


class Evaluator:
    """Just enough of gn for ANGLE's source list files: assignments, lists, + and -, if/else, and skipped calls."""

    def __init__(self, env):
        self.env = env

    def run(self, path):
        self.toks = tokenize(open(path).read())
        self.i = 0
        self.block(execute=True, until=None)

    def peek(self, k=0):
        return self.toks[self.i + k] if self.i + k < len(self.toks) else ('eof', None)

    def take(self, kind=None, value=None):
        t = self.peek()
        if (kind and t[0] != kind) or (value and t[1] != value):
            raise SyntaxError(f'expected {value or kind}, got {t}')
        self.i += 1
        return t

    def block(self, execute, until):
        while self.peek() != ('eof', None) and self.peek() != until:
            self.statement(execute)

    def braced(self, execute):
        self.take('op', '{')
        self.block(execute, ('op', '}'))
        self.take('op', '}')

    def statement(self, execute):
        kind, value = self.peek()
        if kind == 'id' and value == 'if':
            self.take()
            self.take('op', '(')
            cond = self.expr()
            self.take('op', ')')
            run = execute and bool(cond)
            self.braced(run)
            while self.peek() == ('id', 'else'):
                self.take()
                if self.peek() == ('id', 'if'):
                    self.take()
                    self.take('op', '(')
                    c = self.expr()
                    self.take('op', ')')
                    branch = execute and not run and bool(c)
                    self.braced(branch)
                    run = run or branch
                else:
                    self.braced(execute and not run)
                    break
            return
        if kind == 'id' and self.peek(1) == ('op', '('):
            # import(), assert(), template(), declare_args() ...: skipped with their block
            self.take()
            depth = 0
            while True:
                t = self.take()
                if t == ('eof', None):
                    raise SyntaxError('unbalanced parentheses')
                if t == ('op', '('):
                    depth += 1
                elif t == ('op', ')'):
                    depth -= 1
                    if depth == 0:
                        break
            if self.peek() == ('op', '{'):
                self.braced(False)
            return
        name = self.take('id')[1]
        op = self.take('op')[1]
        value = self.expr()
        if not execute:
            return
        if op == '=':
            self.env[name] = value
        elif op == '+=':
            self.env[name] = self.env[name] + value
        elif op == '-=':
            self.env[name] = [v for v in self.env[name] if v not in value]
        else:
            raise SyntaxError(f'unknown assignment {op}')

    # expressions, lowest precedence first
    def expr(self):
        return self.or_expr()

    def or_expr(self):
        v = self.and_expr()
        while self.peek() == ('op', '||'):
            self.take()
            r = self.and_expr()
            v = v or r
        return v

    def and_expr(self):
        v = self.cmp_expr()
        while self.peek() == ('op', '&&'):
            self.take()
            r = self.cmp_expr()
            v = v and r
        return v

    def cmp_expr(self):
        v = self.add_expr()
        while self.peek()[1] in ('==', '!='):
            op = self.take()[1]
            r = self.add_expr()
            v = (v == r) if op == '==' else (v != r)
        return v

    def add_expr(self):
        v = self.unary()
        while self.peek()[1] in ('+', '-') and self.peek()[0] == 'op':
            op = self.take()[1]
            r = self.unary()
            v = v + r if op == '+' else [x for x in v if x not in r]
        return v

    def unary(self):
        if self.peek() == ('op', '!'):
            self.take()
            return not self.unary()
        return self.primary()

    def primary(self):
        kind, value = self.take()
        if kind == 'str' or kind == 'num':
            return value
        if kind == 'op' and value == '(':
            v = self.expr()
            self.take('op', ')')
            return v
        if kind == 'op' and value == '[':
            items = []
            while self.peek() != ('op', ']'):
                items.append(self.expr())
                if self.peek() == ('op', ','):
                    self.take()
            self.take('op', ']')
            return items
        if kind == 'id':
            if value in ('true', 'false'):
                return value == 'true'
            if value == 'defined':
                self.take('op', '(')
                name = self.take('id')[1]
                self.take('op', ')')
                return name in self.env
            if value not in self.env:
                raise NameError(f'{value} is not set; add it to CONFIG')
            return self.env[value]
        raise SyntaxError(f'unexpected {kind} {value!r}')


COMPILED = ('.c', '.cc', '.cpp', '.mm')


def main():
    root, out = sys.argv[1:3]
    commit = sys.argv[3] if len(sys.argv) > 3 else 'unknown'
    env = dict(CONFIG)
    ev = Evaluator(env)
    for f in ('src/libGLESv2.gni', 'src/compiler.gni'):
        ev.run(os.path.join(root, f))
    metal = Evaluator(dict(CONFIG))
    metal.run(os.path.join(root, 'src/libANGLE/renderer/metal/metal_backend.gni'))
    metal_sources = ['src/libANGLE/renderer/metal/' + s for s in metal.env['metal_backend_sources']]

    # The groups, as BUILD.gn assembles its targets for this configuration
    groups = {
        'xxhash': env['xxhash_sources'],
        'common': env['libangle_common_sources'],
        'common_shader_state': env['libangle_common_shader_state_sources'],
        'image_util': env['libangle_image_util_sources'],
        'gpu_info_util': env['libangle_gpu_info_util_sources'] + env['libangle_gpu_info_util_ios_sources'],
        'preprocessor': env['angle_preprocessor_sources'],
        'translator': env['angle_translator_sources'] + env['angle_translator_essl_symbol_table_sources']
        + env['angle_translator_lib_msl_sources'],
        'version_info': ['src/common/angle_version_info.cpp'],
        'libangle': env['libangle_sources'] + env['libangle_mac_sources'] + ['src/libANGLE/renderer/driver_utils_ios.mm'],
        'metal_backend': metal_sources,
        'frame_capture_mock': ['src/libANGLE/capture/FrameCapture_mock.cpp', 'src/libANGLE/capture/serialize_mock.cpp'],
        'compression': ['third_party/zlib/google/compression_utils_portable.cc'],
        'libGLESv2': env['libglesv2_entry_point_sources'] + env['libglesv2_sources'],
        # Built without ANGLE_USE_EGL_LOADER into the same library: eglXxx call libGLESv2's EGL_Xxx directly
        'libEGL': env['libegl_sources'],
    }

    seen = {}
    lines = [
        '# Generated by tools/gen_sources_apple.py from ANGLE\'s .gni files (iOS, Metal only, configuration in the script).',
        f'# ANGLE commit {commit}. Paths are relative to the ANGLE source root. Do not edit by hand.',
        '',
    ]
    for name, files in groups.items():
        sources = []
        for rel in files:
            if not rel.endswith(COMPILED):
                continue
            if rel.startswith('third_party/'):
                pass  # fetched next to ANGLE by the CMake build
            elif not os.path.exists(os.path.join(root, rel)):
                sys.exit(f'{name}: {rel} does not exist in {root}')
            if rel in seen:
                if seen[rel] == name:
                    continue
                sys.exit(f'{rel} is in both {seen[rel]} and {name}')
            seen[rel] = name
            sources.append(rel)
        lines.append(f'set(ANGLE_IOS_SRC_{name}')
        lines += [f'    {s}' for s in sorted(sources)]
        lines += [')', '']

    open(out, 'w').write('\n'.join(lines))
    print(f'{len(seen)} compiled sources in {len(groups)} groups -> {out}')


if __name__ == '__main__':
    main()
