"""Feasibility survey: can we render real textures + real block models from the game/mod jars?

For every block state Voxy has seen, resolve blockstate -> model(s) -> elements/textures and classify:
  cube      single full 16^3 element (textured cube is exact)
  shaped    JSON elements but not a full cube (slabs, stairs, plants, torches, rails, fences...)
  multipart built from multipart parts (fences, walls, redstone...) - JSON, needs property matching
  custom    no usable elements: custom model loader / block-entity renderer / missing model
Also collects every texture referenced and its size/animation, to size a texture array.
"""
import io
import sys
from collections import Counter, defaultdict

import numpy as np
from PIL import Image

from block_colors import AssetIndex, BlockColorizer, _split_id, default_jars
from serve import PROFILE, VERSION_JAR
from voxy_reader import VoxyStorage

WORLDS = {
    'small': r'.cache\Create_Logistics_World\snapshot',
    'big': r'.cache\New_World__2_\snapshot',
}


def model_refs(bc, name, props):
    """All model ids a block state uses (variants: one; multipart: every part that applies)."""
    ns, path = _split_id(name)
    bs = bc.a.json(f'assets/{ns}/blockstates/{path}.json')
    if not bs:
        return None, 'no-blockstate'

    def first(v):
        v = v[0] if isinstance(v, list) and v else v
        return v if isinstance(v, dict) else None

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
        return all(str(props.get(k, '')) in str(v).split('|') for k, v in w.items())

    if 'variants' in bs:
        for cond, v in bs['variants'].items():
            if match(cond):
                return [first(v)], 'variants'
        return [first(next(iter(bs['variants'].values()), None))], 'variants'
    parts = [first(p.get('apply')) for p in bs.get('multipart', []) if 'when' not in p or when_ok(p['when'])]
    return parts, 'multipart'


def classify(bc, name, props):
    refs, kind = model_refs(bc, name, props)
    if refs is None:
        return 'custom', 'no blockstate json', set()
    textures, n_el, n_full = set(), 0, 0
    for r in refs:
        if not r or 'model' not in r:
            continue
        m = bc.resolve_model(r['model'])
        tex = m['textures']
        for el in m['elements']:
            n_el += 1
            if el.get('from') == [0, 0, 0] and el.get('to') == [16, 16, 16]:
                n_full += 1
            for f in el.get('faces', {}).values():
                t = bc._deref(tex, f.get('texture'))
                if t and not t.startswith('#'):
                    textures.add(t)
        if not m['elements']:
            p = bc._deref(tex, tex.get('particle'))
            if p:
                textures.add(p)
    if n_el == 0:
        return 'custom', 'no elements (custom loader / block entity)', textures
    if kind == 'multipart':
        return 'multipart', f'{len(refs)} parts', textures
    # full cubes, including stacked overlays like grass_block's tinted side layer
    return ('cube' if n_full == n_el else 'shaped'), f'{n_el} elements', textures


def main():
    assets = AssetIndex(default_jars(PROFILE, VERSION_JAR))
    bc = BlockColorizer(assets)
    for wname, path in WORLDS.items():
        db = VoxyStorage(path)
        blocks, _ = db.load_mappings()
        # voxel frequency at LOD 0 (small world only; big world just counts states)
        freq = Counter()
        if wname == 'small':
            for key, _, idx, lut in db.iter_sections_raw():
                if key >> 60:
                    continue
                bids = ((lut >> 27) & 0xFFFFF).astype(np.int64)
                cnt = np.bincount(idx, minlength=len(lut))
                for b, c in zip(bids, cnt):
                    freq[int(b)] += int(c)
        cats, cat_vox, examples = Counter(), Counter(), defaultdict(list)
        all_tex = set()
        for bid, (name, props) in blocks.items():
            if name.endswith(':air'):
                continue
            cat, why, tex = classify(bc, name, props)
            cats[cat] += 1; cat_vox[cat] += freq.get(bid, 0); all_tex |= tex
            if len(examples[cat]) < 400:
                examples[cat].append((freq.get(bid, 0), name, why))
        total_vox = sum(cat_vox.values()) or 1
        print(f'\n=== {wname} world: {len(blocks)} block states ===')
        for c in ('cube', 'shaped', 'multipart', 'custom'):
            line = f'  {c:9s} {cats[c]:5d} states'
            if wname == 'small':
                line += f'   {100 * cat_vox[c] / total_vox:6.2f}% of non-air voxels'
            print(line)
        # textures
        sizes, anim, missing = Counter(), 0, 0
        for t in all_tex:
            ns, p = _split_id(t)
            data = assets.read(f'assets/{ns}/textures/{p}.png')
            if not data:
                missing += 1; continue
            w, h = Image.open(io.BytesIO(data)).size
            sizes[w] += 1; anim += h > w
        print(f'  textures: {len(all_tex)} distinct, widths {dict(sizes)}, animated {anim}, missing {missing}')
        for c in ('shaped', 'multipart', 'custom'):
            ex = sorted({(f, n, w) for f, n, w in examples[c]}, reverse=True)
            seen, out = set(), []
            for f, n, w in ex:
                if n not in seen:
                    seen.add(n); out.append(f'{n} ({w})')
                if len(out) >= 14:
                    break
            print(f'  {c} e.g.: ' + ', '.join(out))


if __name__ == '__main__':
    sys.exit(main())
