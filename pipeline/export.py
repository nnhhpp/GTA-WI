# /// script
# requires-python = ">=3.9"
# dependencies = ["numpy", "pillow"]
# ///
"""Step 4: the map + the rendered sprites + bits of your GTA III install -> phone resources (work\\res).

  export.py <kit folder> <work folder>

Reads map\\chunk01\\chunk01_col.tmx (+ its tilesets), work\\paths.json, work\\sprites\\ (render_sprites.py),
work\\gta3\\ (extract.py: HUD + font + menu textures, GXT text) and music\\ (your MIDI files).
Map layers are cut into 16 px tiles in three pools:
  u  ground + roads (opaque, holes = grass)        o  bridge, decals, rail        b  building_tile (+ tree patches)
b tiles carry an occlusion base per cell = bottom of the building's column run (no hand-made occlusion map).
Also: collision layer -> /c/col (8 px bits), spawns / weapons / mission -> /c/obj, GTA path graphs -> /c/paths,
own-map radar, sprites (peds, cars, attacks, chopper, fx), HUD, menu art, music, text.
"""
import sys, os, io, json, struct, hashlib, zipfile, re, math, numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tmx import Map, render_layer, render_objects, over, u8
Image.MAX_IMAGE_PIXELS = None
if len(sys.argv) < 3: sys.exit(__doc__)
KIT, WORK = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
OUT = os.path.join(WORK, 'res')
TMX = os.path.join(KIT, 'map', 'chunk01', 'chunk01_col.tmx')
GTA = os.path.join(WORK, 'gta3')
import assets as A
T, PG = 16, 8
OX, OY = 8608, 3808                 # chunk origin in global px (= 3 x the old 4 px/m east-map px)
for d in ('c', 's', 'h', 'p', 'u', 'a'): os.makedirs(os.path.join(OUT, d), exist_ok=True)
stats = {}
def P(*a): return os.path.join(OUT, *a)

M = Map(TMX)
W16, H16 = M.W, M.H; PW, PH = W16 * T, H16 * T
print('map', W16, H16, [o for o in M.order], flush=True)
def lay(n):
    return u8(render_layer(M, n)) if n in M.layers else np.zeros((PH, PW, 4), np.uint8)
U = np.zeros((PH, PW, 4), np.float32); render_layer(M, 'ground', U); render_layer(M, 'roads', U); U = u8(U)
BR, DEC, B, RL = lay('bridge'), lay('decals'), lay('building_tile'), lay('rail')
print('layers rendered', flush=True)

# ------------------------------------------------------------------ helpers
def binary_alpha(a, thr=128):
    a = a.copy(); keep = a[..., 3] >= thr; solid = a[..., 3] == 255; edge = keep & ~solid
    if edge.any() and solid.any():
        rgb = a[..., :3].copy(); have = solid.copy()
        for _ in range(4):
            if not (edge & ~have).any(): break
            for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0)):
                sh = np.roll(have, (dy, dx), (0, 1)); sr = np.roll(rgb, (dy, dx), (0, 1)); take = ~have & sh
                rgb[take] = sr[take]; have |= take
        a[..., :3] = np.where(edge[..., None] & have[..., None], rgb, a[..., :3])
    a[..., 3] = np.where(keep, 255, 0).astype(np.uint8); a[~keep] = 0
    return a
def save8(arr, fn):
    rgba = arr.shape[2] == 4
    flat = arr.reshape(-1, arr.shape[2]).copy()
    if rgba: flat[flat[:, 3] == 0] = 0
    cols, inv = np.unique(flat, axis=0, return_inverse=True)
    if len(cols) <= 256:
        im = Image.fromarray(inv.reshape(arr.shape[:2]).astype(np.uint8), 'P')
        pal = np.zeros((256, 3), np.uint8); pal[:len(cols)] = cols[:, :3]; im.putpalette(pal.ravel().tolist())
        if rgba: im.save(fn, optimize=True, transparency=bytes(cols[:, 3].tolist()))
        else: im.save(fn, optimize=True)
    else:
        im = Image.fromarray(arr, 'RGBA' if rgba else 'RGB')
        im = im.quantize(256, method=Image.Quantize.FASTOCTREE if rgba else Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
        im.save(fn, optimize=True)
    return os.path.getsize(fn), len(cols)
def tr(t, k):
    return {0: t, 5: np.rot90(t, -1), 3: np.rot90(t, 2), 6: np.rot90(t, 1), 2: t[:, ::-1],
            7: np.rot90(t[:, ::-1], -1), 1: np.rot90(t[:, ::-1], 2), 4: np.rot90(t[:, ::-1], 1)}[k]
TRS = [0, 5, 3, 6, 2, 7, 1, 4]
def h(t): return hashlib.md5(np.ascontiguousarray(t).tobytes()).digest()
def qcolours(im, n):
    m = im[..., 3] > 0
    if not m.any() or len(np.unique(im[m][:, :3], axis=0)) <= n: return im
    q = Image.fromarray(im[..., :3]).quantize(n, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).convert('RGB')
    out = im.copy(); out[..., :3] = np.array(q); out[~m] = 0; return out

# ------------------------------------------------------------------ buildings: palette snap + occlusion base
# the 31-colour building palette (the colours of the hand-reduced building art); building pixels are snapped to it
PAL_HEX = ('000000 020201 050504 0b0b0b 161716 21211f 248b70 2d2e2a 2d6685 383936 403f3c 4f514e 51698a 544d3a 555857 57656e 585c5f '
           '64a0b6 6b6659 6b7c7a 73736f 74827b 757d82 75898d 7591a5 86867a 8a765f 949595 9f7c84 b23c2e d2aa57')
pal = np.array([[int(h[i:i + 2], 16) for i in (0, 2, 4)] for h in PAL_HEX.split()], np.int32)
B = binary_alpha(B)
op = B[..., 3] > 0
px = B[op][:, :3].astype(np.int32)
key = lambda c: (c[:, 0] << 16) | (c[:, 1] << 8) | c[:, 2]
off = ~np.isin(key(px), key(pal))
if off.any():
    d = ((px[off][:, None, :] - pal[None, :, :]) ** 2).sum(2); px[off] = pal[d.argmin(1)]
    B[op, :3] = px.astype(np.uint8)
stats['building_px_snapped_to_palette'] = int(off.sum())
BASE = np.zeros((PH, PW), np.int32)
for x in range(PW):
    col = op[:, x]
    if not col.any(): continue
    dd = np.diff(np.concatenate([[0], col.astype(np.int8), [0]])); s = np.nonzero(dd == 1)[0]; e = np.nonzero(dd == -1)[0]
    for a0, b0 in zip(s, e): BASE[a0:b0, x] = b0 - 1
# props: big tree patches go into the building pool, the rest stay sprites
FH = 0x80000000
POLE = {'trafficlight1': 5, 'lamppost1': 4, 'lamppost2': 4, 'Streetlamp1': 4, 'Streetlamp2': 4, 'doublestreetlght1': 4, 'bollard': 3,
        'bollardlight': 3, 'fire_hydrant': 4, 'wastebin': 5, 'phonebooth1': 8, 'parkbench1': 9, 'hghwysgn01': 5, 'hghwysgn02': 5,
        'westpostsign': 4, 'rdsign02bk': 4, 'doc_floodlite': 5}
small = []
for o in M.objs['props'].findall('object'):
    gid = int(o.get('gid')); s_, i_ = M.tsof(gid); pr = s_.tprops.get(i_, {}); model = pr.get('model', '')
    im = M.tile(gid); w, hh = float(o.get('width')), float(o.get('height'))
    if (round(w), round(hh)) != (im.shape[1], im.shape[0]): im = np.array(Image.fromarray(im).resize((int(round(w)), int(round(hh))), Image.NEAREST))
    im = binary_alpha(im)
    X, Y = int(round(float(o.get('x')))), int(round(float(o.get('y')))) - im.shape[0]; foot = int(round(float(o.get('y'))))
    ax = float(pr.get('anchor_x', im.shape[1] / 2)) * im.shape[1] / M.tile(gid).shape[1]
    ay = float(pr.get('anchor_y', im.shape[0])) * im.shape[0] / M.tile(gid).shape[0]
    if gid & FH: ax = im.shape[1] - ax
    if im.shape[0] * im.shape[1] > 200000:
        im = qcolours(im, 32)
        y0, y1, x0, x1 = max(0, Y), min(PH, Y + im.shape[0]), max(0, X), min(PW, X + im.shape[1])
        sub = im[y0 - Y:y1 - Y, x0 - X:x1 - X]; m = sub[..., 3] > 0
        B[y0:y1, x0:x1][m] = sub[m]; BASE[y0:y1, x0:x1][m] = np.maximum(BASE[y0:y1, x0:x1][m], foot)
    else:
        small.append(dict(im=im, X=X, Y=Y, foot=int(round(Y + ay)), fx=int(round(X + ax)), model=model))
op = B[..., 3] > 0
# ground: holes -> grass swatch
gr = np.array(Image.open(os.path.join(KIT, 'map', 'tiles', 'tiles.png')).convert('RGB'))[64:128, 448:512]
grass = np.tile(gr, (PH // 64 + 1, PW // 64 + 1, 1))[:PH, :PW]
a = U[..., 3:4].astype(np.float32) / 255
Uf = (U[..., :3] * a + grass * (1 - a) + .5).astype(np.uint8)
BR, RL = binary_alpha(BR), binary_alpha(RL); DEC = DEC.copy(); DEC[DEC[..., 3] < 8] = 0

# ------------------------------------------------------------------ tile pools
class Pool:
    def __init__(s, name, orient): s.name, s.orient, s.tiles, s.idx = name, orient, [], {}
    def add(s, t):
        if s.orient:
            for k in TRS:
                hv = h(tr(t, k))
                if hv in s.idx:
                    i = s.idx[hv]; c = s.tiles[i]
                    return i, next(X for X in TRS if np.array_equal(tr(c, X), t))
        hv = h(t)
        if hv in s.idx: return s.idx[hv], 0
        s.idx[hv] = len(s.tiles); s.tiles.append(t); return len(s.tiles) - 1, 0
    def save(s):
        n = len(s.tiles); pages = -(-n // (PG * PG)); total = 0
        ch = s.tiles[0].shape[2] if n else 3
        for p in range(pages):
            im = np.zeros((PG * T, PG * T, ch), np.uint8)
            for i in range(PG * PG):
                k = p * PG * PG + i
                if k >= n: break
                y, x = divmod(i, PG); im[y*T:(y+1)*T, x*T:(x+1)*T] = s.tiles[k]
            total += save8(im, P('c', '%s%d.png' % (s.name, p)))[0]
        stats[s.name] = dict(tiles=n, pages=pages, png_bytes=total)
        return pages
def quad_order():
    for qy in range(-(-H16 // 8)):
        for qx in range(-(-W16 // 8)):
            for y in range(qy * 8, min(H16, qy * 8 + 8)):
                for x in range(qx * 8, min(W16, qx * 8 + 8)):
                    yield x, y
pu, po, pb = Pool('u', True), Pool('o', True), Pool('b', False)
umap = np.zeros((H16, W16), np.int32); bmap = np.zeros((H16, W16), np.int32); bbase = np.zeros((H16, W16), np.int16)
omaps = {k: np.zeros((H16, W16), np.int32) for k in ('br', 'dec', 'rl')}
OL = {'br': BR, 'dec': DEC, 'rl': RL}
for x, y in quad_order():
    sl = (slice(y*T, (y+1)*T), slice(x*T, (x+1)*T))
    i, k = pu.add(Uf[sl]); umap[y, x] = i | (k << 13)
    for kk, L in OL.items():
        t = L[sl]
        if t[..., 3].max() == 0: continue
        i, k = po.add(t); omaps[kk][y, x] = (i + 1) | (k << 13)
    t = B[sl]
    if t[..., 3].max() > 0:
        i, _ = pb.add(t); bmap[y, x] = i + 1
        bbase[y, x] = int(BASE[sl][op[sl]].max())
assert len(pu.tiles) < 8192 and len(po.tiles) < 8191 and len(pb.tiles) < 65535
nU, nO, nB = pu.save(), po.save(), pb.save()
print('pools', stats, flush=True)

# ------------------------------------------------------------------ props (sprites); breakable poles get a collider radius
Bs, RLs = B[..., 3] == 255, RL[..., 3] == 255
pimgs, pid, plist, hidden = [], {}, [], 0
for p in small:
    im, X, Y, foot = p['im'], p['X'], p['Y'], p['foot']
    y0, y1, x0, x1 = max(0, Y), min(PH, Y + im.shape[0]), max(0, X), min(PW, X + im.shape[1])
    if y0 < y1 and x0 < x1:
        opq = im[y0-Y:y1-Y, x0-X:x1-X, 3] > 0
        cov = RLs[y0:y1, x0:x1] | (Bs[y0:y1, x0:x1] & (BASE[y0:y1, x0:x1] > foot))
        if not (opq & ~cov).any(): hidden += 1; continue
    im = qcolours(im, 32); k = h(im)
    if k not in pid: pid[k] = len(pimgs); pimgs.append(im); save8(im, P('c', 'p%d.png' % pid[k]))
    r = POLE.get(p['model'], 0)
    # overhead: lamp arms, signal heads, signs, banners hanging south of the base -> drawn again over peds/cars (flag 0x40)
    if Y + im.shape[0] - foot >= 12 and not p['model'].startswith(('veg_bush', 'parkbench', 'bar_barrier')): r |= 0x40
    plist.append((foot, pid[k], X, Y, p['fx'], r))
plist.sort()
stats['props'] = dict(kept=len(plist), hidden=hidden, images=len(pimgs), poles=sum(1 for q in plist if q[5] & 0x3f), overhead=sum(1 for q in plist if q[5] & 0x40))

with open(P('c', 'map'), 'wb') as f:
    f.write(struct.pack('>8h', 0x5733, W16, H16, nU, nO, nB, 0, 0))
    f.write(umap.astype('>u2').tobytes())
    for kk in ('br', 'dec', 'rl'): f.write(omaps[kk].astype('>u2').tobytes())
    f.write(bmap.astype('>u2').tobytes()); f.write(bbase.astype('>i2').tobytes())
    f.write(struct.pack('>hh', len(plist), len(pimgs)))
    for im in pimgs: f.write(struct.pack('>hh', im.shape[1], im.shape[0]))
    for foot, i, X, Y, fx, r in plist: f.write(struct.pack('>hhhhhb', i, X, Y, foot, fx, r))

# ------------------------------------------------------------------ collision (Tiled layer 'collision', tile id = quadrant mask)
cs = [t for t in M.tsets if t.name == 'collision'][0]
v = M.layers['collision']; G = np.zeros((H16 * 2, W16 * 2), bool)
for ty, tx in zip(*np.nonzero(v)):
    g = int(v[ty, tx]); m = (g & 0x0FFFFFFF) - cs.first
    q = np.array([[m & 1, (m >> 1) & 1], [(m >> 2) & 1, (m >> 3) & 1]], bool)
    if g & 0x20000000: q = q.T
    if g & 0x80000000: q = q[:, ::-1]
    if g & 0x40000000: q = q[::-1]
    G[ty*2:ty*2+2, tx*2:tx*2+2] = q
with open(P('c', 'col'), 'wb') as f:
    f.write(b'COL1' + struct.pack('>HHB', G.shape[1], G.shape[0], 8)); f.write(np.packbits(G, axis=1).tobytes())
stats['collision_solid_pct'] = round(float(G.mean() * 100), 1)
solid = lambda x, y: G[min(G.shape[0] - 1, max(0, int(y) // 8)), min(G.shape[1] - 1, max(0, int(x) // 8))]
roads = np.zeros((PH, PW), bool)
if 'roads' in M.layers: roads = M.layers['roads'].repeat(16, 0).repeat(16, 1) != 0
def snap(x, y, prefer_road=True, rmax=160):
    """nearest free 8 px cell (on a road if possible) with a 2-cell clear margin"""
    best = None
    for r in range(0, rmax, 4):
        for dx in range(-r, r + 1, 4):
            for dy in (-r, r) if abs(dx) != r else range(-r, r + 1, 4):
                X, Y = x + dx, y + dy
                if not (16 <= X < PW - 16 and 16 <= Y < PH - 16): continue
                if any(solid(X + ox, Y + oy) for ox in (-12, 0, 12) for oy in (-12, 0, 12)): continue
                if prefer_road and not roads[int(Y), int(X)]: continue
                return X, Y
    return snap(x, y, False, rmax) if prefer_road else (x, y)

# ------------------------------------------------------------------ objects
lines = []
for o in M.objs['spawns'].findall('object'):
    if o.get('type') == 'cop_spawn':
        x, y = float(o.get('x')), float(o.get('y')); sx, sy = snap(x, y)
        hd = {p.get('name'): p.get('value') for p in o.iter('property')}.get('heading', 'E')
        lines.append('spawn %d %d %s %s' % (sx, sy, hd, o.get('name')))
    elif o.get('type') == 'roadblock':
        lines.append('rb %d %d %d %d' % tuple(round(float(o.get(k))) for k in ('x', 'y', 'width', 'height')))
for o in M.objs['weapons'].findall('object'):
    x, y = snap(float(o.get('x')), float(o.get('y')), False, 64); lines.append('weapon %d %d' % (x, y))
for o in M.objs['mission'].findall('object'):
    n, x, y = o.get('name'), float(o.get('x')), float(o.get('y'))
    if n.startswith('fire'): lines.append('fire %d %d %d' % (x, y, 4 if o.get('type') == 'fire' else 10))
    else: lines.append('%s %d %d' % (n, x, y))
open(P('c', 'obj'), 'w', newline='\n').write('\n'.join(lines) + '\n')

# ------------------------------------------------------------------ GTA path graphs -> chunk px (car = 0, ped = 1)
PJ = json.load(open(os.path.join(WORK, 'paths.json')))
with open(P('c', 'paths'), 'wb') as f:
    for gname in ('car', 'ped'):
        grp = PJ['groups'][gname]; keep = {}; nodes = []
        for n in grp['nodes']:
            x, y = n['sx'] * 3 - OX, n['sy'] * 3 - OY
            if 24 <= x < PW - 24 and 24 <= y < PH - 24 and not solid(x, y): keep[n['id']] = len(nodes); nodes.append((x, y))
        edges = []
        for e in grp['edges']:
            if e['a'] in keep and e['b'] in keep:
                (ax, ay), (bx, by) = nodes[keep[e['a']]], nodes[keep[e['b']]]
                n_ = max(2, int(max(abs(bx - ax), abs(by - ay)) / 6))
                if any(solid(ax + (bx - ax) * t / n_, ay + (by - ay) * t / n_) for t in range(n_ + 1)): continue
                edges.append((keep[e['a']], keep[e['b']], e['lanes_l'], e['lanes_r'], 1 if e['no_traffic'] else 0))
        f.write(struct.pack('>h', len(nodes)))
        for x, y in nodes: f.write(struct.pack('>hh', int(round(x)), int(round(y))))
        f.write(struct.pack('>h', len(edges)))
        for e in edges: f.write(struct.pack('>hhbbb', *e))
        stats['paths_' + gname] = (len(nodes), len(edges))

# ------------------------------------------------------------------ sprites (from render_sprites.py, 32 px/m masters)
meta = []
# Claude + the cop tiers: re-cut from the Blender masters (32 px/m) at 16 px/m. Moving = 8-frame run cycle
# (sprites/master_run, rendered from ped.ifp run_player / run_civi); stand + dead from the same renders.
MR, MS, MC = (os.path.join(WORK, 'sprites', d) for d in ('master_run', 'master', 'master_combat'))
RUN_NF = 8
def mfile(name):
    for d in (MR, MS, MC):
        f = os.path.join(d, name)
        if os.path.exists(f): return f
    raise FileNotFoundError(name)
def cut_ped(files, scale=16.0 / 31.875):
    sm = []
    for f in files:
        im = Image.open(f).convert('RGBA'); s_ = im.resize((round(im.size[0] * scale), round(im.size[1] * scale)), Image.LANCZOS)
        s_.putalpha(s_.getchannel('A').point(lambda v: 255 if v > 110 else 0)); sm.append(s_)
    bb = None
    for s_ in sm:
        b = s_.getbbox()
        if b: bb = b if bb is None else (min(bb[0], b[0]), min(bb[1], b[1]), max(bb[2], b[2]), max(bb[3], b[3]))
    cw, ch = bb[2] - bb[0], bb[3] - bb[1]
    strip = Image.new('RGBA', (cw * len(sm), ch), (0, 0, 0, 0))
    for k, s_ in enumerate(sm): strip.paste(s_.crop(bb), (k * cw, 0))
    return np.array(strip), cw, ch, int(round(sm[0].size[0] / 2 - bb[0])), bb
RUNNERS = ['claude', 'cop', 'swat', 'fbi', 'army']
for n in RUNNERS:
    st, sw, sh, sa, sbb = cut_ped([mfile('ped_%s_stand_d%02d.png' % (n, d)) for d in range(8)])
    save8(qcolours(st, 64), P('s', 'p_%s_s.png' % n)); meta.append('peds %s %d %d %d' % (n, sw, sh, sa))
    dd, dw, dh, da, _ = cut_ped([mfile('ped_%s_dead_d%02d.png' % (n, d)) for d in (0, 2, 4, 6)])
    save8(qcolours(dd, 64), P('s', 'p_%s_d.png' % n)); meta.append('pedd %s %d %d %d' % (n, dw, dh, da))
    rn, rw, rh, ra, rbb = cut_ped([mfile('ped_%s_run_d%02d_f%d.png' % (n, d, j)) for d in range(8) for j in range(RUN_NF)])
    save8(qcolours(rn, 64), P('s', 'p_%s_w.png' % n))
    meta.append('pedw %s %d %d %d %d %d' % (n, rw, rh, ra, sbb[3] - 1 - rbb[1], RUN_NF))   # + ground row in the cell, frames per dir
    stats['run_' + n] = (rw, rh)
# everyone else (8-Ball, Misty, the civilians): stand / dead / 4-frame walk cycle
for n in [p for p in A.PEDS_WALK if p not in RUNNERS]:
    st, sw, sh, sa, sbb = cut_ped([mfile('ped_%s_stand_d%02d.png' % (n, d)) for d in range(8)])
    save8(qcolours(st, 64), P('s', 'p_%s_s.png' % n)); meta.append('peds %s %d %d %d' % (n, sw, sh, sa))
    dd, dw, dh, da, _ = cut_ped([mfile('ped_%s_dead_d%02d.png' % (n, d)) for d in (0, 2, 4, 6)])
    save8(qcolours(dd, 64), P('s', 'p_%s_d.png' % n)); meta.append('pedd %s %d %d %d' % (n, dw, dh, da))
    rn, rw, rh, ra, rbb = cut_ped([mfile('ped_%s_walk_d%02d_f%d.png' % (n, d, j)) for d in range(8) for j in range(4)])
    save8(qcolours(rn, 64), P('s', 'p_%s_w.png' % n)); meta.append('pedw %s %d %d %d %d 4' % (n, rw, rh, ra, sbb[3] - 1 - rbb[1]))
# fire + smoke (procedural, 8 frames)
frng = np.random.default_rng(3)
FW, FH, NF = 26, 40, 8
fire = np.zeros((FH, FW * NF, 4), np.uint8)
for k in range(NF):
    fr = np.zeros((FH, FW, 4), np.float32); yy, xx = np.mgrid[0:FH, 0:FW].astype(np.float32)
    for b_ in range(9):
        cx_ = FW / 2 + frng.normal(0, 3.2); cy_ = FH - 8 - frng.uniform(0, 20)
        r_ = frng.uniform(3.5, 7.5) * (1 - (FH - 8 - cy_) / 34)
        d = np.sqrt(((xx - cx_) / max(r_, 1.5)) ** 2 + ((yy - cy_) / max(r_ * 1.7, 2)) ** 2)
        fr[..., 3] = np.maximum(fr[..., 3], np.clip(1 - d, 0, 1))
    heat = np.clip(fr[..., 3] * 1.6 * (0.6 + 0.4 * yy / FH), 0, 1)
    fr[..., 0] = 1.0; fr[..., 1] = np.clip(heat * 0.95, 0.25, 1); fr[..., 2] = np.clip(heat - 0.6, 0, 1) * 1.5
    glow = np.clip(1 - np.sqrt(((xx - FW / 2) / (FW / 2)) ** 2 + ((yy - (FH - 10)) / 12) ** 2), 0, 1) * 0.35
    fr[..., 3] = np.clip(np.where(fr[..., 3] > 0.25, 1, glow), 0, 1)
    fire[:, k * FW:(k + 1) * FW] = (np.clip(fr, 0, 1) * 255).astype(np.uint8)
Image.fromarray(fire, 'RGBA').quantize(48, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE).save(P('s', 'fire.png'), optimize=True)
SW, SH = 28, 30
smoke = np.zeros((SH, SW * NF, 4), np.uint8)
for k in range(NF):
    fr = np.zeros((SH, SW, 4), np.float32); yy, xx = np.mgrid[0:SH, 0:SW].astype(np.float32)
    for b_ in range(6):
        cx_ = SW / 2 + frng.normal(0, 4); cy_ = SH - 6 - ((k * 3 + b_ * 5) % 22); r_ = 4 + (SH - cy_) / 4
        d = np.sqrt(((xx - cx_) / r_) ** 2 + ((yy - cy_) / r_) ** 2)
        fr[..., 3] = np.maximum(fr[..., 3], np.clip(1 - d, 0, 1) * 0.55)
    fr[..., :3] = 0.16
    smoke[:, k * SW:(k + 1) * SW] = (fr * 255).astype(np.uint8)
Image.fromarray(smoke, 'RGBA').quantize(16, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE).save(P('s', 'smoke.png'), optimize=True)
open(P('s', 'fx'), 'w', newline='\n').write('fire %d %d %d\nsmoke %d %d %d\n' % (FW, FH, NF, SW, SH, NF))
meta = [l for l in meta if l.split()[0] != 'atk']
for an, nf in (('punch', 4), ('gun', 3)):
    at, aw, ah, aa, _ = cut_ped([os.path.join(MC, 'ped_claude_%s_d%02d_f%d.png' % (an, d, j)) for d in range(8) for j in range(nf)])
    save8(qcolours(at, 64), P('s', 'a_%s.png' % an)); meta.append('atk %s %d %d %d %d' % (an, aw, ah, aa, nf))
# HUD (models\\hud.txd, fonts.txd): radar ring, north, player arrow; health digits + star in pricedown
import rwtxd
from PIL import ImageFilter
hud = rwtxd.read_txd(open(os.path.join(GTA, 'models', 'hud.txd'), 'rb').read())
font1 = rwtxd.read_txd(open(os.path.join(GTA, 'models', 'fonts.txd'), 'rb').read())['font1']
def hsave(img, name): Image.fromarray(np.asarray(img)).quantize(64, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE).save(P('h', name), optimize=True)
def hscaled(name, size): return Image.fromarray(hud[name]).resize((size, size), Image.LANCZOS)
hsave(hscaled('radardisc', 66), 'ring.png'); hsave(hscaled('radar_north', 11), 'north.png')
ctr = Image.fromarray(hud['radar_centre']).resize((12, 12), Image.LANCZOS); arw = Image.new('RGBA', (12 * 16, 12))
for k in range(16): arw.alpha_composite(ctr.rotate(-k * 22.5, resample=Image.BICUBIC), (k * 12, 0))
hsave(arw, 'arrow.png')
def pglyph(r_, c_):
    g = Image.fromarray(font1).crop((c_ * 32, r_ * 40, c_ * 32 + 32, r_ * 40 + 40))
    xs = np.nonzero((np.asarray(g)[..., 3] > 90).any(0))[0]
    return g.crop((0, 0, 8, 40)) if len(xs) == 0 else g.crop((int(xs[0]), 0, int(xs[-1]) + 1, 40))
def pstrip(cells, color, h):
    gs = []
    for r_, c_ in cells:
        g = pglyph(r_, c_); g = g.resize((max(1, round(g.width * h / 40.0)), h), Image.LANCZOS)
        a_ = np.clip((np.asarray(g)[..., 3].astype(np.float32) / 255 - 0.15) * 1.6, 0, 1)    # firm up the worn texture
        ab = np.pad(a_, 1); ol = np.asarray(Image.fromarray((ab * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(3))).astype(np.float32) / 255
        rgba = np.zeros(ab.shape + (4,), np.float32); rgba[..., 3] = np.maximum(ol, ab)
        rgba[..., :3] = (np.array(color, np.float32)[None, None] / 255) * (ab / np.maximum(rgba[..., 3], 1e-3))[..., None]
        gs.append(Image.fromarray((np.clip(rgba, 0, 1) * 255).astype(np.uint8)))
    st_ = Image.new('RGBA', (sum(g.width for g in gs), h + 2)); x_ = 0
    for g in gs: st_.alpha_composite(g, (x_, 0)); x_ += g.width
    return st_, [g.width for g in gs]
hs_img, hs_w = pstrip([(5, 11)] + [(1, d) for d in range(10)], (168, 102, 54), 15); hsave(hs_img, 'health.png')
st_img, st_w = pstrip([(3, 13)], (214, 170, 60), 11); hsave(st_img, 'star.png')
open(P('h', 'meta'), 'w', newline='\n').write('health %s\nstar %s\n' % (' '.join(map(str, hs_w)), ' '.join(map(str, st_w))))
# dialogue portraits: the head renders on a dark background, 52 px
for nm in A.PORTRAITS:
    pi_ = Image.open(os.path.join(WORK, 'sprites', 'portraits', nm + '.png')).convert('RGBA')
    bgp = Image.new('RGBA', pi_.size, (22, 24, 30, 255)); bgp.alpha_composite(pi_)
    bgp.convert('RGB').resize((52, 52), Image.LANCZOS).save(P('p', nm + '.png'), optimize=True)
# menu art + logo from models\\menu.txd (the logo becomes GTA WI below)
menu = rwtxd.read_txd(open(os.path.join(GTA, 'models', 'menu.txd'), 'rb').read())
def fit(a_, w, h, cover):
    im = Image.fromarray(a_).convert('RGBA')
    sc = max(w / im.width, h / im.height) if cover else min(w / im.width, h / im.height)
    im = im.resize((round(im.width * sc), round(im.height * sc)), Image.LANCZOS)
    if cover: x0 = (im.width - w) // 2; y0 = (im.height - h) // 2; im = im.crop((x0, y0, x0 + w, y0 + h))
    return im
def mkey(*names): return next(menu[k] for k in menu if k.lower() in names)
fit(mkey('gta3logo256'), 200, 100, False).quantize(128, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE).save(P('u', 'logo.png'), optimize=True)
# menu background: the three characters on the right of the texture, above its orange menu bar
fit(mkey('mainmenu24')[10:390, 238:506], 240, 320, True).quantize(128, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE).save(P('u', 'menu.png'), optimize=True)
# music: YOUR MIDI files - music\\maintheme.mid (menu) + music\\track01.mid, track02.mid ... (car radio)
import glob, shutil
MUS = os.path.join(KIT, 'music')
mt = [f for f in glob.glob(os.path.join(MUS, '*.mid')) if os.path.basename(f).lower() == 'maintheme.mid']
if mt: shutil.copyfile(mt[0], P('a', 'theme.mid'))
tracks = sorted(f for f in glob.glob(os.path.join(MUS, '*.mid')) if re.match(r'track\d+\.mid$', os.path.basename(f).lower()))
for i_, f in enumerate(tracks): shutil.copyfile(f, P('a', 'r%d.mid' % i_))
stats['music'] = dict(theme=bool(mt), radio=len(tracks))
# sound effects (extract.py took them out of YOUR audio\\sfx.RAW): small 8-bit WAVs the game plays for guns + explosions
import sfx as SFX_
for nm_, (_, mx_) in A.SFX.items():
    f_ = os.path.join(GTA, 'sfx', nm_ + '.wav')
    if os.path.exists(f_):
        x_, r_ = SFX_.read_wav16(f_)
        open(P('a', 's_%s.wav' % nm_), 'wb').write(SFX_.phone_wav(x_, r_, max_s=mx_, fade_s=min(0.4, mx_ * 0.3)))
stats['sfx'] = sorted(n_ for n_ in os.listdir(P('a')) if n_.startswith('s_'))
# GTA WI ("What If"): the logo's red III box becomes WI - same red and white, same stem weight
lg = np.array(Image.open(P('u', 'logo.png')).convert('RGBA'))
rmask = (lg[..., 0] > 120) & (lg[..., 1] < 80) & (lg[..., 3] > 0)
if rmask.any():
    ys_, xs_ = np.nonzero(rmask); x0_, x1_, y0_, y1_ = xs_.min(), xs_.max(), ys_.min(), ys_.max()
    bx_ = lg[y0_:y1_ + 1, x0_:x1_ + 1]; red_ = np.median(bx_[rmask[y0_:y1_ + 1, x0_:x1_ + 1]], 0).astype(np.uint8)
    wm_ = (bx_[..., 0] > 200) & (bx_[..., 1] > 200); wht_ = np.median(bx_[wm_], 0).astype(np.uint8) if wm_.any() else np.array([254, 253, 253, 255], np.uint8)
    Hb, Wb = bx_.shape[:2]; nb = np.empty_like(bx_); nb[:] = wht_
    sx_ = Wb / 66.0; sy_ = Hb / 42.0
    def fl(c0, c1, r0, r1): nb[int(r0 * sy_):int((r1 + 1) * sy_), int(c0 * sx_):int((c1 + 1) * sx_)] = red_
    fl(0, 10, 0, 41); fl(16, 24, 6, 41); fl(30, 40, 0, 41); fl(0, 40, 30, 41)        # W: three stems joined at the bottom
    fl(46, 65, 0, 11); fl(46, 65, 30, 41); fl(50, 61, 0, 41)                          # I with serifs
    lg[y0_:y1_ + 1, x0_:x1_ + 1] = nb; Image.fromarray(lg).save(P('u', 'logo.png'), optimize=True)
# cars: re-cut from the 32 px/m masters at 15 px/m (sprite scale of the new map), threshold alpha 110
CARS = A.CARS
def car_dims(src):
    # width = side to side when facing north (frame 0), length = nose to tail when facing east (frame 4), in metres
    ext = []
    for d in (0, 4):
        al = np.array(Image.open(os.path.join(MS, 'car_%s_d%02d.png' % (src, d))).convert('RGBA'))[..., 3] >= 128
        xs = np.nonzero(al.any(0))[0]; ext.append((xs[-1] - xs[0] + 1) / 32.0)
    return '%.2f' % ext[0], '%.2f' % ext[1]
def cut_car(name, src, rgb=None):
    fr = []
    for d in range(16):
        im = Image.open(os.path.join(MS, 'car_%s_d%02d.png' % (src, d))).convert('RGBA')
        f = 15.0 / 32.0
        s = im.resize((round(im.size[0] * f), round(im.size[1] * f)), Image.LANCZOS)
        s.putalpha(s.getchannel('A').point(lambda v: 255 if v > 110 else 0)); fr.append((im.size, s))
    bb = None
    for _, s in fr:
        b = s.getbbox(); bb = b if bb is None else (min(bb[0], b[0]), min(bb[1], b[1]), max(bb[2], b[2]), max(bb[3], b[3]))
    cw, ch = bb[2] - bb[0], bb[3] - bb[1]
    strip = Image.new('RGBA', (cw * 16, ch), (0, 0, 0, 0)); anc = []
    for k, (sz, s) in enumerate(fr):
        strip.paste(s.crop(bb), (k * cw, 0))
        anc += [round(s.size[0] / 2 - bb[0]), round(s.size[1] / 2 - bb[1])]
    a = np.array(strip)
    if rgb is not None:
        lum = a[..., :3].astype(np.float32).mean(2, keepdims=True)
        body = (a[..., :3].max(2) - a[..., :3].min(2)) > 18
        a[..., :3][body] = np.clip(lum / 110.0 * np.array(rgb), 0, 255)[body].astype(np.uint8)
    save8(qcolours(a, 48), P('s', 'c_%s.png' % name))
    w_, l_ = car_dims(src)
    meta.insert(0, 'car %s %d %d %s %s %s' % (name, cw, ch, w_, l_, ' '.join(map(str, anc))))
for c in reversed(CARS): cut_car(c, c)
# burnt wrecks: every car strip charred (the game loads one only when a car of that type blows up)
brng = np.random.default_rng(3)
for nm in CARS:
    ca = np.array(Image.open(P('s', 'c_%s.png' % nm)).convert('RGBA')).astype(np.float32)
    lum = ca[..., :3].mean(2, keepdims=True) * brng.uniform(0.7, 1.1, ca.shape[:2] + (1,))
    ca[..., :3] = np.clip(lum * 0.3 + np.array([20, 15, 12], np.float32), 0, 255)
    save8(qcolours(ca.astype(np.uint8), 24), P('s', 'c_%s_b.png' % nm))
# explosion: 8 procedural fireball frames (white core -> yellow -> orange -> red -> smoke), binary alpha
EN, ES = 8, 56; erng = np.random.default_rng(7)
eyy, exx = np.mgrid[0:ES, 0:ES].astype(np.float32) - ES / 2 + 0.5
eblobs = [(erng.uniform(-10, 10), erng.uniform(-10, 6), erng.uniform(8, 13)) for _ in range(11)]
estrip = np.zeros((ES, ES * EN, 4), np.uint8)
for f_ in range(EN):
    t_ = f_ / (EN - 1); grow = 0.7 + 0.55 * (1 - (1 - t_) ** 2)
    dens = np.zeros((ES, ES), np.float32)
    for bx_, by_, br_ in eblobs:
        cx_, cy_, r_ = bx_ * grow * 1.3, by_ * grow * 1.3 - t_ * 7, br_ * grow
        dens = np.maximum(dens, 1 - ((exx - cx_) ** 2 + (eyy - cy_) ** 2) / (r_ * r_))
    dens *= erng.uniform(0.8, 1.2, (ES, ES)).astype(np.float32)
    heat = dens * (1.3 - 1.1 * t_)
    col = np.zeros((ES, ES, 3), np.float32); col[:] = (72, 62, 58)
    for th_, c_ in ((0.25, (200, 60, 20)), (0.45, (250, 140, 30)), (0.65, (255, 215, 80)), (0.85, (255, 250, 225))):
        col[heat > th_] = c_
    fr_ = np.zeros((ES, ES, 4), np.uint8); fr_[..., :3] = col.astype(np.uint8)
    fr_[..., 3] = np.where(dens > 0.1 + 0.3 * t_, 255, 0)
    estrip[:, f_ * ES:(f_ + 1) * ES] = fr_
save8(qcolours(estrip, 32), P('s', 'boom.png'))
open(P('s', 'fx'), 'a', newline='\n').write('\nboom %d %d %d\n' % (ES, ES, EN))
# police chopper (from wave 3): searchlight pool, the chopper's shadow (8 dirs) and its rotor shadow (4 frames), all with alpha
from PIL import ImageDraw, ImageFilter
LW, LH = 96, 62
lyy, lxx = np.mgrid[0:LH, 0:LW].astype(np.float32)
lr = ((lxx - LW / 2 + 0.5) / (LW / 2)) ** 2 + ((lyy - LH / 2 + 0.5) / (LH / 2)) ** 2
la = np.clip(1 - lr, 0, 1) ** 0.7 * 160
lim = np.zeros((LH, LW, 4), np.uint8); lim[..., 0], lim[..., 1], lim[..., 2] = 255, 252, 228; lim[..., 3] = (la / 8).round().astype(np.uint8) * 8
save8(lim, P('s', 'light.png'))
HS = 120                                   # ~7 m chopper shadow at 12 px/m (cars are ~75 px)
def heli_shadow(ang):
    im = Image.new('L', (HS * 4, HS * 4), 0); d = ImageDraw.Draw(im); k = HS / 18.0; c = HS * 2
    d.ellipse((c - 9 * k, c - 22 * k, c + 9 * k, c + 8 * k), fill=255)          # cabin
    d.rectangle((c - 2 * k, c + 4 * k, c + 2 * k, c + 30 * k), fill=255)        # tail boom
    d.rectangle((c - 7 * k, c + 27 * k, c + 7 * k, c + 30 * k), fill=255)       # tail plane
    for sx_ in (-1, 1): d.rectangle((c + sx_ * 11 * k - k, c - 16 * k, c + sx_ * 11 * k + k, c + 6 * k), fill=255)   # skids
    im = im.rotate(-ang, resample=Image.BICUBIC).resize((HS, HS), Image.LANCZOS)
    return np.array(im)
hs = np.zeros((HS, HS * 8, 4), np.uint8)
for k_ in range(8):
    m_ = heli_shadow(45 * k_); hs[:, k_ * HS:(k_ + 1) * HS, 3] = (m_ > 90) * 95
hs[..., :3] = (10, 12, 14); save8(hs, P('s', 'hshadow.png'))
RW = 136                                   # ~11 m rotor disc
rs = np.zeros((RW, RW * 4, 4), np.uint8)
for f_ in range(4):
    im = Image.new('L', (RW * 4, RW * 4), 0); d = ImageDraw.Draw(im); c = RW * 2
    for j_, al in enumerate((255, 150, 80)):
        a_ = math.radians(f_ * 22.5 - j_ * 9)
        for sgn in (1, -1):
            d.line((c, c, c + sgn * math.cos(a_) * RW * 1.9, c + sgn * math.sin(a_) * RW * 1.9), fill=al, width=int(9 * RW / 88))
    m_ = np.array(im.resize((RW, RW), Image.LANCZOS)).astype(np.float32)
    rs[:, f_ * RW:(f_ + 1) * RW, 3] = (np.where(m_ > 40, np.minimum(m_, 200) * 0.45, 0) / 8).round().astype(np.uint8) * 8
rs[..., :3] = (10, 12, 14); save8(rs, P('s', 'hrotor.png'))
open(P('s', 'fx'), 'a', newline='\n').write('heli %d %d %d %d %d\n' % (LW, LH, HS, RW, 1 if os.path.exists(os.path.join(MR, 'heli_d00.png')) else 0))
# the chopper itself when its Blender renders exist (blend\_claude_dump\render_heli.py): 8 dirs at 9 px/m, rotor drawn by the game
if os.path.exists(os.path.join(MR, 'heli_d00.png')):
    HSC = 9.0 / 32.0                                                       # a bit bigger than the ground shadow: it's up near the camera
    hb, hw_, hh_, ha_, hbb = cut_ped([os.path.join(MR, 'heli_d%02d.png' % d) for d in range(8)], HSC)
    hsz = round(Image.open(os.path.join(MR, 'heli_d00.png')).size[1] * HSC)
    save8(qcolours(hb, 64), P('s', 'heli.png')); open(P('s', 'fx'), 'a', newline='\n').write('helib %d %d %d %d\n' % (hw_, hh_, ha_, int(round(hsz / 2 - hbb[1]))))
open(P('s', 'meta'), 'w', newline='\n').write('\n'.join(meta) + '\n')
# HUD: weapon icons (46 px; 36 px up to v0.7.0) + radar blips from hud.txd
HUD_S = 1.3                                # v0.7.1: the top-right HUD (weapon, health, stars) 1.3x bigger
for w in ('fist', 'bat', 'pistol', 'uzi', 'shotgun', 'ak47', 'm16'):
    im = Image.fromarray(hud[w]).resize((46, 46), Image.LANCZOS); a = np.array(im); a[..., 3] = np.where(a[..., 3] > 100, 255, 0)
    save8(a, P('h', 'w_%s.png' % w))
    pk = Image.fromarray(hud[w]).resize((22, 22), Image.LANCZOS); a = np.array(pk); a[..., 3] = np.where(a[..., 3] > 100, 255, 0)
    save8(a, P('h', 'k_%s.png' % w))
# health glyphs (heart + 0-9, made above) and the wanted star scaled per glyph so the widths stay exact
hm_ = open(P('h', 'meta')).read().split('\n'); hs_ = Image.open(P('h', 'health.png')).convert('RGBA')
for i_, l_ in enumerate(hm_):
    t_ = l_.split()
    if t_ and t_[0] == 'health':
        ws_ = [int(v) for v in t_[1:]]; nh_ = round(hs_.size[1] * HUD_S); nws_ = [round(w * HUD_S) for w in ws_]
        out_ = Image.new('RGBA', (sum(nws_), nh_), (0, 0, 0, 0)); x0_ = x1_ = 0
        for w, nw in zip(ws_, nws_):
            out_.paste(hs_.crop((x0_, 0, x0_ + w, hs_.size[1])).resize((nw, nh_), Image.LANCZOS), (x1_, 0)); x0_ += w; x1_ += nw
        a = np.array(out_); a[..., 3] = np.where(a[..., 3] > 100, 255, 0); save8(a, P('h', 'health.png'))
        hm_[i_] = 'health ' + ' '.join(map(str, nws_))
st_ = Image.open(P('h', 'star.png')).convert('RGBA'); st_ = st_.resize((round(st_.size[0] * HUD_S), round(st_.size[1] * HUD_S)), Image.LANCZOS)
a = np.array(st_); a[..., 3] = np.where(a[..., 3] > 100, 255, 0); save8(a, P('h', 'star.png'))
hm_ = [('star %d' % st_.size[0]) if l_.split()[:1] == ['star'] else l_ for l_ in hm_]
open(P('h', 'meta'), 'w', newline='\n').write('\n'.join(hm_))
# WASTED in GTA III's pricedown (fonts.txd font1, upright set: 16 x 32 px columns, 40 px rows from char 32),
# dark yellow with a black outline and drop shadow (v0.7.2)
fnt_ = font1
def pd_word(word, col, width):
    gl_ = []
    for ch in word:
        o_ = ord(ch) - 32; cx_, cy_ = (o_ % 16) * 32, (o_ // 16) * 40
        cell_ = fnt_[cy_:cy_ + 40, cx_:cx_ + 32]; cs_ = np.where(cell_[..., 3].max(0) > 40)[0]
        gl_.append(cell_[:, cs_[0]:cs_[-1] + 1] if len(cs_) else cell_[:, :12])
    gap_ = 2; ww_ = sum(g_.shape[1] for g_ in gl_) + gap_ * (len(gl_) - 1)
    im_ = np.zeros((40, ww_, 4), np.uint8); x_ = 0
    for g_ in gl_: im_[:, x_:x_ + g_.shape[1]] = g_; x_ += g_.shape[1] + gap_
    rs_ = np.where(im_[..., 3].max(1) > 40)[0]; im_ = im_[rs_[0]:rs_[-1] + 1].astype(np.float32)
    lum_ = im_[..., :3].mean(2, keepdims=True) / 255.0
    im_[..., :3] = np.clip(np.array(col, np.float32) * (0.55 + 0.45 * lum_ / max(lum_.max(), 1e-3)), 0, 255)
    sc_ = width / im_.shape[1]; ht_ = round(im_.shape[0] * sc_)
    t_ = Image.fromarray(im_.astype(np.uint8), 'RGBA').resize((width, ht_), Image.LANCZOS)
    pad_ = 4; cv_ = Image.new('RGBA', (width + 2 * pad_ + 2, ht_ + 2 * pad_ + 2), (0, 0, 0, 0))
    al_ = Image.new('L', cv_.size, 0); al_.paste(t_.getchannel('A'), (pad_, pad_))
    ol_ = al_.filter(ImageFilter.MaxFilter(5))                                   # 2 px black outline
    sh_ = Image.new('L', cv_.size, 0); sh_.paste(ol_, (2, 2)); sh_ = sh_.point(lambda v: v * 6 // 10)
    cv_.paste((0, 0, 0, 255), (0, 0), sh_); cv_.paste((0, 0, 0, 255), (0, 0), ol_); cv_.alpha_composite(t_, (pad_, pad_))
    a_ = np.array(cv_); a_[..., 3] = np.where(a_[..., 3] > 60, 255, 0)
    return a_
save8(qcolours(pd_word('WASTED', (196, 148, 28), 176), 32), P('u', 'wasted.png'))
for b in ('radar_weapon', 'radar_copcar'):
    a = np.array(Image.fromarray(hud[b]).resize((11, 11), Image.LANCZOS)); a[..., 3] = np.where(a[..., 3] > 90, 255, 0); save8(a, P('h', b[6:] + '.png'))
# radar: built from OUR map at 1/36 (GTA III style - one tan colour; alpha lets the dark disc through on roads)
RS_ = 36
rh_, rw_ = -(-PH // RS_), -(-PW // RS_)
fr = np.zeros((rh_, rw_), np.float32); fb = np.zeros((rh_, rw_), np.float32)
for j_ in range(rh_):                      # strip by strip: full-map temporaries would not fit in the VM
    y0_, y1_ = j_ * RS_, min(PH, (j_ + 1) * RS_)
    u_ = Uf[y0_:y1_].astype(np.int16); lum3 = u_.sum(2); sat_ = u_.max(2) - u_.min(2)
    bl_ = B[y0_:y1_, :, 3] > 0
    for m_, out_ in ((((lum3 < 384) & (sat_ < 26) & ~bl_) | (BR[y0_:y1_, :, 3] > 0), fr), (bl_, fb)):
        z_ = np.zeros((RS_, rw_ * RS_), np.float32); z_[:y1_ - y0_, :PW] = m_
        out_[j_] = z_.reshape(RS_, rw_, RS_).mean((0, 2))
ra = np.full(fr.shape, 255, np.uint8); ra[fb > 0.45] = 196; ra[fr > 0.30] = 8
rad = np.zeros(fr.shape + (4,), np.uint8); rad[..., 0], rad[..., 1], rad[..., 2] = 204, 145, 71; rad[..., 3] = ra
Image.fromarray(rad, 'RGBA').save(P('radar.png'), optimize=True)
stats['radar'] = list(rad.shape[:2])
# text: old GXT keys + the new ones
def gxt(path):
    d = open(path, 'rb').read(); n = struct.unpack_from('<I', d, 4)[0]; keys = {}
    for i_ in range(0, n, 12):
        keys[d[8 + i_ + 4:8 + i_ + 12].split(b'\0')[0].decode('latin1')] = struct.unpack_from('<I', d, 8 + i_)[0]
    base = 8 + n + 8
    def get(k):
        if k not in keys: return None
        o = base + keys[k]; e = o
        while d[e:e + 2] != b'\0\0': e += 2
        return re.sub(r'~.~', '', d[o:e].decode('utf-16-le')).strip()
    return get
G_ = gxt(os.path.join(GTA, 'text', 'american.gxt'))
txt = ['WASTED=WASTED', 'HELP_FOOT=5 attack, * change weapon, 0 enter a car (or take one).', 'HELP_CAR=5 horn, # handbrake, 0 get out.']
for k in ('EBAL_A', 'EBAL_A1', 'WRECKED'):                  # 8-Ball's first lines + the wreck warning, from the game's own text
    v_ = G_(k)
    if v_: txt.append('%s=%s' % (k, v_))
NEW = {
 'WI_TITLE': "WHAT IF 8-BALL DROVE?",
 'WI_NAME': "GTA WI",
 'WI_8A': "...ah, forget it. Hands or no hands, I'm drivin'. Get in!",
 'WI_8B': "Whoa whoa WHOA-",
 'WI_C1': "The cops think YOU blew up the bridge. They shoot on sight! Survive the wave: hold out and take some of them down.",
 'WI_WAVE': "WAVE",
 'WI_CLEAR': "Wave survived! +40 health. The heat is going up...",
 'WI_GUNS': "Two guns are stashed nearby - you can only grab one before the next wave. Check the radar.",
 'WI_OVER': "Overtime! They keep coming until you get the kills.",
 'WI_AMMO': "Run them over to pick up ammo.",
 'WI_BUSTED': "BUSTED",
 'WI_TIME': "SURVIVED",
 'WI_GETOUT': "GET OUT!",
 'WI_MISTY1': "MISTY: You Luigi's new driver? He said someone would pick me up from the clinic... guess not.",
 'WI_MISTY2': "MISTY: Still waiting here, sugar. Go do your thing.",
}
keys = set(l.split('=', 1)[0] for l in txt if '=' in l)
for k, v_ in NEW.items():
    if k not in keys: txt.append('%s=%s' % (k, v_))
open(P('t'), 'w', encoding='latin-1', newline='\n').write('\n'.join(txt) + '\n')
# phone menu icon: the Kuruma, side on
ic = Image.open(os.path.join(MS, 'car_kuruma_d04.png')).convert('RGBA'); ic = ic.crop(ic.getbbox())
ic = ic.resize((30, max(1, round(ic.height * 30 / ic.width))), Image.LANCZOS); icn = Image.new('RGBA', (32, 32)); icn.alpha_composite(ic, (1, (32 - ic.height) // 2))
icn.save(P('icon.png'), optimize=True)
stats['jar_payload_bytes'] = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(OUT) for f in fs)
# reference picture of the static map
ref = Image.fromarray(Uf).convert('RGBA')
for L in (BR, DEC, B, RL): ref.alpha_composite(Image.fromarray(L))
ref.convert('RGB').resize((PW // 4, PH // 4), Image.LANCZOS).save(os.path.join(WORK, 'export_ref.png'))
json.dump(stats, open(os.path.join(WORK, 'export_stats.json'), 'w'), indent=1, default=str)
print(json.dumps(stats, indent=1))
