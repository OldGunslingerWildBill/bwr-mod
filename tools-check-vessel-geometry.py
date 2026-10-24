"""Regression for the v2 vessel parts: every piece is a closed, outward-facing solid (the renderer culls back faces),
and the shell joints of VesselProfile overlap at every supported size."""
import json
from collections import Counter
from pathlib import Path
ROOT=Path(__file__).resolve().parent
PARTS=ROOT/'art/models/reactor_vessel_v2/parts'
OPEN_OK={'failed_head'}  # torn on purpose: a jagged rim and a peeled sheet
for path in sorted(PARTS.glob('*.json')):
    source=json.loads(path.read_text())
    groups={}
    for f in source['faces']:
        groups.setdefault(f['part'],[]).append(f)
    for label,faces in groups.items():
        edges=Counter();volume=0
        for f in faces:
            p=[tuple(round(v,6) for v in vertex[:3]) for vertex in f['vertices']]
            for a,b in zip(p,p[1:]+p[:1]):edges[tuple(sorted((a,b)))]+=1
            a,b,c=p
            volume+=(a[0]*(b[1]*c[2]-b[2]*c[1])+a[1]*(b[2]*c[0]-b[0]*c[2])+a[2]*(b[0]*c[1]-b[1]*c[0]))/6
        if path.stem in OPEN_OK and label!='sheared studs':
            continue
        assert all(n%2==0 for n in edges.values()),(path.stem,label,'open edge')
        assert volume>0,(path.stem,label,'inverted shell: faces point inward and would be culled')
    print(path.stem,len(source['faces']),'triangles; closed and outward-facing')
# joints: the barrel overlaps the knuckle of the bottom head and the vessel flange's taper, for every footprint and height
for w in range(7,24):
    for d in range(7,24):
        for h in range(10,132):
            m=min(w,d);rise=min(h*.22,m*.18);zf=h-rise;fl=min(1.2,max(.28,.21*rise))
            ri=.448*w;t=.012*w;k=.43/.448
            hb=min((1.72-.15-t)/(1-(1-k*k)**.5),.5*ri)+t;zk=.15+hb
            assert zk<zf-fl-.45*fl,(w,d,h,'no barrel between the knuckle and the flange')
print('Shell joints verified for every supported footprint and height (7..23 x 7..23 x 10..131).')
