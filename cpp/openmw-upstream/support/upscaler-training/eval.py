# eval.py <data> <model.pt>...: holdout SSIMULACRA2 against the HD textures: bilinear, EASU, each model
import sys, os, subprocess, numpy as np, torch, torch.nn.functional as F
from PIL import Image
from model import Compact
data, models = sys.argv[1], sys.argv[2:]
hold = open(data + '/holdout.txt').read().splitlines()
nets = []
for m in models:
    ck = torch.load(m, map_location='cuda'); n = Compact(ck['feat'], ck['convs']).cuda(); n.load_state_dict(ck['state']); n.eval(); nets.append(n)
os.makedirs('tmp', exist_ok=True)
def raw(a, p): Image.fromarray(a).convert('RGBA').save(p + '.png'); open(p, 'wb').write(np.asarray(Image.fromarray(a).convert('RGBA')).tobytes())
tot = np.zeros(2 + len(nets)); cnt = 0
for name in hold:
    lr = np.asarray(Image.open(f'{data}/lr/{name}.png').convert('RGB')); hr = np.asarray(Image.open(f'{data}/hr/{name}.png').convert('RGB'))
    h, w = lr.shape[:2]
    if min(h, w) < 8: continue
    raw(hr, 'tmp/ref.raw')
    x = torch.from_numpy(lr.astype(np.float32) / 255).permute(2, 0, 1)[None].cuda()
    outs = []
    bil = F.interpolate(x, scale_factor=2, mode='bilinear', align_corners=False)
    outs.append(bil)
    open('tmp/lr.raw', 'wb').write(np.asarray(Image.fromarray(lr).convert('RGBA')).tobytes())
    subprocess.run(['./easu', 'tmp/lr.raw', str(w), str(h), 'tmp/easu.raw', '0.25'], check=True)
    with torch.no_grad():
        for n in nets: outs.append(n(x))
    paths = []
    for i, o in enumerate(outs):
        a = (o[0].clamp(0, 1).permute(1, 2, 0).cpu().numpy() * 255 + 0.5).astype(np.uint8)
        p = f'tmp/o{i}.raw'; raw(a, p); paths.append(p)
    paths.insert(1, 'tmp/easu.raw')
    s = [float(v) for v in subprocess.run(['./score', str(2 * w), str(2 * h), 'tmp/ref.raw'] + paths, capture_output=True, text=True).stdout.split()]
    tot += s; cnt += 1
    print(f'{name:40s} ' + ' '.join(f'{v:7.1f}' for v in s), flush=True)
print('mean', ' '.join(f'{v:7.2f}' for v in tot / cnt), 'over', cnt, '(bilinear, easu, models...)')
