"""Copy a world, keeping only the full chunks that hold a bwr block or block entity (everything else is terrain that the
old-save check ignores). Usage: trim.py <world> <out>"""
import io, os, shutil, struct, sys, zlib, gzip

def read_nbt(buf):
    pos = 0
    def u8():
        nonlocal pos; v = buf[pos]; pos += 1; return v
    def rd(fmt, n):
        nonlocal pos; v = struct.unpack_from(fmt, buf, pos)[0]; pos += n; return v
    def string():
        nonlocal pos; n = rd('>H', 2); s = buf[pos:pos+n].decode('utf-8', 'replace'); pos += n; return s
    def payload(t):
        nonlocal pos
        if t == 1: return rd('>b', 1)
        if t == 2: return rd('>h', 2)
        if t == 3: return rd('>i', 4)
        if t == 4: return rd('>q', 8)
        if t == 5: return rd('>f', 4)
        if t == 6: return rd('>d', 8)
        if t == 7:
            n = rd('>i', 4); pos += n; return None
        if t == 8: return string()
        if t == 9:
            it = u8(); n = rd('>i', 4); return [payload(it) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                it = u8()
                if it == 0: return d
                k = string(); d[k] = payload(it)
        if t == 11:
            n = rd('>i', 4); pos += 4*n; return None
        if t == 12:
            n = rd('>i', 4); pos += 8*n; return None
        raise ValueError(t)
    t = u8(); string()
    return payload(t)

def keep(chunk):
    if chunk.get('Status') != 'minecraft:full': return False
    for s in chunk.get('sections', []):
        for p in s.get('block_states', {}).get('palette', []):
            if p.get('Name', '').startswith('bwr:'): return True
    for be in chunk.get('block_entities', []) or []:
        if be.get('id', '').startswith('bwr:'): return True
    return False

def trim_region(src, dst):
    data = open(src, 'rb').read()
    if len(data) < 8192: return 0
    out = {}
    for i in range(1024):
        e = struct.unpack_from('>I', data, i*4)[0]
        if e == 0: continue
        off = (e >> 8) * 4096
        length = struct.unpack_from('>I', data, off)[0]
        comp = data[off+4]
        raw = data[off+5:off+4+length]
        if comp == 2: nbt = zlib.decompress(raw)
        elif comp == 1: nbt = gzip.decompress(raw)
        elif comp == 3: nbt = raw
        else: continue
        try:
            chunk = read_nbt(nbt)
        except Exception:
            continue
        if keep(chunk):
            out[i] = (data[off:off+4+length], struct.unpack_from('>I', data, 4096 + i*4)[0])
    if not out: return 0
    header = bytearray(8192); body = bytearray(); sector = 2
    for i, (blob, ts) in sorted(out.items()):
        pad = (-len(blob)) % 4096
        n = (len(blob) + pad) // 4096
        struct.pack_into('>I', header, i*4, (sector << 8) | n)
        struct.pack_into('>I', header, 4096 + i*4, ts)
        body += blob + b'\0' * pad
        sector += n
    open(dst, 'wb').write(bytes(header) + bytes(body))
    return len(out)

src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)
for name in os.listdir(src):
    p = os.path.join(src, name)
    if name in ('region', 'entities', 'poi', 'session.lock', 'computercraft', 'playerdata', 'stats', 'advancements'):
        continue
    if name == 'data':
        os.makedirs(os.path.join(dst, 'data'), exist_ok=True)
        for f in os.listdir(p):
            if f.startswith('bwr_') or f == 'neoforge_data_attachments.dat': shutil.copy(os.path.join(p, f), os.path.join(dst, 'data', f))
        continue
    if os.path.isdir(p): continue
    shutil.copy(p, os.path.join(dst, name))
total = 0
for dimdir, rel in (('region', 'region'),):
    rdir = os.path.join(src, dimdir)
    os.makedirs(os.path.join(dst, rel), exist_ok=True)
    for f in sorted(os.listdir(rdir)):
        if f.endswith('.mca'):
            total += trim_region(os.path.join(rdir, f), os.path.join(dst, rel, f))
print('kept chunks', total)
