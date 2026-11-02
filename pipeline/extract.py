# /// script
# requires-python = ">=3.9"
# dependencies = []
# ///
"""Step 1: copy what the build needs out of YOUR GTA III folder into work\\gta3 (nothing from the game ships with GTA WI).

  extract.py <GTA III folder> <work\\gta3>

Reads models\\gta3.img + gta3.dir (and txd.img + txd.dir as a fallback) for the ped / car / chopper models, plus a few
loose files (HUD, fonts, menu art, ped animations, data files). Names are matched case-insensitively.
"""
import os, sys, struct, shutil
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import assets as A


def ci(root, rel):
    """case-insensitive path lookup (the game ships names like MISC.TXD, Generic\\wheels.DFF)"""
    cur = root
    for part in rel.replace('\\', '/').split('/'):
        if not part: continue
        try: m = [x for x in os.listdir(cur) if x.lower() == part.lower()]
        except OSError: return None
        if not m: return None
        cur = os.path.join(cur, m[0])
    return cur


class Img:
    """GTA III IMG v1: name.dir holds 32-byte entries (offset, size in 2048-byte sectors, 24-char name)"""
    def __init__(self, img, dir_):
        self.img = img; self.ent = {}
        d = open(dir_, 'rb').read()
        for i in range(0, len(d) - 31, 32):
            off, size = struct.unpack_from('<II', d, i)
            n = d[i + 8:i + 32].split(b'\0')[0].decode('latin1').lower()
            self.ent[n] = (off * 2048, size * 2048)
    def read(self, name):
        e = self.ent.get(name.lower())
        if e is None: return None
        with open(self.img, 'rb') as f: f.seek(e[0]); return f.read(e[1])


def main():
    if len(sys.argv) < 3: sys.exit(__doc__)
    game, out = sys.argv[1], sys.argv[2]
    if not ci(game, 'models/gta3.img'): sys.exit('ERROR: no models\\gta3.img in "%s" - set GTA3 in config.bat to your GTA III folder' % game)
    os.makedirs(os.path.join(out, 'img'), exist_ok=True)
    imgs = [Img(ci(game, 'models/%s.img' % n), ci(game, 'models/%s.dir' % n)) for n in ('gta3', 'txd') if ci(game, 'models/%s.dir' % n)]
    # model -> txd from default.ide (peds + cars sections); special characters use their own name
    txd = {}
    sec = None
    for l in open(ci(game, 'data/default.ide'), encoding='latin1'):
        l = l.split('#')[0].strip()
        if not l: continue
        if l.lower() in ('objs', 'tobj', 'hier', 'cars', 'peds', 'weap', 'path', '2dfx'): sec = l.lower(); continue
        if l.lower() == 'end': sec = None; continue
        t = [x.strip() for x in l.split(',')]
        if sec in ('cars', 'peds') and len(t) > 2: txd[t[1].lower()] = t[2].lower()
    models = set(A.PEDS_WALK.values()) | set(m for m, _ in A.PEDS_RUN.values()) | set(A.CARS) | {A.HELI} | set(A.PORTRAITS.values())
    missing = []
    for m in sorted(models):
        for ext, name in (('dff', m), ('txd', txd.get(m, m))):
            data = next((d for d in (i.read(name + '.' + ext) for i in imgs) if d), None)
            if data is None: missing.append(name + '.' + ext); continue
            open(os.path.join(out, 'img', '%s.%s' % (m, ext)), 'wb').write(data)     # saved under the MODEL name: dff + txd pair up
    for rel in A.LOOSE:
        src = ci(game, rel)
        if not src: missing.append(rel); continue
        dst = os.path.join(out, rel.lower()); os.makedirs(os.path.dirname(dst), exist_ok=True); shutil.copyfile(src, dst)
    for rel in A.LOOSE_DIRS:
        src = ci(game, rel)
        if not src: missing.append(rel); continue
        for dp, _, fs in os.walk(src):
            for f in fs:
                dst = os.path.join(out, rel.lower(), os.path.relpath(os.path.join(dp, f), src).lower())
                os.makedirs(os.path.dirname(dst), exist_ok=True); shutil.copyfile(os.path.join(dp, f), dst)
    n = sum(len(fs) for _, _, fs in os.walk(out))
    print('extract: %d files -> %s' % (n, out))
    if missing:
        sys.exit('ERROR: not found in your GTA III folder: ' + ', '.join(missing) + '\n(PC version of GTA III needed: Steam / retail / Rockstar Launcher)')


if __name__ == '__main__':
    main()
