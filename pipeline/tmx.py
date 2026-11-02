"""Minimal TMX loader/renderer for chunk01 (tile layers + object layers)."""
import os, numpy as np, xml.etree.ElementTree as ET
from PIL import Image
Image.MAX_IMAGE_PIXELS = None
FH, FV, FD = 0x80000000, 0x40000000, 0x20000000

class TS: pass

class Map:
    def __init__(self, path):
        self.path = path; D = self.D = os.path.dirname(path)
        m = self.root = ET.parse(path).getroot()
        self.W, self.H, self.T = int(m.get('width')), int(m.get('height')), int(m.get('tilewidth'))
        self.props = {p.get('name'): p.get('value') for p in m.find('properties')} if m.find('properties') is not None else {}
        self.tsets = []
        for ts in m.findall('tileset'):
            if ts.get('source'):
                p = os.path.normpath(os.path.join(D, ts.get('source'))); r = ET.parse(p).getroot() if os.path.exists(p) else None; td = os.path.dirname(p)
            else: r = ts; td = D
            s = TS(); s.first = int(ts.get('firstgid')); s.tiles = {}; s.tprops = {}; s.grid = None; s.img = None; s.name = r.get('name') if r is not None else '?'
            s.src = ts.get('source')
            if r is not None:
                img = r.find('image')
                if img is not None: s.img = (os.path.normpath(os.path.join(td, img.get('source'))), int(r.get('tilewidth')), int(r.get('columns')), img.get('trans'))
                for t in r.findall('tile'):
                    i = int(t.get('id')); im = t.find('image')
                    if im is not None: s.tiles[i] = (os.path.normpath(os.path.join(td, im.get('source'))), int(im.get('width')), int(im.get('height')))
                    s.tprops[i] = {pp.get('name'): pp.get('value') for pp in t.iter('property')}
                    s.tprops[i]['_type'] = t.get('type')
            self.tsets.append(s)
        self.tsets.sort(key=lambda s: s.first)
        self.layers = {}; self.order = []
        for el in m:
            if el.tag == 'layer':
                v = np.array([int(x) for x in el.find('data').text.replace('\n', '').split(',') if x.strip()], dtype=np.int64).reshape(self.H, self.W)
                self.layers[el.get('name')] = v; self.order.append(('tile', el.get('name')))
            elif el.tag == 'objectgroup':
                self.order.append(('obj', el.get('name')))
        self.objs = {g.get('name'): g for g in m.findall('objectgroup')}
        self.cache = {}
    def tsof(self, gid):
        g = gid & 0x0FFFFFFF; s = [t for t in self.tsets if t.first <= g][-1]; return s, g - s.first
    def tile(self, gid):
        if gid in self.cache: return self.cache[gid]
        s, i = self.tsof(gid)
        if s.img:
            if s.grid is None:
                im = Image.open(s.img[0]).convert('RGBA'); sheet = np.array(im)
                if s.img[3]:
                    c = tuple(int(s.img[3][k:k+2], 16) for k in (0, 2, 4))
                    key = (sheet[..., 0] == c[0]) & (sheet[..., 1] == c[1]) & (sheet[..., 2] == c[2]); sheet[key, 3] = 0
                tw = s.img[1]; s.grid = (sheet, tw, s.img[2])
            sheet, tw, cols = s.grid; y, x = divmod(i, cols)
            a = sheet[y*tw:(y+1)*tw, x*tw:(x+1)*tw]
            if a.shape[:2] != (tw, tw):
                b = np.zeros((tw, tw, 4), np.uint8); b[:a.shape[0], :a.shape[1]] = a; a = b
        else:
            path, w, h = s.tiles[i]; a = np.array(Image.open(path).convert('RGBA'))
        if gid & FD: a = a.transpose(1, 0, 2)
        if gid & FH: a = a[:, ::-1]
        if gid & FV: a = a[::-1]
        a = np.ascontiguousarray(a); self.cache[gid] = a; return a

def over(dst, src, x, y):
    h, w = src.shape[:2]
    x0, y0, x1, y1 = max(0, x), max(0, y), min(dst.shape[1], x + w), min(dst.shape[0], y + h)
    if x0 >= x1 or y0 >= y1: return
    s = src[y0-y:y1-y, x0-x:x1-x].astype(np.float32) / 255; d = dst[y0:y1, x0:x1]
    sa = s[..., 3:4]; da = d[..., 3:4]; oa = sa + da * (1 - sa)
    d[..., :3] = (s[..., :3] * sa + d[..., :3] * da * (1 - sa)) / np.maximum(oa, 1e-6); d[..., 3:4] = oa

def render_layer(M, name, img=None):
    T = M.T
    if img is None: img = np.zeros((M.H*T, M.W*T, 4), np.float32)
    v = M.layers[name]
    for (ty, tx), gid in np.ndenumerate(v):
        if gid == 0: continue
        a = M.tile(int(gid)); over(img, a, tx*T, (ty+1)*T - a.shape[0])
    return img

def render_objects(M, name, img):
    for o in M.objs[name].findall('object'):
        if o.get('gid') is None: continue
        gid = int(o.get('gid')); a = M.tile(gid)
        w, h = float(o.get('width')), float(o.get('height'))
        if (round(w), round(h)) != (a.shape[1], a.shape[0]) and w > 0 and h > 0:
            a = np.array(Image.fromarray(a).resize((max(1, round(w)), max(1, round(h))), Image.NEAREST))
        over(img, a, int(round(float(o.get('x')))), int(round(float(o.get('y')))) - a.shape[0])
    return img

def u8(a): return (np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8)
