# train.py <data dir> <out .pt> <feat> <convs> <steps>
import sys, os, random, glob, time, math
import numpy as np, torch, torch.nn.functional as F
from PIL import Image
from model import Compact
data, outp, feat, convs, steps = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
names = sorted(os.path.basename(p)[:-4] for p in glob.glob(data + '/lr/*.png'))
hold = set(open(data + '/holdout.txt').read().splitlines())
train = [n for n in names if n not in hold]
def load(n):
    lr = np.asarray(Image.open(f'{data}/lr/{n}.png').convert('RGB'))
    hr = np.asarray(Image.open(f'{data}/hr/{n}.png').convert('RGB'))
    return lr, hr
pairs = [load(n) for n in train]
print(len(pairs), 'training textures', flush=True)
dev = 'cuda'
net = Compact(feat, convs).to(dev)
opt = torch.optim.Adam(net.parameters(), lr=5e-4)
sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, steps, 1e-6)
P, B = 48, 32
def batch():
    xs, ys = [], []
    while len(xs) < B:
        lr, hr = random.choice(pairs)
        h, w = lr.shape[:2]
        if h < P or w < P:
            # small textures: tile them (they repeat)
            reps = (math.ceil(P / h), math.ceil(P / w))
            lr = np.tile(lr, (reps[0], reps[1], 1)); hr = np.tile(hr, (reps[0], reps[1], 1)); h, w = lr.shape[:2]
        y, x = random.randrange(h - P + 1), random.randrange(w - P + 1)
        a, b = lr[y:y + P, x:x + P], hr[2 * y:2 * y + 2 * P, 2 * x:2 * x + 2 * P]
        k = random.randrange(8)
        if k & 1: a, b = a[:, ::-1], b[:, ::-1]
        if k & 2: a, b = a[::-1], b[::-1]
        if k & 4: a, b = a.transpose(1, 0, 2), b.transpose(1, 0, 2)
        xs.append(np.ascontiguousarray(a)); ys.append(np.ascontiguousarray(b))
    t = lambda v: torch.from_numpy(np.stack(v)).permute(0, 3, 1, 2).to(dev).float() / 255
    return t(xs), t(ys)
t0 = time.time()
for step in range(1, steps + 1):
    x, y = batch()
    loss = F.l1_loss(net(x), y)
    opt.zero_grad(); loss.backward(); opt.step(); sched.step()
    if step % 2000 == 0:
        print(f'step {step} loss {loss.item():.4f} {time.time() - t0:.0f}s', flush=True)
        torch.save({'feat': feat, 'convs': convs, 'state': net.state_dict()}, outp)
torch.save({'feat': feat, 'convs': convs, 'state': net.state_dict()}, outp)
