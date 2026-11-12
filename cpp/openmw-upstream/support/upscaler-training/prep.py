# prep.py <hd textures dir> <out dir> <bsa>...: pairs of (vanilla, the HD texture at twice the vanilla size), as PNG, RGB
import struct, sys, io, os, random
from PIL import Image
met, out, bsas = sys.argv[1], sys.argv[2], sys.argv[3:]
metFiles = {}
for root, _, files in os.walk(met):
    for f in files:
        if f.lower().endswith('.dds'):
            rel = os.path.relpath(os.path.join(root, f), met).replace(os.sep, '\\').lower()
            metFiles[rel] = os.path.join(root, f)
os.makedirs(out + '/lr', exist_ok=True); os.makedirs(out + '/hr', exist_ok=True)
done = set(); skipped = 0
for bsa in bsas:
    d = open(bsa, 'rb').read()
    ver, hashoff, n = struct.unpack_from('<III', d, 0)
    recs = [struct.unpack_from('<II', d, 12 + 8 * i) for i in range(n)]
    noff = 12 + 8 * n
    offs = struct.unpack_from('<%dI' % n, d, noff)
    block = d[noff + 4 * n: 12 + hashoff]
    data0 = 12 + hashoff + 8 * n
    for i, o in enumerate(offs):
        nm = block[o:block.index(b'\0', o)].decode('latin1').lower()
        if not nm.startswith('textures\\') or not nm.endswith('.dds'): continue
        rel = nm[len('textures\\'):]
        stem = rel[:-4]
        if rel not in metFiles or rel in done: continue
        if stem.endswith(('_n', '_nh', '_nm', '_normal')): continue
        size, off = recs[i]
        try:
            v = Image.open(io.BytesIO(d[data0 + off: data0 + off + size])).convert('RGB')
            w, h = v.size
            if min(w, h) < 16 or max(w, h) > 1024: skipped += 1; continue
            m = Image.open(metFiles[rel]).convert('RGB')
            if m.size[0] * h != m.size[1] * w or m.size[0] < 2 * w: skipped += 1; continue
            m = m.resize((2 * w, 2 * h), Image.LANCZOS)
        except Exception as e:
            skipped += 1; continue
        name = rel.replace('\\', '__')[:-4]
        v.save(f'{out}/lr/{name}.png'); m.save(f'{out}/hr/{name}.png')
        done.add(rel)
print(len(done), 'pairs,', skipped, 'skipped')
