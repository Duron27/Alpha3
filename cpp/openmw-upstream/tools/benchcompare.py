#!/usr/bin/env python3
"""Reads the benchmark's files (F2 menu, Testing page, "benchmark"; written to Alpha3/benchmarks/ on the phone) and
says where the frame's time went: one file on its own, or two side by side (the older, slower or "good" one first).

    adb pull /storage/emulated/0/Alpha3/benchmarks/
    python3 benchcompare.py good.json bad.json

For each part of the scene the benchmark took away, its cost is the baseline less the frame without it. Between two
runs, the part whose cost grew is where to look. Before trusting that, the checks at the top must pass: the same view,
weather and settings, and a phone that was not hotter or clocked lower in one of them.
"""
import json
import math
import sys


def load(path):
    with open(path) as f:
        return json.load(f)


def phases(run):
    return {p['name']: p for p in run['phases']}


def nan(v):
    return v is None or (isinstance(v, float) and math.isnan(v))


def fmt(v, digits=2, width=8):
    return ('-' if nan(v) else f'{v:.{digits}f}').rjust(width)


def scene_camera(phase):
    cams = phase.get('cameras', {})
    return cams.get('SceneCam') or (next(iter(cams.values())) if cams else {})


def timing(phase, key):
    """ms of a 'time taken' attribute (OSG keeps seconds)"""
    if phase is None or 'skipped' in phase:
        return float('nan')
    if key in ('mean', 'p50', 'p90', 'p99'):
        return phase['ms'][key]
    for source in (scene_camera(phase), phase.get('viewer', {})):
        if key in source:
            return source[key] * 1000.0
    return float('nan')


# what each row of the table is: (label, key in the phase)
ROWS = [
    ('frame ms', 'mean'),
    ('frame p90', 'p90'),
    ('frame p99', 'p99'),
    ('GPU ms', 'GPU draw time taken'),
    ('cull ms', 'Cull traversal time taken'),
    ('draw ms', 'Draw traversal time taken'),
    ('update ms', 'Update traversal time taken'),
    ('event ms', 'Event traversal time taken'),
    # OpenMW's own CPU work (benchmarks from before these were collected show nan)
    ('Lua ms', 'lua_time_taken'),
    ('Lua sync ms', 'luasyncupdate_time_taken'),
    ('mechanics ms', 'mechanics_time_taken'),
    ('physics ms', 'physics_time_taken'),
    ('mwscript ms', 'script_time_taken'),
]


def bound(phase):
    """what limits the frame: the GPU, the CPU's cull and draw, or neither (held by the swap's pacing)"""
    frame, gpu = timing(phase, 'mean'), timing(phase, 'GPU draw time taken')
    cpu = sum(v for v in (timing(phase, 'Cull traversal time taken'), timing(phase, 'Draw traversal time taken'),
        timing(phase, 'Update traversal time taken'), timing(phase, 'Event traversal time taken')) if not nan(v))
    if nan(frame):
        return '?'
    if not nan(gpu) and gpu > 0.85 * frame:
        return 'GPU-bound'
    if cpu > 0.85 * frame:
        return 'CPU-bound'
    return 'paced/mixed'


def header(run, label):
    b, d, g, v = run.get('build', {}), run.get('device', {}), run.get('gl', {}), run.get('view', {})
    print(f'{label}: {run.get("date")}  build {b.get("commit")} ({b.get("built")})')
    print(f'    {d.get("manufacturer")} {d.get("model")} ({d.get("soc")}), Android {d.get("android")}')
    print(f'    {g.get("renderer")} | {g.get("version")} | timer queries {g.get("timer queries")}')
    print(f'    {v.get("width")}x{v.get("height")}, rain {v.get("rain")}, weather {v.get("forced weather")}, '
          f'thermal pressure at start {v.get("thermal pressure")}')


def summary(run):
    ps = run['phases']
    base = ps[0]
    print(f'\n{"phase":26}{"fps":>8}{"ms":>8}{"GPU":>8}{"cull":>8}{"draw":>8}{"cost ms":>9}  limit   temp/clock')
    for p in ps:
        if 'skipped' in p:
            print(f'{p["name"]:26}  skipped: {p["skipped"]}')
            continue
        cost = timing(base, 'mean') - timing(p, 'mean') if p is not base and 'again' not in p['name'] else float('nan')
        m = p.get('machine', {})
        print(f'{p["name"]:26}{fmt(p["fps"], 1)}{fmt(timing(p, "mean"))}{fmt(timing(p, "GPU draw time taken"))}'
              f'{fmt(timing(p, "Cull traversal time taken"))}{fmt(timing(p, "Draw traversal time taken"))}'
              f'{fmt(cost, 2, 9)}  {bound(p):11} {fmt(m.get("temp hottest c"), 0, 3)}C gpu {fmt(m.get("gpu mhz"), 0, 4)}MHz'
              f' {fmt(m.get("gpu busy %"), 0, 3)}%')


def checks(a, b):
    """what differs between the runs that is not the code"""
    print('\n== Is it a fair comparison? ==')
    ok = True
    va, vb = a.get('view', {}), b.get('view', {})
    if va.get('eye') and vb.get('eye'):
        dist = math.dist(va['eye'], vb['eye'])
        dot = sum(x * y for x, y in zip(va['look'], vb['look']))
        angle = math.degrees(math.acos(max(-1.0, min(1.0, dot))))
        flag = dist > 64 or angle > 3
        ok &= not flag
        print(f'{"!!" if flag else "ok"} view: {dist:.0f} units apart, looking {angle:.1f} degrees apart')
    for key in ('width', 'height', 'rain', 'forced weather', 'shadows', 'grass', 'occlusion culling', 'threading', 'cull mask'):
        if va.get(key) != vb.get(key):
            ok = False
            print(f'!! view {key}: {va.get(key)} -> {vb.get(key)}')
    for section in ('device', 'gl', 'environment'):
        if a.get(section) != b.get(section):
            ok = False
            print(f'!! {section}: {a.get(section)} -> {b.get(section)}')
    sa, sb = a.get('settings', {}), b.get('settings', {})
    changed = [k for k in sorted(set(sa) | set(sb)) if sa.get(k) != sb.get(k)]
    if changed:
        print(f'.. settings that differ ({len(changed)}; a setting new in a build is expected):')
        for k in changed:
            print(f'     {k}: {sa.get(k, "(none)")!r} -> {sb.get(k, "(none)")!r}')
    if a.get('menu') != b.get('menu'):
        print('.. the F2 menu values differ (shaders.yaml): diff the "menu" fields of the two files')
    pa, pb = a['phases'][0], b['phases'][0]
    ma, mb = pa.get('machine', {}), pb.get('machine', {})
    for key, limit in (('temp hottest c', 5), ('temp battery c', 3), ('gpu mhz', 0.15)):
        x, y = ma.get(key), mb.get(key)
        if nan(x) or nan(y):
            continue
        flag = abs(y - x) > (limit * x if limit < 1 else limit)
        ok &= not flag
        print(f'{"!!" if flag else "ok"} baseline {key}: {x:.0f} -> {y:.0f}')
    if pa.get('thermal pressure') != pb.get('thermal pressure'):
        ok = False
        print(f'!! thermal pressure (view distance backs off when hot): {pa.get("thermal pressure")} -> '
              f'{pb.get("thermal pressure")}')
    for run, name in ((a, 'first'), (b, 'second')):
        ps = run['phases']
        first, last = ps[0], ps[-1]
        if 'skipped' not in last and not nan(timing(first, 'mean')):
            drift = timing(last, 'mean') - timing(first, 'mean')
            if abs(drift) > 0.1 * timing(first, 'mean'):
                ok = False
                print(f'!! {name} run drifted {drift:+.2f} ms between its two baselines (heating up, or something moved)')
    print('ok: nothing besides the code stands out' if ok else
          '(!! above: those can explain a difference on their own)')


def compare(a, b):
    header(a, 'A')
    header(b, 'B')
    checks(a, b)
    pa, pb = phases(a), phases(b)
    print('\n== Baseline ==')
    print(f'{"":14}{"A":>12}{"B":>12}{"B-A":>12}')
    for label, key in ROWS:
        x, y = timing(pa['baseline'], key), timing(pb['baseline'], key)
        print(f'{label:14}{fmt(x, 2, 12)}{fmt(y, 2, 12)}{fmt(y - x, 2, 12)}')
    print(f'{"limited by":14}{bound(pa["baseline"]):>12}{bound(pb["baseline"]):>12}')

    print('\n== What each part costs (ms of frame / of GPU), and how that changed ==')
    print(f'{"part":26}{"A frame":>9}{"B frame":>9}{"change":>9}{"A GPU":>9}{"B GPU":>9}{"change":>9}')
    rows = []
    for name in pa:
        if name in ('baseline', 'baseline again') or name not in pb:
            continue
        if 'skipped' in pa[name] or 'skipped' in pb[name]:
            print(f'{name:26}  skipped: {pa[name].get("skipped") or pb[name].get("skipped")}')
            continue
        cost = lambda run, key: timing(run['baseline'], key) - timing(run[name], key)
        fa, fb = cost(pa, 'mean'), cost(pb, 'mean')
        ga, gb = cost(pa, 'GPU draw time taken'), cost(pb, 'GPU draw time taken')
        rows.append((name, fa, fb, ga, gb))
    for name, fa, fb, ga, gb in sorted(rows, key=lambda r: (r[0] == 'no scene', -max(r[2] - r[1] if not nan(r[2] - r[1]) else 0,
                                                                r[4] - r[3] if not nan(r[4] - r[3]) else 0))):
        print(f'{name:26}{fmt(fa, 2, 9)}{fmt(fb, 2, 9)}{fmt(fb - fa, 2, 9)}{fmt(ga, 2, 9)}{fmt(gb, 2, 9)}{fmt(gb - ga, 2, 9)}')
    print('(the top rows grew the most: look there first. "no scene" is everything 3D at once; what it leaves is\n'
          ' post-processing, the GUI and the swap. Frame times held at the display rate hide savings: read GPU then.)')

    print('\n== Stats that changed most in the baseline ==')
    changes = []
    for source in ('viewer', 'cameras'):
        xa, xb = pa['baseline'].get(source, {}), pb['baseline'].get(source, {})
        flat = lambda d: ({f'{c}/{k}': v for c, m in d.items() for k, v in m.items()} if source == 'cameras' else d)
        fa, fb = flat(xa), flat(xb)
        for k in set(fa) | set(fb):
            x, y = fa.get(k), fb.get(k)
            if nan(x) or nan(y):
                changes.append((float('inf'), k, x, y))
                continue
            rel = abs(y - x) / max(abs(x), abs(y), 1e-9)
            if rel > 0.05:
                changes.append((rel, k, x, y))
    for rel, k, x, y in sorted(changes, reverse=True)[:30]:
        print(f'  {k:60} {x if x is not None else "-":>12} -> {y if y is not None else "-":>12}')


def main():
    if len(sys.argv) == 2:
        run = load(sys.argv[1])
        header(run, 'Run')
        summary(run)
    elif len(sys.argv) == 3:
        a, b = load(sys.argv[1]), load(sys.argv[2])
        compare(a, b)
        for run, label in ((a, 'A'), (b, 'B')):
            print(f'\n== {label} ==')
            summary(run)
    else:
        sys.exit(__doc__)


if __name__ == '__main__':
    main()
