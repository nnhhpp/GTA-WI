# /// script
# requires-python = ">=3.9"
# dependencies = ["numpy"]
# ///
"""Step 3: the car + pedestrian path graphs of Portland, from YOUR GTA III data files (IDE 'path' sections + IPL placements).

  build_paths.py <work\\gta3> <work\\paths.json>

Node positions are mapped into the 4 px/m "east view" the map was drawn from (the exporter turns them into map pixels).
"""
import os, sys, json
import numpy as np

PORTLAND_IPL = ['industNE', 'industNW', 'industSE', 'industSW']
BBOX = (740, -1150, 1600, 230)
# east view: u = -y (screen right), v = 0.7071 * (x + z) (screen up); px = (u - U0) * PPM, py = (V1 - v) * PPM
U0, V1, PPM = -228.6188201904297, 1185.8242687006295, 4


def main():
    if len(sys.argv) < 3: sys.exit(__doc__)
    game, out = sys.argv[1], sys.argv[2]

    def ci(path):
        cur = game
        for part in path.replace('\\', '/').split('/'):
            if not part: continue
            m = [x for x in os.listdir(cur) if x.lower() == part.lower()]
            if not m: return None
            cur = os.path.join(cur, m[0])
        return cur

    def dat_lines():
        lines = []
        for f in ('data/default.dat', 'data/gta3.dat'):
            lines += open(ci(f), encoding='latin1').read().splitlines()
        return lines

    def sections(path):
        sec = None
        for line in open(path, encoding='latin1'):
            line = line.split('#')[0].strip()
            if not line: continue
            if sec is None: sec = line.lower(); continue
            if line.lower() == 'end': sec = None; continue
            yield sec, [t.strip() for t in line.split(',')]

    def quat_matrix(x, y, z, w):
        w = -w  # GTA stores the conjugate
        return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                         [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                         [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])

    defs = {}
    for l in dat_lines():
        t = l.split('#')[0].split()
        if len(t) >= 2 and t[0] == 'IDE':
            p = ci(t[1])
            if not p: continue
            cur = None
            for sec, tok in sections(p):
                if sec != 'path': continue
                if tok[0].lower() in ('car', 'ped'):
                    cur = []; defs[(tok[0].lower(), tok[2].lower())] = cur
                else: cur.append([float(v) for v in tok])
    insts = []
    for l in dat_lines():
        t = l.split('#')[0].split()
        if len(t) >= 2 and t[0] == 'IPL' and any(n.lower() in t[1].lower() for n in PORTLAND_IPL):
            for sec, r in sections(ci(t[1])):
                if sec == 'inst':
                    insts.append((r[1].lower(), np.array([float(r[2]), float(r[3]), float(r[4])]), [float(v) for v in r[8:12]]))
    if not insts: sys.exit('ERROR: no Portland IPL files found under %s\\data\\maps' % game)

    def to_screen(p):
        u = -p[1]; v = 0.70710678 * (p[0] + p[2])
        return ((u - U0) * PPM, (V1 - v) * PPM)

    def build(kind, merge=1.2):
        nodes = []; edges = {}; ext = {}
        def ext_node(p):
            key = (round(p[0] / merge), round(p[1] / merge))
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for ni in ext.get((key[0] + dx, key[1] + dy), []):
                        q = nodes[ni]
                        if abs(q[0] - p[0]) < merge and abs(q[1] - p[1]) < merge and abs(q[2] - p[2]) < 2.5: return ni
            nodes.append(p); ext.setdefault(key, []).append(len(nodes) - 1); return len(nodes) - 1
        for name, pos, q in insts:
            d = defs.get((kind, name))
            if not d or not (BBOX[0] < pos[0] < BBOX[2] and BBOX[1] < pos[1] < BBOX[3]): continue
            R = quat_matrix(*q); idx = []
            for nd in d:
                ty = int(nd[0])
                if ty == 0: idx.append(-1); continue
                w = R @ (np.array(nd[3:6]) / 16.0) + pos
                if ty == 1: idx.append(ext_node(w))
                else: nodes.append(w); idx.append(len(nodes) - 1)
            for i, nd in enumerate(d):
                if int(nd[0]) == 0: continue
                nx = int(nd[1])
                if nx < 0 or nx >= len(d) or idx[nx] < 0 or idx[i] == idx[nx]: continue
                edges[(idx[i], idx[nx])] = (int(nd[7]), int(nd[8]))
        return nodes, edges

    res = {'note': 'world metres + screen px in the 4 px/m east view; lanes = (left,right) as in GTA III IDE', 'groups': {}}
    for kind in ('car', 'ped'):
        nodes, edges = build(kind)
        N = [{'id': i, 'x': round(float(p[0]), 2), 'y': round(float(p[1]), 2), 'z': round(float(p[2]), 2),
              'sx': round(to_screen(p)[0], 1), 'sy': round(to_screen(p)[1], 1)} for i, p in enumerate(nodes)]
        E = []
        for (a, b), (l, r) in edges.items():
            pa, pb = nodes[a], nodes[b]
            # Callahan bridge deck (elevated, west of Portland): no random traffic
            bridge = kind == 'car' and pa[2] > 20 and pb[2] > 20 and pa[1] < -880 and pb[1] < -880
            E.append({'a': a, 'b': b, 'lanes_l': l, 'lanes_r': r, 'no_traffic': bool(bridge)})
        res['groups'][kind] = {'nodes': N, 'edges': E}
        print('paths:', kind, len(N), 'nodes', len(E), 'edges')
    json.dump(res, open(out, 'w'))


if __name__ == '__main__':
    main()
