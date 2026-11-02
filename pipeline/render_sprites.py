"""Step 2 (Blender, background): render every sprite GTA WI needs from the GTA III models in work\\gta3\\img.

  blender -b --python render_sprites.py -- <work dir> [cars,peds,run,combat,heli,portraits] [names]

Needs INU Tools (GPL-3, github.com/INU-ez/INU_Tools-GTA-Blender) installed as a Blender extension - setup.bat does it.
Builds its own scene (orthographic 45 degree camera, one sun, white world at 0.55, EEVEE, transparent film) and never
saves a .blend. Everything is rendered at 32 px per metre; export.py scales the frames down for the phone.

Output (work\\sprites):
  master\\car_<name>_dNN.png                 16 directions
  master\\ped_<name>_{stand,dead}_dNN.png    8 (dead: 4) directions
  master\\ped_<name>_walk_dNN_fJ.png         walk_civi, 4 frames
  master_run\\ped_<name>_run_dNN_fJ.png      run cycles, 8 frames (+ stand/dead for swat, fbi, army)
  master_combat\\ped_claude_<attack>_dNN_fJ.png
  master_run\\heli_dNN.png                   the police chopper, rotors hidden
  portraits\\<name>.png                      head close-ups for the dialogue box
"""
import bpy, math, os, sys, re, struct, shutil, tempfile, time, traceback, importlib, mathutils

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import assets as A

args = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
WORK = os.path.abspath(args[0] if args else os.path.join(HERE, '..', 'work'))
ONLY = set(args[1].split(',')) if len(args) > 1 and args[1] else {'cars', 'peds', 'run', 'combat', 'heli', 'portraits'}
NAMES = set(args[2].split(',')) if len(args) > 2 else None       # optional: only these cars / peds
GTA = os.path.join(WORK, 'gta3'); IMG = os.path.join(GTA, 'img')
OUT = {k: os.path.join(WORK, 'sprites', d) for k, d in (('m', 'master'), ('r', 'master_run'), ('c', 'master_combat'), ('p', 'portraits'))}
for d in OUT.values(): os.makedirs(d, exist_ok=True)
LOG = os.path.join(WORK, 'sprites', 'render_log.txt')
open(LOG, 'w').write('start %s\n' % time.ctime())
def log(*a):
    print('[render]', *a, flush=True)
    with open(LOG, 'a') as f: f.write(' '.join(map(str, a)) + '\n')

# ------------------------------------------------------------------ INU Tools
def inu():
    for name in ('bl_ext.user_default.inu_tools_gta_sa', 'inu_tools_gta_sa'):
        try:
            import addon_utils
            addon_utils.enable(name, default_set=True)
            return importlib.import_module(name + '.ops.dff_import')
        except Exception:
            continue
    raise SystemExit('INU Tools is not installed in this Blender - run setup.bat again')
DFF = inu()

# ------------------------------------------------------------------ the sprite rig (same numbers as the original sprites.blend)
SC = bpy.context.scene
for o in list(bpy.data.objects): bpy.data.objects.remove(o, do_unlink=True)
cam = bpy.data.objects.new('SPRCAM', bpy.data.cameras.new('SPRCAM')); SC.collection.objects.link(cam)
sun = bpy.data.objects.new('SPRSUN', bpy.data.lights.new('SPRSUN', 'SUN')); SC.collection.objects.link(sun)
pv = bpy.data.objects.new('PIVOT', None); SC.collection.objects.link(pv)
RIG = {cam, sun, pv}
cam.data.type = 'ORTHO'; cam.data.clip_start = 0.1; cam.data.clip_end = 200
cam.rotation_euler = (math.radians(45), 0, math.radians(-90))
cam.location = -mathutils.Vector((0.70710678, 0, -0.70710678)) * 50 + mathutils.Vector((0, 0, 0.6))
SC.camera = cam
sun.rotation_euler = (math.radians(40), math.radians(25), math.radians(-60)); sun.data.energy = 2.2
w = bpy.data.worlds.new('SPRW'); SC.world = w; w.use_nodes = True
bg = w.node_tree.nodes.get('Background'); bg.inputs[0].default_value = (1, 1, 1, 1); bg.inputs[1].default_value = 0.55
r = SC.render
r.engine = 'BLENDER_EEVEE_NEXT' if 'BLENDER_EEVEE_NEXT' in [e.identifier for e in type(r).bl_rna.properties['engine'].enum_items] else 'BLENDER_EEVEE'
r.film_transparent = True; r.resolution_percentage = 100
r.image_settings.file_format = 'PNG'; r.image_settings.color_mode = 'RGBA'
SC.view_settings.view_transform = 'Standard'; SC.view_settings.look = 'None'
SC.eevee.taa_render_samples = 16

def models(): return [o for o in SC.objects if o not in RIG]
def under(o, root):
    while o is not None:
        if o == root: return True
        o = o.parent
    return False

def clear():
    for o in models(): bpy.data.objects.remove(o, do_unlink=True)
    for blk in (bpy.data.meshes, bpy.data.materials, bpy.data.images):
        for x in list(blk):
            if x.users == 0: blk.remove(x)
    pv.rotation_euler = (0, 0, 0)

def load(dff_path, txd_path, stem='m'):
    """import a dff with its txd (INU finds the txd next to the dff by name); returns the new objects"""
    td = tempfile.mkdtemp()
    shutil.copyfile(dff_path, os.path.join(td, stem + '.dff'))
    if txd_path and os.path.exists(txd_path): shutil.copyfile(txd_path, os.path.join(td, stem + '.txd'))
    old = set(bpy.data.objects); stats = {}
    for _ in DFF._iter_import_dff_files([os.path.join(td, stem + '.dff')], bpy.context, stats, import_game='III', link_alpha=True, skip_2dfx=True): pass
    new = [o for o in bpy.data.objects if o not in old]
    for o in new:
        if o.name not in SC.objects:
            try: SC.collection.objects.link(o)
            except Exception: pass
    shutil.rmtree(td, ignore_errors=True)
    log('  import', os.path.basename(dff_path), {k: v for k, v in stats.items() if k in ('imported', 'txd_loaded', 'errors')}, len(new), 'objects')
    return new

def model(name):
    return os.path.join(IMG, name + '.dff'), os.path.join(IMG, name + '.txd')

def to_pivot(objs, hide_parts=True):
    for o in objs:
        nm = o.name.lower()
        if o.type == 'MESH' and hide_parts and ('_dam' in nm or nm.endswith('_vlo') or nm.endswith('_lo')): o.hide_render = True
        if o.parent is None: o.parent = pv

def face(k, n):
    th = 2 * math.pi * k / n
    pv.rotation_euler = (0, 0, math.atan2(-math.cos(th), -math.sin(th)))

def shot(path, frame_m, res):
    cam.data.ortho_scale = frame_m; r.resolution_x = r.resolution_y = res
    r.filepath = path; bpy.ops.render.render(write_still=True)

# ------------------------------------------------------------------ ped.ifp animations
def ifp_load(want):
    d = open(os.path.join(GTA, 'anim', 'ped.ifp'), 'rb').read()
    def rd(p): return d[p:p + 4].decode('latin1'), struct.unpack_from('<I', d, p + 4)[0]
    def pad(n): return (n + 3) & ~3
    p = 8; tag, sz = rd(p); p += 8 + sz
    end = 8 + struct.unpack_from('<I', d, 4)[0]
    while p < end - 8:
        tag, sz = rd(p)
        if tag != 'NAME': p += 1; continue
        name = d[p + 8:p + 8 + sz].split(b'\0')[0].decode('latin1'); p += 8 + pad(sz)
        tag, dsz = rd(p); dg_end = p + 8 + dsz; p += 8
        tag, isz = rd(p); nobj = struct.unpack_from('<i', d, p + 8)[0]; p += 8 + pad(isz)
        anim = {}
        for _ in range(nobj):
            tag, csz = rd(p); cp_end = p + 8 + csz; p += 8
            tag, asz = rd(p)
            bname = d[p + 8:p + 8 + 28].split(b'\0')[0].decode('latin1')
            nfr = struct.unpack_from('<i', d, p + 8 + 28)[0]
            p += 8 + pad(asz); keys = []
            if nfr > 0:
                tag, ksz = rd(p); p += 8
                for _k in range(nfr):
                    if tag == 'KR00': x, y, z, w_, t = struct.unpack_from('<5f', d, p); p += 20
                    elif tag == 'KRT0': x, y, z, w_, tx, ty, tz, t = struct.unpack_from('<8f', d, p); p += 32
                    else: x, y, z, w_, tx, ty, tz, sx, sy, sz_, t = struct.unpack_from('<11f', d, p); p += 44
                    keys.append((t, (w_, x, y, z)))
            anim[bname.lower()] = keys
            p = cp_end
        if name.lower() == want.lower(): return anim
        p = dg_end
    raise SystemExit('animation %s not in ped.ifp' % want)
ANIM = {}
def anim(n):
    if n not in ANIM: ANIM[n] = ifp_load(n)
    return ANIM[n]
def dur(a): return max(k[-1][0] for k in a.values() if k)

def pose(objs, a, t):
    for o in objs:
        keys = a.get(o.name.lower().split('.')[0])
        if not keys: continue
        d_ = keys[-1][0] or 1
        tt = t % d_ if d_ > 0 else 0
        k1 = 0
        while k1 < len(keys) - 1 and keys[k1 + 1][0] <= tt: k1 += 1
        k2 = min(k1 + 1, len(keys) - 1); ka, kb = keys[k1], keys[k2]
        f = 0 if kb[0] == ka[0] else (tt - ka[0]) / (kb[0] - ka[0])
        q = mathutils.Quaternion(ka[1]).slerp(mathutils.Quaternion(kb[1]), f).conjugated()
        o.rotation_mode = 'QUATERNION'; o.rotation_quaternion = q

PED_M, PED_RES = 3.2, 102
def ped(name):
    clear(); objs = load(*model(name)); to_pivot(objs, hide_parts=False)
    return [o for o in SC.objects if o.type in ('MESH', 'EMPTY') and o is not pv and under(o, pv)]

def stand_dead(objs, nm, out):
    st, de = anim('idle_stance'), anim('KO_shot_front'); dd = dur(de)
    for k in range(8):
        face(k, 8)
        pose(objs, st, 0.0); shot(os.path.join(out, 'ped_%s_stand_d%02d.png' % (nm, k)), PED_M, PED_RES)
        if k % 2 == 0: pose(objs, de, dd - 0.001); shot(os.path.join(out, 'ped_%s_dead_d%02d.png' % (nm, k)), PED_M, PED_RES)

# ------------------------------------------------------------------ jobs
def job_peds():
    wk = anim('walk_civi')
    for nm, mdl in A.PEDS_WALK.items():
        if NAMES and nm not in NAMES: continue
        objs = ped(mdl)
        for k in range(8):
            face(k, 8)
            for j in range(4):
                pose(objs, wk, 0.3 * j); shot(os.path.join(OUT['m'], 'ped_%s_walk_d%02d_f%d.png' % (nm, k, j)), PED_M, PED_RES)
        stand_dead(objs, nm, OUT['m'])
        log('ped', nm)

def job_run():
    for nm, (mdl, an) in A.PEDS_RUN.items():
        if NAMES and nm not in NAMES: continue
        objs = ped(mdl); a = anim(an); d_ = dur(a)
        for k in range(8):
            face(k, 8)
            for j in range(8):
                pose(objs, a, d_ * j / 8); shot(os.path.join(OUT['r'], 'ped_%s_run_d%02d_f%d.png' % (nm, k, j)), PED_M, PED_RES)
        if nm in A.RUN_EXTRA: stand_dead(objs, nm, OUT['r'])
        log('run', nm)

def job_combat():
    for atk, (an, nf, weapon) in A.ATTACKS.items():
        objs = ped(A.PEDS_WALK['claude'])
        if weapon:      # the gun from Generic\weapons.dff (textures in MISC.TXD), held in the right hand
            wo = load(os.path.join(GTA, 'models', 'generic', 'weapons.dff'), os.path.join(GTA, 'models', 'misc.txd'), 'weapons')
            hand = next((o for o in objs if o.name.lower().split('.')[0] in ('srhand', 'rhand', 'bip01 r hand')), None)
            keep = [o for o in wo if o.name.lower().startswith(weapon)]
            for o in wo:
                if o not in keep and o.parent is None: o.hide_render = True
                if o not in keep and o.type == 'MESH' and not any(under(o, k) for k in keep): o.hide_render = True
            for o in keep:
                if o.parent is None and hand: o.parent = hand; o.location = (0, 0, 0); o.rotation_euler = (0, 0, 0)
        a = anim(an); d_ = dur(a)
        for k in range(8):
            face(k, 8)
            for j in range(nf):
                pose(objs, a, d_ * (j + 0.5) / nf); shot(os.path.join(OUT['c'], 'ped_claude_%s_d%02d_f%d.png' % (atk, k, j)), PED_M, PED_RES)
        log('combat', atk)

def car_info():
    ide = open(os.path.join(GTA, 'data', 'default.ide'), encoding='latin1').read().splitlines()
    wheels, cars, sec = {}, {}, None
    for l in ide:
        l = l.split('#')[0].strip()
        if not l: continue
        if l.lower() in ('objs', 'cars', 'peds', 'weap', 'hier', 'tobj', 'path', '2dfx'): sec = l.lower(); continue
        if l.lower() == 'end': sec = None; continue
        t = [x.strip() for x in l.split(',')]
        if sec == 'objs': wheels[t[0]] = t[1]
        if sec == 'cars' and len(t) > 3 and t[3].lower() == 'car': cars[t[1].lower()] = dict(wheel=wheels.get(t[-2]), wscale=float(t[-1]))
    cols, combos, sec = [], {}, None
    for l in open(os.path.join(GTA, 'data', 'carcols.dat'), encoding='latin1').read().splitlines():
        l = l.split('#')[0].strip()
        if not l: continue
        if sec is None: sec = l; continue
        if l == 'end': sec = None; continue
        if sec == 'col':
            n = [int(x) for x in re.findall(r'\d+', l)][:3]
            if len(n) == 3: cols.append(tuple(n))
        if sec == 'car':
            t = [x.strip() for x in l.split(',') if x.strip()]; combos[t[0].lower()] = [int(x) for x in t[1:]]
    return cars, cols, combos

def car_colours(prim, sec_):
    done = set()
    for o in models():
        if o.type != 'MESH': continue
        for m in o.data.materials:
            if not m or not m.use_nodes or m.name in done: continue
            done.add(m.name)
            rr, gg, bb = [round(x * 255) for x in m.diffuse_color[:3]]
            tgt = prim if (abs(rr - 60) < 12 and gg > 240 and bb < 12) else (sec_ if (rr > 240 and gg < 12 and abs(bb - 175) < 12) else None)
            nt = m.node_tree; bsdf = next((n for n in nt.nodes if n.type == 'BSDF_PRINCIPLED'), None)
            if not bsdf: continue
            bsdf.inputs['Metallic'].default_value = 0.0; bsdf.inputs['Roughness'].default_value = 0.65
            col = tgt if tgt else (rr, gg, bb)
            if col == (255, 255, 255): continue
            c4 = (col[0] / 255, col[1] / 255, col[2] / 255, 1); bc = bsdf.inputs['Base Color']
            if bc.is_linked:
                src = bc.links[0].from_socket
                mix = nt.nodes.new('ShaderNodeMix'); mix.data_type = 'RGBA'; mix.blend_type = 'MULTIPLY'
                mix.inputs['Factor'].default_value = 1.0
                nt.links.new(src, mix.inputs[6]); mix.inputs[7].default_value = c4; nt.links.new(mix.outputs[2], bc)
            else: bc.default_value = c4

def job_cars():
    cars, cols, combos = car_info()
    for nm in A.CARS:
        if NAMES and nm not in NAMES: continue
        clear(); info = cars.get(nm, {})
        wl = load(os.path.join(GTA, 'models', 'generic', 'wheels.dff'), os.path.join(GTA, 'models', 'misc.txd'), 'wheels')
        for o in wl: o.hide_render = True
        tmpl = next((o for o in wl if o.type == 'MESH' and info.get('wheel') and o.name.lower().startswith((info['wheel'] + '_l0').lower())), None)
        objs = load(*model(nm)); to_pivot(objs)
        if tmpl:
            for o in objs:
                n_ = o.name.lower()
                if n_.startswith('wheel_') and n_.endswith('_dummy'):
                    w_ = bpy.data.objects.new(n_ + '_w', tmpl.data); SC.collection.objects.link(w_)
                    w_.parent = o; w_.location = (0, 0, 0); w_.rotation_euler = (0, 0, math.pi if n_[6] == 'l' else 0)
                    s_ = info['wscale']; w_.scale = (s_, s_, s_)
        cc = combos.get(nm, [0, 0]); pi_ = A.CAR_COMBO.get(nm, 0) * 2
        cc = cc[pi_:pi_ + 2] if len(cc) >= pi_ + 2 else cc[:2]
        car_colours(cols[cc[0]] if cc[0] < len(cols) else (255, 255, 255), cols[cc[1]] if len(cc) > 1 and cc[1] < len(cols) else (255, 255, 255))
        fm = 11.0 if nm in ('enforcer', 'barracks') else 9.0          # big ones need a bigger frame (still 32 px/m)
        for k in range(16):
            face(k, 16); shot(os.path.join(OUT['m'], 'car_%s_d%02d.png' % (nm, k)), fm, round(fm * 32))
        log('car', nm, 'wheel', info.get('wheel'), 'colours', cc[:2])

def job_heli():
    clear(); objs = load(*model(A.HELI)); to_pivot(objs)
    for o in objs:
        n_ = o.name.lower()
        if o.type == 'MESH' and ('rotor' in n_ or 'moving' in n_): o.hide_render = True
    for k in range(8):
        face(k, 8); shot(os.path.join(OUT['r'], 'heli_d%02d.png' % k), 30.0, 960)
    log('heli')

def job_portraits():
    """head close-ups for the dialogue box: camera level with the face (export.py puts them on a dark background)"""
    for nm, mdl in A.PORTRAITS.items():
        objs = ped(mdl); pose(objs, anim('idle_stance'), 0.0); bpy.context.view_layer.update()
        head = next((o for o in objs if o.name.lower().split('.')[0] in ('shead', 'head')), None)
        hz = head.matrix_world.translation.z if head else 0.75
        old = (cam.location.copy(), cam.rotation_euler.copy())
        face(4, 8)                                                    # looking at the camera
        cam.rotation_euler = (math.radians(90), 0, math.radians(-90)); cam.location = (-10, 0, hz + 0.06)
        shot(os.path.join(OUT['p'], '%s.png' % nm), 0.42, 104)        # 2x, export.py scales to 52
        cam.location, cam.rotation_euler = old
        log('portrait', nm, 'head z', round(hz, 3))

JOBS = [('cars', job_cars), ('peds', job_peds), ('run', job_run), ('combat', job_combat), ('heli', job_heli), ('portraits', job_portraits)]
t0 = time.time(); bad = 0
for key, fn in JOBS:
    if key not in ONLY: continue
    try: fn()
    except SystemExit: raise
    except Exception:
        bad += 1; log('ERR', key, traceback.format_exc())
log('DONE' if not bad else 'FAILED (%d jobs)' % bad, round(time.time() - t0), 's')
if not bad and ONLY >= {'cars', 'peds', 'run', 'combat', 'heli', 'portraits'} and not NAMES:
    open(os.path.join(WORK, 'sprites', 'render_ok.txt'), 'w').write(time.ctime() + '\n')     # build.bat skips the render next time
if bad: sys.exit(1)
