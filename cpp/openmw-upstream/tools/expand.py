"""Host-side mirror of Shader::ShaderManager's template expansion (#include, @define, $foreach, $link), used to feed the
OpenGL ES translator and glslangValidator. Usage: expand.py <shaders dir> <template> [KEY=VALUE ...] -o <outdir>"""
import os, re, sys

def parse_includes(root, source, fname, seen=()):
    source = source.replace('\r\n', '\n')
    seen = set(seen) | {fname}
    while True:
        m = re.search(r'#include\s*"([^"]+)"', source)
        if not m:
            return source
        inc = m.group(1)
        if not (inc.startswith('lib/') or inc.startswith('core/') or inc.startswith('compatibility/')):
            inc = os.path.join(os.path.dirname(fname), inc)
        if inc in seen:
            raise SystemExit('cyclic include ' + inc)
        text = open(os.path.join(root, inc)).read()
        text = parse_includes(root, text, inc, seen)
        source = source[:m.start()] + text + '\n' + source[m.end():]

def expand(root, name, defines):
    if not (name.startswith('lib/') or name.startswith('core/') or name.startswith('compatibility/')):
        name = 'compatibility/' + name
    src = parse_includes(root, open(os.path.join(root, name)).read(), name)
    # defines: @name -> value; $foreach handled by iterating
    def foreach(src):
        while True:
            m = re.search(r'[@$]foreach\s+(\S+?)([ \n\r()\[\].;,])(.*?)\n(.*?)[@$]endforeach', src, re.S)
            if not m:
                return src
            it = '$' + m.group(1) if not m.group(1).startswith('$') else m.group(1)
            first = m.group(0)
            # list is the rest of the line after the iterator name
            line_end = src.index('\n', m.start())
            header = src[m.start():line_end]
            parts = header.split(None, 2)
            itname = '$' + parts[1]
            lst = parts[2] if len(parts) > 2 else ''
            elems = [e for e in lst.split(',')] if lst else []
            body_start = line_end + 1
            end = src.index('endforeach', body_start)
            body = src[body_start:end-1]
            rep = ''.join(body.replace(itname, e) for e in elems)
            src = src[:m.start()] + rep + src[end + len('endforeach'):]
    # substitute defines (iterate, a value may contain further @refs)
    # first resolve @foreach list headers: they reference defines
    out = []
    links = []
    def subst(src):
        pos = 0
        res = ''
        iters = []
        while True:
            i = src.find('@', pos)
            if i < 0:
                return res + src[pos:]
            j = i + 1
            while j < len(src) and src[j] not in ' \n\r()[].;,':
                j += 1
            key = src[i+1:j]
            if key == 'foreach':
                k = j + 1
                while k < len(src) and src[k] not in ' \n\r()[].;,':
                    k += 1
                iters.append(src[j+1:k])
                res += src[pos:i] + '$foreach'
                pos = j
                continue
            if key in ('endforeach', 'link'):
                res += src[pos:i] + '$' + key
                pos = j
                continue
            if key in iters:
                res += src[pos:i] + '$' + key
                pos = j
                continue
            if key not in defines:
                raise SystemExit('undefined @%s in %s' % (key, name))
            res += src[pos:i] + defines[key]
            pos = j
    src = subst(src)
    # foreach
    while '$foreach' in src:
        i = src.index('$foreach')
        le = src.index('\n', i)
        hdr = src[i:le].split(None, 2)
        itname = '$' + hdr[1]
        elems = hdr[2].split(',') if len(hdr) > 2 and hdr[2].strip() else []
        bs = le + 1
        e = src.index('$endforeach', bs)
        body = src[bs:e]
        src = src[:i] + ''.join(body.replace(itname, el) for el in elems) + src[e+len('$endforeach'):]
    # links
    while True:
        m = re.search(r'\$link\s+"([^"]+)"(?:\s+if\s+(!)?\s*([_A-Za-z0-9]+)?)?\s*\n', src)
        if not m:
            break
        cond = True
        if m.group(0).strip().split('if', 1)[1:] and ' if ' in m.group(0):
            val = m.group(3)
            cond = not (val is None or val == '' or val == '0')
            if m.group(2) == '!':
                cond = not cond
        if cond and m.group(1) != name and m.group(1) not in links:
            links.append(m.group(1))
        src = src[:m.start()] + '\n' + src[m.end():]
    return src, links

def main():
    root, tmpl = sys.argv[1], sys.argv[2]
    outdir = None
    defines = {}
    a = sys.argv[3:]
    i = 0
    while i < len(a):
        if a[i] == '-o':
            outdir = a[i+1]; i += 2
        else:
            k, v = a[i].split('=', 1); defines[k] = v; i += 1
    src, links = expand(root, tmpl, defines)
    os.makedirs(outdir, exist_ok=True)
    base = tmpl.replace('/', '_')
    open(os.path.join(outdir, base), 'w').write(src)
    for n, l in enumerate(links):
        s, _ = expand(root, l, defines)
        ext = os.path.splitext(tmpl)[1]
        open(os.path.join(outdir, '%s.link%d%s' % (base, n, ext)), 'w').write(s)
        print('link', l, file=sys.stderr)
if __name__ == '__main__':
    main()
