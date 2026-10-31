"""Host test of the generated maps (support/src/generatedmaps.cpp): runs genmaps on DDS textures and makes a sheet of
diffuse | normals | height | lit by the normals | specular, one row per texture.
  genmaps.py <genmaps binary> <textures dir> <out.png> <name>:<material> ..."""
import os, struct, subprocess, sys, tempfile
import numpy as np
from PIL import Image, ImageDraw

tool, textures, out = sys.argv[1], sys.argv[2], sys.argv[3]
work = tempfile.mkdtemp()

def read_rgba(path):
    data = open(path, 'rb').read()
    w, h = struct.unpack('<II', data[:8])
    return np.frombuffer(data[8:], np.uint8).reshape(h, w, 4)

S = 192
rows = []
for item in sys.argv[4:]:
    name, material = item.split(':')
    im = Image.open(os.path.join(textures, name + '.dds')).convert('RGBA')
    a = np.asarray(im)
    raw = os.path.join(work, name + '.rgba')
    open(raw, 'wb').write(struct.pack('<II', a.shape[1], a.shape[0]) + a.tobytes())
    prefix = os.path.join(work, name)
    subprocess.run([tool, raw, prefix, material], check=True)
    nh = read_rgba(prefix + '_nh.rgba').astype(np.float32) / 255
    spec = read_rgba(prefix + '_spec.rgba')
    n = nh[..., :3] * 2 - 1
    light = np.array([-0.5, -0.5, 0.7]); light /= np.linalg.norm(light)   # from the top left
    shade = np.clip(n @ light, 0, 1)
    lit = np.clip(a[..., :3] / 255 * (0.25 + 0.95 * shade[..., None]), 0, 1)
    flat = np.clip(a[..., :3] / 255 * (0.25 + 0.95 * light[2]), 0, 1)
    tiles = [a[..., :3], (nh[..., :3] * 255).astype(np.uint8), np.repeat((nh[..., 3:] * 255).astype(np.uint8), 3, -1),
             (flat * 255).astype(np.uint8), (lit * 255).astype(np.uint8), spec[..., :3]]
    rows.append((name + ' (' + material + ')', [Image.fromarray(t).resize((S, S), Image.LANCZOS) for t in tiles]))

labels = ['diffuse', 'normals', 'height', 'flat lit', 'lit with normals', 'specular']
sheet = Image.new('RGB', (len(labels) * (S + 8) + 8, len(rows) * (S + 30) + 30), (24, 26, 32))
d = ImageDraw.Draw(sheet)
for i, l in enumerate(labels):
    d.text((8 + i * (S + 8), 8), l, fill=(220, 220, 230))
for r, (title, tiles) in enumerate(rows):
    y = 30 + r * (S + 30)
    d.text((8, y), title, fill=(220, 220, 230))
    for i, t in enumerate(tiles):
        sheet.paste(t, (8 + i * (S + 8), y + 14))
sheet.save(out)
print(out)
