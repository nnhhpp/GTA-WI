"""Minimal RenderWare TXD reader (GTA III/VC PC textures: DXT1/3, paletted, 32/16 bit). From the earlier pipeline (rw.py)."""
import struct
import numpy as np

def rw_version(libid):
    if libid & 0xFFFF0000:
        return (((libid >> 14) & 0x3FF00) + 0x30000) | ((libid >> 16) & 0x3F)
    return libid << 8

class Chunk:
    __slots__ = ('type', 'size', 'ver', 'data', 'off')
    def __init__(self, buf, off):
        self.type, self.size, lib = struct.unpack_from('<III', buf, off)
        self.ver = rw_version(lib)
        self.data = buf
        self.off = off + 12
    def children(self, start=None):
        p = self.off if start is None else start
        end = self.off + self.size
        while p + 12 <= end:
            c = Chunk(self.data, p)
            yield c
            p = c.off + c.size
    def child(self, t):
        for c in self.children():
            if c.type == t: return c
    def kids(self, t):
        return [c for c in self.children() if c.type == t]

STRUCT, STRING, EXT, TEXTURE, MATERIAL, MATLIST, FRAMELIST, GEOMETRY, CLUMP, ATOMIC, TEXNATIVE, TEXDICT, GEOMLIST = \
    0x01, 0x02, 0x03, 0x06, 0x07, 0x08, 0x0E, 0x0F, 0x10, 0x14, 0x15, 0x16, 0x1A
NODENAME = 0x0253F2FE

def _str(c):
    return c.data[c.off:c.off + c.size].split(b'\0')[0].decode('latin1')

# ---------------------------------------------------------------- TXD
def _dxt_block_colors(c0, c1, dxt1):
    def unpack565(c):
        r = (c >> 11) & 31; g = (c >> 5) & 63; b = c & 31
        return np.stack([(r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2)], -1).astype(np.int32)
    a = unpack565(c0); b = unpack565(c1)
    n = len(c0)
    cols = np.zeros((n, 4, 4), np.int32)
    cols[:, 0, :3] = a; cols[:, 1, :3] = b; cols[:, :, 3] = 255
    four = (c0 > c1) | (not dxt1)
    four = np.asarray(four)
    cols[:, 2, :3] = np.where(four[:, None], (2 * a + b) // 3, (a + b) // 2)
    cols[:, 3, :3] = np.where(four[:, None], (a + 2 * b) // 3, 0)
    if dxt1:
        cols[:, 3, 3] = np.where(four, 255, 0)
    return cols

def decode_dxt(data, w, h, kind):
    bw, bh = max(1, (w + 3) // 4), max(1, (h + 3) // 4)
    nb = bw * bh
    if kind == 1:
        blk = np.frombuffer(data, np.uint8, nb * 8).reshape(nb, 8)
        cblk = blk; alpha = None
    else:
        blk = np.frombuffer(data, np.uint8, nb * 16).reshape(nb, 16)
        cblk = blk[:, 8:]; alpha = blk[:, :8]
    c0 = cblk[:, 0].astype(np.uint32) | (cblk[:, 1].astype(np.uint32) << 8)
    c1 = cblk[:, 2].astype(np.uint32) | (cblk[:, 3].astype(np.uint32) << 8)
    cols = _dxt_block_colors(c0, c1, kind == 1)
    bits = cblk[:, 4].astype(np.uint32) | (cblk[:, 5].astype(np.uint32) << 8) | (cblk[:, 6].astype(np.uint32) << 16) | (cblk[:, 7].astype(np.uint32) << 24)
    idx = (bits[:, None] >> (2 * np.arange(16, dtype=np.uint32))[None]) & 3
    px = np.take_along_axis(cols, idx[:, :, None].astype(np.int64), 1)  # nb,16,4
    if kind == 3:
        a64 = np.zeros(nb, np.uint64)
        for i in range(8): a64 |= alpha[:, i].astype(np.uint64) << np.uint64(8 * i)
        av = (a64[:, None] >> (4 * np.arange(16, dtype=np.uint64))[None]) & np.uint64(15)
        px[:, :, 3] = (av * 17).astype(np.int32)
    img = px.reshape(bh, bw, 4, 4, 4).transpose(0, 2, 1, 3, 4).reshape(bh * 4, bw * 4, 4)
    return img[:h, :w].astype(np.uint8)

def decode_texnative(c):
    s = c.child(STRUCT); b = s.data; p = s.off
    platform, filt = struct.unpack_from('<II', b, p); p += 8
    name = b[p:p + 32].split(b'\0')[0].decode('latin1').lower(); p += 32
    p += 32  # mask
    rfmt, alpha_or_d3d = struct.unpack_from('<II', b, p); p += 8
    w, h, depth, nlev, rtype, comp = struct.unpack_from('<HHBBBB', b, p); p += 8
    if platform != 8:
        return name, None
    fmt = rfmt & 0x0F00
    pal = None
    if rfmt & 0x2000:
        pal = np.frombuffer(b, np.uint8, 256 * 4, p).reshape(256, 4); p += 1024
    elif rfmt & 0x4000:
        pal = np.frombuffer(b, np.uint8, 32 * 4, p).reshape(32, 4); p += 128
    size = struct.unpack_from('<I', b, p)[0]; p += 4
    data = b[p:p + size]
    if comp in (1, 3):
        img = decode_dxt(data, w, h, comp)
    elif pal is not None:
        if rfmt & 0x2000:
            ix = np.frombuffer(data, np.uint8, w * h)
        else:
            raw = np.frombuffer(data, np.uint8, (w * h + 1) // 2)
            ix = np.stack([raw & 15, raw >> 4], 1).reshape(-1)[:w * h]
        img = pal[ix].reshape(h, w, 4).copy()
    elif fmt in (0x0500, 0x0600):
        img = np.frombuffer(data, np.uint8, w * h * 4).reshape(h, w, 4)[:, :, [2, 1, 0, 3]].copy()
        if fmt == 0x0600: img[:, :, 3] = 255
    elif fmt in (0x0100, 0x0200, 0x0300, 0x0A00):
        v = np.frombuffer(data, np.uint16, w * h).reshape(h, w).astype(np.uint32)
        img = np.zeros((h, w, 4), np.uint8)
        if fmt == 0x0200:
            img[..., 0] = ((v >> 11) & 31) * 255 // 31; img[..., 1] = ((v >> 5) & 63) * 255 // 63; img[..., 2] = (v & 31) * 255 // 31; img[..., 3] = 255
        elif fmt == 0x0300:
            img[..., 3] = ((v >> 12) & 15) * 17; img[..., 0] = ((v >> 8) & 15) * 17; img[..., 1] = ((v >> 4) & 15) * 17; img[..., 2] = (v & 15) * 17
        else:
            img[..., 3] = np.where(v & 0x8000, 255, 0) if fmt == 0x0100 else 255
            img[..., 0] = ((v >> 10) & 31) * 255 // 31; img[..., 1] = ((v >> 5) & 31) * 255 // 31; img[..., 2] = (v & 31) * 255 // 31
    elif fmt == 0x0400:
        l = np.frombuffer(data, np.uint8, w * h).reshape(h, w)
        img = np.stack([l, l, l, np.full_like(l, 255)], -1)
    else:
        return name, None
    if not (rfmt & 0x0F00 in (0x0100, 0x0300, 0x0500)) and comp != 3 and pal is None and comp != 1:
        img[..., 3] = 255
    return name, img

def read_txd(buf):
    root = Chunk(buf, 0)
    out = {}
    for c in root.kids(TEXNATIVE):
        try:
            n, img = decode_texnative(c)
            if img is not None: out[n] = img
        except Exception:
            pass
    return out

