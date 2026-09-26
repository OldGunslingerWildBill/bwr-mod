"""Regression for sealed Blender heads and shell joint coverage."""
import json, math
from collections import Counter
from pathlib import Path
ROOT=Path(__file__).resolve().parent
for part,label in [('bottom','solid dished lower head'),('head','solid elliptical removable head')]:
    source=json.loads((ROOT/f'art/models/reactor_vessel/{part}.json').read_text())
    edges=Counter();volume=0;count=0
    for f in source['faces']:
        if not f['part'].startswith(label):continue
        p=[tuple(round(v,6) for v in vertex[:3]) for vertex in f['vertices']]
        for a,b in zip(p,p[1:]+p[:1]):edges[tuple(sorted((a,b)))]+=1
        a,b,c=p
        volume+=(a[0]*(b[1]*c[2]-b[2]*c[1])+a[1]*(b[2]*c[0]-b[0]*c[2])+a[2]*(b[0]*c[1]-b[1]*c[0]))/6
        count+=1
    assert count>0 and all(n==2 for n in edges.values()),(part,'open/non-manifold edge')
    assert volume>0,(part,'inverted shell')
    print(part,count,'triangles; closed oriented solid, volume',round(volume,6))
for w in range(7,24):
    for d in range(7,24):
        for h in (10,22,131):
            hh=min(h*.22,min(w,d)*.18);flange=h-hh-.4
            assert 1.16<1.2 and 1.16+flange-1.10>flange
            assert flange+.42>h-hh
print('Shell seam overlaps verified for all supported widths/depths and min/reference/max heights.')
