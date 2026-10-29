"""Expands every compatibility shader of an OpenMW tree in a few define configurations into <out>/<config>/..."""
import os, re, sys
sys.path.insert(0, os.path.dirname(__file__))
from expand import expand

root, out = sys.argv[1], sys.argv[2]
NUMERIC = {'alphaFunc': '0', 'maxLights': '8', 'maxLightsPerCluster': '16', 'workGroupSize': '16', 'rippleMapSize': '512.0',
           'rippleMapWorldScale': '2.0', 'rainRippleDetail': '1', 'groundcoverStompMode': '2', 'groundcoverFadeStart': '3000.0',
           'groundcoverFadeEnd': '6000.0', 'groundcoverStompIntensity': '1', 'hdrExposureTime': '0.5', 'numViews': '1',
           'shadowNormalOffset': '1.0', 'distorionRTRatio': '1.0', 'shadow_texture_unit_list': '6,7,8',
           'lightingMethodClustered': '0', 'useOVR_multiview': '0', 'useGPUShader4': '0',
           'disableNormals': '0', 'clipPlaneList': '0,1,2,3,4,5'}
def defines_for(names, on):
    d = {}
    for n in sorted(names):
        if n in NUMERIC:
            d[n] = NUMERIC[n]
        elif n.endswith('UV'):
            d[n] = str(sum(1 for k in d if k.endswith('UV')) % 8)
        elif n in ('glossMap', 'bumpMap', 'envMap', 'specularMap', 'glossMapUV', 'decalMap', 'darkMap', 'detailMap') and on:
            d[n] = '0'
        else:
            d[n] = '1' if on else '0'
    if on:
        d['alphaFunc'] = '4'
    d['useClipDistanceFallback'] = '0' if on else '1'
    d['numViews'] = '1'; d['useOVR_multiview'] = '0'; d['useGPUShader4'] = '0'; d['lightingMethodClustered'] = '0'
    return d

def names_of(path, seen):
    names = set()
    text = open(os.path.join(root, path)).read()
    for m in re.finditer(r'#include\s*"([^"]+)"', text):
        inc = m.group(1)
        if not inc.startswith(('lib/', 'core/', 'compatibility/')):
            inc = os.path.join(os.path.dirname(path), inc)
        if inc not in seen and os.path.exists(os.path.join(root, inc)):
            seen.add(inc); names |= names_of(inc, seen)
    for m in re.finditer(r'(?:@)([A-Za-z_][A-Za-z0-9_]*)', text):
        names.add(m.group(1))
    for m in re.finditer(r'\$link\s+"([^"]+)"', text) if False else []:
        pass
    for m in re.finditer(r'@link\s+"([^"]+)"', text):
        l = m.group(1)
        if l not in seen:
            seen.add(l); names |= names_of(l, seen)
    names -= {'foreach', 'endforeach', 'link'}
    return names

skip = ('luminance', 'multiview_resolve', 'ripples')
for dirpath, _, files in os.walk(os.path.join(root, 'compatibility')):
    for f in files:
        if not f.endswith(('.vert', '.frag')) or any(s in f for s in skip):
            continue
        rel = os.path.relpath(os.path.join(dirpath, f), os.path.join(root, 'compatibility'))
        names = names_of('compatibility/' + rel, set())
        for cfg, on in (('min', False), ('max', True)):
            try:
                src, links = expand(root, rel, defines_for(names, on))
            except SystemExit as e:
                print('SKIP', rel, cfg, e); continue
            base = os.path.join(out, cfg, rel.replace('/', '_'))
            os.makedirs(os.path.dirname(base), exist_ok=True)
            open(base, 'w').write(src)
            ext = os.path.splitext(rel)[1]
            for n, l in enumerate(links):
                ls, _ = expand(root, l, defines_for(names, on))
                open('%s.link%d%s' % (base, n, ext), 'w').write(ls)
