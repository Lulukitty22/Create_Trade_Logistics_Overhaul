"""Bake Minecraft block states into render-ready quads + a texture array, from the game/mod jars.

Follows the vanilla model pipeline closely enough for a map:
  blockstate json (variants with property matching, or multipart with when/OR/AND)
  -> model (parent chain: textures merged, nearest 'elements' wins)
  -> elements (from/to cuboids, per-face uv/texture/rotation/cullface/tintindex, element rotation)
  -> variant x/y rotation (90 degree steps) and uvlock.

Output per block state (BakedBlock):
  kind       air / solid cube / water / glass-like cube / model (non-cube shape)
  quads      float32 [n, 24]: 4 xyz corners (block units 0..1), 4 uv pairs (0..1, v down),
             texture layer, tinted, cull direction (-1..5), shade
  faces      texture layer per direction for drawing it as a cube (full cubes; coarse LODs)
Directions everywhere: 0 +x east, 1 -x west, 2 +y up, 3 -y down, 4 +z south, 5 -z north.
"""
import io
import math
import struct

import numpy as np
from PIL import Image

from block_colors import AIR_NAMES, FIXED_FOLIAGE, NEVER_TINTED, WATER_NAMES, _split_id

KIND_AIR, KIND_SOLID, KIND_WATER, KIND_CAVE, KIND_GLASS, KIND_MODEL = 0, 1, 2, 3, 4, 5
FLAG_WATERLOGGED, FLAG_CUBE_AT_LOD = 1 << 6, 1 << 7        # low 6 bits: per-direction tint mask
DIRS = {'east': 0, 'west': 1, 'up': 2, 'down': 3, 'south': 4, 'north': 5}
DIR_VEC = np.array([[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]], float)
TEX = 16
WATERLOGGED_NAMES = ('minecraft:seagrass', 'minecraft:tall_seagrass', 'minecraft:kelp', 'minecraft:kelp_plant')
NO_RENDER_WORDS = ('sign', 'banner', 'skull', '_head', 'barrier', 'structure_void', 'moving_piston')
NO_RENDER_NAMES = ('minecraft:light',)


def _corners(f, t, face):
    """Face corners TL, BL, BR, TR as seen from outside (texture u right, v down), in 0..16 space."""
    (x0, y0, z0), (x1, y1, z1) = f, t
    return {
        'north': [(x1, y1, z0), (x1, y0, z0), (x0, y0, z0), (x0, y1, z0)],
        'south': [(x0, y1, z1), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1)],
        'east':  [(x1, y1, z1), (x1, y0, z1), (x1, y0, z0), (x1, y1, z0)],
        'west':  [(x0, y1, z0), (x0, y0, z0), (x0, y0, z1), (x0, y1, z1)],
        'up':    [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)],
        'down':  [(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)],
    }[face]


def _default_uv(f, t, face):
    (x0, y0, z0), (x1, y1, z1) = f, t
    return {
        'north': [16 - x1, 16 - y1, 16 - x0, 16 - y0], 'south': [x0, 16 - y1, x1, 16 - y0],
        'east': [16 - z1, 16 - y1, 16 - z0, 16 - y0], 'west': [z0, 16 - y1, z1, 16 - y0],
        'up': [x0, z0, x1, z1], 'down': [x0, 16 - z1, x1, 16 - z0],
    }[face]


def _project_uv(p, d):
    """uvlock: texture coords from final position, using the default projection for direction d."""
    x, y, z = p
    return [(16 - z, 16 - y), (z, 16 - y), (x, z), (x, 16 - z), (x, 16 - y), (16 - x, 16 - y)][d]


def _rot_matrix(axis, deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    if axis == 'x':
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == 'y':
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def _variant_matrix(xr, yr):
    """Blockstate rotation: x first, then y; both clockwise-positive like the game (north->east for y=90)."""
    return _rot_matrix('y', -yr) @ _rot_matrix('x', -xr)


def _dir_of(v):
    i = int(np.argmax(np.abs(v)))
    return [0, 2, 4][i] + (0 if v[i] > 0 else 1)


def _uv_rot(r, u, v):
    """Rotate texture coords about the tile centre by r quarter turns (same convention as the mesher)."""
    return [(u, v), (1 - v, u), (1 - u, 1 - v), (v, 1 - u)][r]


def _face_rotation(pts, uvs, d):
    """Quarter turns that map the default projection onto this quad's UVs (e.g. bark on sideways logs)."""
    proj = [tuple(c / 16.0 for c in _project_uv(p * 16, d)) for p in pts]
    best, err = 0, 1e9
    for r in range(4):
        e = sum((a - b) ** 2 + (c - f) ** 2 for (b, f), (a, c) in zip([_uv_rot(r, u, v) for u, v in proj], uvs))
        if e < err:
            best, err = r, e
    return best


class BakedBlock:
    __slots__ = ('kind', 'quads', 'faces', 'tint_mask', 'flags', 'tint_type', 'model', 'rot')

    def __init__(self, kind, quads=None, faces=None, tint_mask=0, flags=0, tint_type=None, rot=0):
        self.kind, self.quads, self.faces = kind, quads, faces or [0] * 6
        self.tint_mask, self.flags, self.tint_type, self.model = tint_mask, flags, tint_type, -1
        self.rot = rot   # 2 bits per direction: texture quarter turns for cube faces


class ModelBaker:
    def __init__(self, colorizer):
        self.bc = colorizer               # reuses its asset index, model resolution, texture averages
        self.a = colorizer.a
        self.tex_ids, self.tex_index = ['<missing>'], {'<missing>': 0}   # layer 0 = missing texture
        self.tex_fill = [False]
        self.models = []                  # list of float32 [n,24] arrays (append-only)
        self.cache = {}

    # ---------------------------------------------------------------- textures
    def layer(self, tex_id, fill=False):
        """Texture layer index for a texture id (append-only). fill=True: opaque version (leaves)."""
        if not tex_id or tex_id.startswith('#'):
            return 0
        key = (tex_id, fill)
        i = self.tex_index.get(key)
        if i is None:
            i = self.tex_index[key] = len(self.tex_ids)
            self.tex_ids.append(tex_id); self.tex_fill.append(fill)
        return i

    def _texture_rgba(self, tex_id, fill):
        if tex_id == '<missing>':
            return np.full((TEX, TEX, 4), (128, 128, 128, 255), np.uint8)
        ns, path = _split_id(tex_id)
        data = self.a.read(f'assets/{ns}/textures/{path}.png')
        if data is None:
            return np.full((TEX, TEX, 4), (255, 0, 255, 255), np.uint8)
        img = Image.open(io.BytesIO(data)).convert('RGBA')
        w, h = img.size
        if h > w:
            img = img.crop((0, 0, w, w))      # animated strip: first frame
        if img.size != (TEX, TEX):
            img = img.resize((TEX, TEX), Image.BOX if img.size[0] > TEX else Image.NEAREST)
        a = np.array(img)
        if fill:                               # fast-graphics style: holes become dark leaf colour
            alpha = a[..., 3:4] / 255.0
            avg = (a[..., :3] * alpha).sum((0, 1)) / max(alpha.sum(), 1)
            a[..., :3] = (a[..., :3] * alpha + avg * 0.55 * (1 - alpha)).astype(np.uint8)
            a[..., 3] = 255
        return a

    def atlas_bytes(self, start=0):
        return b''.join(self._texture_rgba(t, f).tobytes() for t, f in zip(self.tex_ids[start:], self.tex_fill[start:]))

    # ---------------------------------------------------------------- blockstates
    def _parts(self, name, props):
        ns, path = _split_id(name)
        bs = self.a.json(f'assets/{ns}/blockstates/{path}.json')
        if not bs:
            return None

        def first(v):
            v = v[0] if isinstance(v, list) and v else v
            return v if isinstance(v, dict) and 'model' in v else None

        def match(cond):
            for kv in filter(None, cond.split(',')):
                k, _, val = kv.partition('=')
                if str(props.get(k, '')) not in val.split('|'):
                    return False
            return True

        def when_ok(w):
            if 'OR' in w:
                return any(when_ok(x) for x in w['OR'])
            if 'AND' in w:
                return all(when_ok(x) for x in w['AND'])
            return all(str(props.get(k, '')).lower() in str(v).lower().split('|') for k, v in w.items())

        if 'variants' in bs:
            vs = bs['variants']
            for cond, v in vs.items():
                if match(cond):
                    return [first(v)]
            return [first(next(iter(vs.values()), None))]
        return [first(p.get('apply')) for p in bs.get('multipart', []) if 'when' not in p or when_ok(p['when'])]

    # ---------------------------------------------------------------- baking
    def bake(self, name, props):
        key = (name, tuple(sorted(props.items())))
        b = self.cache.get(key)
        if b is None:
            b = self.cache[key] = self._bake(name, props)
            if b.quads is not None and len(b.quads):
                b.model = len(self.models)
                self.models.append(b.quads)
        return b

    def _tint_type(self, name):
        if name in NEVER_TINTED:
            return None
        if name in FIXED_FOLIAGE:
            return FIXED_FOLIAGE[name]
        _, path = _split_id(name)
        return 'foliage' if ('leaves' in path or 'vine' in path) else 'grass'

    def _bake(self, name, props):
        _, path = _split_id(name)
        if name in AIR_NAMES:
            return BakedBlock(KIND_AIR)
        if name in WATER_NAMES:
            w = self.layer('minecraft:block/water_still', fill=True)
            return BakedBlock(KIND_WATER, faces=[w] * 6, tint_mask=0x3F, tint_type='water', flags=FLAG_WATERLOGGED)
        waterlogged = str(props.get('waterlogged', '')) == 'true' or name in WATERLOGGED_NAMES
        leaves = 'leaves' in path
        parts = self._parts(name, props) or []

        quads, particle, any_rot = [], None, False
        for part in parts:
            if not part:
                continue
            m = self.bc.resolve_model(part['model'])
            tex = m['textures']
            particle = particle or self.bc._deref(tex, tex.get('particle'))
            xr, yr = int(part.get('x', 0)) % 360, int(part.get('y', 0)) % 360
            vm = _variant_matrix(xr, yr)
            uvlock = bool(part.get('uvlock')) and (xr or yr)
            for el in m['elements']:
                f, t = el.get('from', [0, 0, 0]), el.get('to', [16, 16, 16])
                er = el.get('rotation')
                em, origin, scale = None, None, None
                if er and er.get('angle'):
                    any_rot = True
                    em = _rot_matrix(er.get('axis', 'y'), float(er['angle']))
                    origin = np.array(er.get('origin', [8, 8, 8]), float)
                    if er.get('rescale'):
                        k = 1 / math.cos(math.radians(abs(float(er['angle']))))
                        scale = np.array([1.0 if er.get('axis', 'y') == a else k for a in 'xyz'])
                for face, fd in (el.get('faces') or {}).items():
                    if face not in DIRS:
                        continue
                    tid = self.bc._deref(tex, fd.get('texture'))
                    if not tid or tid.startswith('#'):
                        continue
                    pts = np.array(_corners(f, t, face), float)
                    if em is not None:
                        rel = pts - origin
                        if scale is not None:
                            rel = rel * scale
                        pts = rel @ em.T + origin
                    pts = (pts - 8) @ vm.T + 8
                    uv = fd.get('uv') or _default_uv(f, t, face)
                    u0, v0, u1, v1 = uv
                    uvs = [(u0, v0), (u0, v1), (u1, v1), (u1, v0)]
                    r = (int(fd.get('rotation', 0)) // 90) % 4
                    uvs = uvs[r:] + uvs[:r]
                    n = np.cross(pts[1] - pts[0], pts[2] - pts[1])
                    nn = n / (np.linalg.norm(n) or 1)
                    d = _dir_of(nn)
                    if uvlock and np.max(np.abs(nn)) > 0.99:
                        uvs = [_project_uv(p, d) for p in pts]
                    cull = fd.get('cullface')
                    cull_dir = _dir_of(vm @ DIR_VEC[DIRS[cull]]) if cull in DIRS else -1
                    shade = 1.0 if el.get('shade') is False else float(
                        nn[0] ** 2 * 0.6 + nn[2] ** 2 * 0.8 + nn[1] ** 2 * (1.0 if nn[1] > 0 else 0.5))
                    area = float(np.linalg.norm(n))
                    quads.append((pts / 16.0, [(u / 16.0, v / 16.0) for u, v in uvs],
                                  self.layer(tid, leaves), 'tintindex' in fd, cull_dir, shade, d, area))

        # Blocks drawn by code (chests, beds, ...): a simple stand-in box, or nothing.
        if not quads:
            if any(w in path for w in NO_RENDER_WORDS) or name in NO_RENDER_NAMES or name.endswith(':air'):
                return BakedBlock(KIND_AIR)
            p = particle or f'{_split_id(name)[0]}:block/{path}'
            lay = self.layer(p)
            if any(w in path for w in ('chest', 'barrel', 'shulker')):
                return self._box(lay, (1, 0, 1), (15, 14, 15), waterlogged)
            if path.endswith('bed'):
                return self._box(lay, (0, 0, 0), (16, 9, 16), waterlogged)
            return BakedBlock(KIND_SOLID, faces=[lay] * 6, flags=FLAG_CUBE_AT_LOD)

        # Per-direction cube faces: the largest quad facing each direction (+ its texture rotation).
        faces, tint_mask, best, rot = [self.layer(particle)] * 6, 0, [0.0] * 6, 0
        for pts, uvs, lay, tinted, cull, shade, d, area in quads:
            if area > best[d] + 1e-6:
                best[d] = area; faces[d] = lay
                tint_mask = (tint_mask | (1 << d)) if tinted else (tint_mask & ~(1 << d))
                rot = (rot & ~(3 << (2 * d))) | (_face_rotation(pts, uvs, d) << (2 * d))
        tint_type = self._tint_type(name) if any(q[3] for q in quads) else None
        if tint_type is None:
            tint_mask = 0

        full = not any_rot and all(abs(b - 256.0) < 1e-3 for b in best) and self._all_full(parts)
        flags = tint_mask | (FLAG_WATERLOGGED if waterlogged else 0)
        if full:
            avg = self.bc.texture_avg(self.tex_ids[faces[2]])
            cover = avg[1] if avg else 1.0
            kind = KIND_GLASS if (cover < 0.95 and not leaves) else KIND_SOLID
            return BakedBlock(kind, faces=faces, tint_mask=tint_mask, flags=flags | FLAG_CUBE_AT_LOD,
                              tint_type=tint_type, rot=rot)
        volume = self._volume(parts)
        if volume >= 0.2:
            flags |= FLAG_CUBE_AT_LOD
        arr = np.array([[*np.ravel(p), *np.ravel(u), lay, float(ti), float(c), s]
                        for p, u, lay, ti, c, s, d, a in quads], np.float32)
        return BakedBlock(KIND_MODEL, quads=arr, faces=faces, tint_mask=tint_mask, flags=flags,
                          tint_type=tint_type, rot=rot)

    def _box(self, lay, f, t, waterlogged):
        quads = []
        for face in DIRS:
            pts = np.array(_corners(f, t, face), float) / 16.0
            u0, v0, u1, v1 = _default_uv(f, t, face)
            uvs = [(u0 / 16, v0 / 16), (u0 / 16, v1 / 16), (u1 / 16, v1 / 16), (u1 / 16, v0 / 16)]
            d = DIRS[face]
            shade = [0.6, 0.6, 1.0, 0.5, 0.8, 0.8][d]
            quads.append([*pts.ravel(), *np.ravel(uvs), lay, 0.0, float(d) if face == 'down' else -1.0, shade])
        return BakedBlock(KIND_MODEL, quads=np.array(quads, np.float32), faces=[lay] * 6,
                          flags=FLAG_CUBE_AT_LOD | (FLAG_WATERLOGGED if waterlogged else 0))

    def _all_full(self, parts):
        for part in parts:
            if part:
                for el in self.bc.resolve_model(part['model'])['elements']:
                    if el.get('from') != [0, 0, 0] or el.get('to') != [16, 16, 16]:
                        return False
        return True

    def _volume(self, parts):
        """Filled fraction of the block, counting only real boxes (plane elements like torch/cross
        quads have <4 faces and would overcount)."""
        v = 0.0
        for part in parts:
            if part:
                for el in self.bc.resolve_model(part['model'])['elements']:
                    if len(el.get('faces') or {}) < 4:
                        continue
                    f, t = el.get('from', [0, 0, 0]), el.get('to', [16, 16, 16])
                    v += abs((t[0] - f[0]) * (t[1] - f[1]) * (t[2] - f[2])) / 4096.0
        return min(v, 1.0)

    def models_blob(self, start=0):
        """'VXM1', u32 first model id, u32 count, u32 quad offsets[count+1], float32 quads."""
        ms = self.models[start:]
        offs = np.zeros(len(ms) + 1, np.uint32)
        offs[1:] = np.cumsum([len(m) for m in ms])
        data = np.concatenate(ms).astype('<f4') if ms else np.zeros((0, 24), '<f4')
        return struct.pack('<4sII', b'VXM1', start, len(ms)) + offs.astype('<u4').tobytes() + data.tobytes()
