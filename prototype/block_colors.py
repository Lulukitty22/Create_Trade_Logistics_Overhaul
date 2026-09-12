"""Derive per-block map colours from the real game assets (vanilla jar + mod jars).

For each block state: blockstate json -> model (with parent chain) -> the textures used by its
'up' and side faces -> alpha-weighted average colour. Tinted faces (grass, foliage, water) are
multiplied by the biome's colour, computed like vanilla does from the biome json + colormaps.
"""
import io
import json
import os
import zipfile

import numpy as np
from PIL import Image

KIND_AIR, KIND_SOLID, KIND_WATER = 0, 1, 2

AIR_NAMES = ('minecraft:air', 'minecraft:cave_air', 'minecraft:void_air')
WATER_NAMES = ('minecraft:water', 'minecraft:bubble_column')
# Small/thin decorations we don't draw as cubes (yet). Glass is kept despite being see-through.
DECOR_WORDS = ('torch', 'rail', 'button', 'pressure_plate', 'lever', 'sign', 'banner', 'redstone_wire',
               'tripwire', 'ladder', 'vine', 'lichen', 'carpet', 'lantern', 'chain', 'petals', 'sapling',
               'flower_pot', 'candle', 'cobweb', 'dead_bush', 'seagrass', 'kelp', 'coral_fan', 'bars')
FIXED_FOLIAGE = {'minecraft:birch_leaves': (0x80, 0xA7, 0x55), 'minecraft:spruce_leaves': (0x61, 0x99, 0x61),
                 'minecraft:mangrove_leaves': (0x92, 0xC6, 0x48), 'minecraft:lily_pad': (0x20, 0x80, 0x30)}
# Models say tintindex, but the game registers no colour handler for these.
NEVER_TINTED = ('minecraft:cherry_leaves', 'minecraft:azalea_leaves', 'minecraft:flowering_azalea_leaves')
DEFAULT_BIOME = {'temperature': 0.8, 'downfall': 0.4, 'effects': {'water_color': 0x3F76E4}}


class AssetIndex:
    """Looks up asset paths across the vanilla jar and every mod jar (mods win over vanilla).

    Also indexes jar-in-jar mods (META-INF/jarjar/*.jar, META-INF/jars/*.jar), e.g. the sub-mods
    bundled inside create-aeronautics-bundled.
    """

    def __init__(self, jar_paths):
        self.where = {}   # asset path -> zip id
        self.zips = {}    # zip id -> ZipFile
        for jp in jar_paths:  # later jars override earlier ones
            try:
                self._index(jp, zipfile.ZipFile(jp))
            except (zipfile.BadZipFile, OSError):
                pass

    def _index(self, zid, z, depth=0):
        self.zips[zid] = z
        for n in z.namelist():
            if n.startswith(('assets/', 'data/')) and n.endswith(('.json', '.png')):
                self.where[n] = zid
            elif depth == 0 and n.startswith(('META-INF/jarjar/', 'META-INF/jars/')) and n.endswith('.jar'):
                try:
                    self._index(f'{zid}!{n}', zipfile.ZipFile(io.BytesIO(z.read(n))), depth + 1)
                except zipfile.BadZipFile:
                    pass

    def read(self, path):
        zid = self.where.get(path)
        return None if zid is None else self.zips[zid].read(path)

    def json(self, path):
        data = self.read(path)
        if data is None:
            return None
        try:
            return json.loads(data.decode('utf-8-sig'))
        except (ValueError, UnicodeDecodeError):
            return None


def _split_id(rid, default_ns='minecraft'):
    ns, _, path = rid.partition(':') if ':' in rid else (default_ns, '', rid)
    return ns, path


class BlockColorizer:
    def __init__(self, assets: AssetIndex):
        self.a = assets
        self._tex_cache = {}
        self._model_cache = {}
        self.grass_map = self._colormap('grass')
        self.foliage_map = self._colormap('foliage')

    # ---- textures ----
    def _colormap(self, name):
        data = self.a.read(f'assets/minecraft/textures/colormap/{name}.png')
        return np.asarray(Image.open(io.BytesIO(data)).convert('RGB')) if data else None

    def texture_avg(self, tex_id):
        """Returns (rgb float array, opaque coverage 0..1) or None."""
        if tex_id in self._tex_cache:
            return self._tex_cache[tex_id]
        ns, path = _split_id(tex_id)
        data = self.a.read(f'assets/{ns}/textures/{path}.png')
        res = None
        if data:
            img = np.asarray(Image.open(io.BytesIO(data)).convert('RGBA')).astype(np.float64)
            h, w = img.shape[:2]
            if h > w:  # animated strip: first frame
                img = img[:w]
            alpha = img[..., 3] / 255.0
            if alpha.sum() > 0:
                rgb = (img[..., :3] * alpha[..., None]).sum((0, 1)) / alpha.sum()
                res = (rgb, float((alpha > 0.5).mean()))
        self._tex_cache[tex_id] = res
        return res

    # ---- models ----
    def resolve_model(self, model_id):
        """Returns dict(textures=..., elements=..., parents=[...]) with the parent chain merged."""
        if model_id in self._model_cache:
            return self._model_cache[model_id]
        textures, elements, parents = {}, None, []
        cur, depth = model_id, 0
        while cur and depth < 16:
            ns, path = _split_id(cur)
            parents.append(f'{ns}:{path}')
            m = self.a.json(f'assets/{ns}/models/{path}.json')
            if m is None:
                break
            for k, v in (m.get('textures') or {}).items():
                textures.setdefault(k, v)
            if elements is None and m.get('elements'):
                elements = m['elements']
            cur, depth = m.get('parent'), depth + 1
        res = {'textures': textures, 'elements': elements or [], 'parents': parents}
        self._model_cache[model_id] = res
        return res

    @staticmethod
    def _deref(textures, ref, depth=0):
        while isinstance(ref, str) and ref.startswith('#') and depth < 10:
            ref, depth = textures.get(ref[1:]), depth + 1
        return ref

    def pick_model(self, name, props):
        ns, path = _split_id(name)
        bs = self.a.json(f'assets/{ns}/blockstates/{path}.json')
        if not bs:
            return None

        def first_model(v):
            if isinstance(v, list):
                v = v[0] if v else None
            return v.get('model') if isinstance(v, dict) else None

        def matches(cond):
            for kv in filter(None, cond.split(',')):
                k, _, val = kv.partition('=')
                if str(props.get(k, '')) != val:
                    return False
            return True

        if 'variants' in bs:
            variants = bs['variants']
            for cond, v in variants.items():
                if matches(cond):
                    return first_model(v)
            return first_model(next(iter(variants.values()), None))
        for part in bs.get('multipart', []):
            if 'when' not in part:
                return first_model(part.get('apply'))
        parts = bs.get('multipart', [])
        return first_model(parts[0].get('apply')) if parts else None

    # ---- biomes ----
    def biome(self, biome_id):
        ns, path = _split_id(biome_id)
        return self.a.json(f'data/{ns}/worldgen/biome/{path}.json') or DEFAULT_BIOME

    def biome_tints(self, biome_id):
        """Returns dict of grass/foliage/water RGB tuples for a biome, like vanilla computes them."""
        b = self.biome(biome_id)
        eff = b.get('effects', {})
        t = min(max(float(b.get('temperature', 0.8)), 0.0), 1.0)
        d = min(max(float(b.get('downfall', 0.4)), 0.0), 1.0) * t
        x, y = int((1 - t) * 255), int((1 - d) * 255)

        def rgb(c):
            return ((c >> 16) & 255, (c >> 8) & 255, c & 255)

        grass = rgb(eff['grass_color']) if 'grass_color' in eff else tuple(int(v) for v in self.grass_map[y, x])
        foliage = rgb(eff['foliage_color']) if 'foliage_color' in eff else tuple(int(v) for v in self.foliage_map[y, x])
        mod = eff.get('grass_color_modifier')
        if mod == 'dark_forest':
            g = (grass[0] << 16) | (grass[1] << 8) | grass[2]
            grass = rgb(((g & 0xFEFEFE) + 0x28340A) >> 1)
        elif mod == 'swamp':
            grass = (0x6A, 0x70, 0x39)
        return {'grass': grass, 'foliage': foliage, 'water': rgb(eff.get('water_color', 0x3F76E4))}

    # ---- blocks ----
    def block_info(self, name, props):
        """Classifies a block state and returns its untinted face colours.

        Returns dict(kind, top, side, tint) where tint is None|'grass'|'foliage'|'water'|(r,g,b)
        and applies to the faces flagged in tint_top / tint_side.
        """
        if name in AIR_NAMES:
            return {'kind': KIND_AIR}
        if name in WATER_NAMES:
            avg = self.texture_avg('minecraft:block/water_still')
            base = avg[0] if avg else np.array([180, 180, 180.0])
            return {'kind': KIND_WATER, 'top': base, 'side': base, 'tint': 'water', 'tint_top': True, 'tint_side': True}
        _, path = _split_id(name)

        model_id = self.pick_model(name, props)
        m = self.resolve_model(model_id) if model_id else {'textures': {}, 'elements': [], 'parents': []}
        tex = m['textures']
        is_cross = any('cross' in p for p in m['parents'])

        top_ref = side_ref = None
        tint_top = tint_side = False
        for el in m['elements']:
            faces = el.get('faces', {})
            if top_ref is None and 'up' in faces:
                top_ref = faces['up'].get('texture'); tint_top = 'tintindex' in faces['up']
            for s in ('north', 'south', 'east', 'west'):
                if side_ref is None and s in faces:
                    side_ref = faces[s].get('texture'); tint_side = 'tintindex' in faces[s]
        if is_cross:
            tint_top = tint_side = any('tint' in p for p in m['parents'])
        particle = tex.get('particle')
        top_tex = self._deref(tex, top_ref) or self._deref(tex, particle)
        side_tex = self._deref(tex, side_ref) or top_tex
        top = self.texture_avg(top_tex) if top_tex else None
        side = self.texture_avg(side_tex) if side_tex else None
        if top is None and side is None:
            # Unknown model (custom loader etc.): try a same-named texture, else a neutral grey.
            ns, p = _split_id(name)
            top = side = self.texture_avg(f'{ns}:block/{p}') or (np.array([128, 128, 128.0]), 1.0)
        top = top or side
        side = side or top

        decor = is_cross or any(w in path for w in DECOR_WORDS) or (top[1] < 0.3 and 'glass' not in path)
        tint = None
        if name in NEVER_TINTED:
            tint_top = tint_side = False
        if tint_top or tint_side:
            if name in FIXED_FOLIAGE:
                tint = FIXED_FOLIAGE[name]
            elif 'leaves' in path or 'vine' in path:
                tint = 'foliage'
            else:
                tint = 'grass'
        return {'kind': KIND_AIR if decor else KIND_SOLID, 'decor': decor, 'top': top[0], 'side': side[0],
                'tint': tint, 'tint_top': tint_top, 'tint_side': tint_side}

    def face_colors(self, info, biome_tints):
        """Final (top_rgb, side_rgb) uint8 tuples for a block_info in a given biome."""
        def apply(base, on):
            if not on or info['tint'] is None:
                return base
            t = info['tint']
            t = biome_tints[t] if isinstance(t, str) else t
            return base * np.array(t) / 255.0
        top = apply(info['top'], info.get('tint_top'))
        side = apply(info['side'], info.get('tint_side'))
        return (tuple(int(v) for v in np.clip(top, 0, 255)), tuple(int(v) for v in np.clip(side, 0, 255)))


def default_jars(profile_dir, version_jar):
    mods = os.path.join(profile_dir, 'mods')
    jars = [version_jar] + sorted(os.path.join(mods, f) for f in os.listdir(mods) if f.endswith('.jar'))
    return jars
