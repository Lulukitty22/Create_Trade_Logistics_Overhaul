"""Feasibility proof: render a shaded top-down map of each Voxy LOD level straight from the DB."""
import sys
import zlib

import numpy as np
from PIL import Image

from voxy_reader import VoxyStorage, decode_key

SKIP = ('air', 'short_grass', 'tall_grass', 'fern', 'vine', 'flower', 'torch', 'button', 'pressure_plate')
KEYWORD_COLORS = [  # first match wins
    ('water', (63, 118, 228)), ('lava', (230, 110, 20)), ('leaves', (58, 110, 40)),
    ('grass_block', (110, 160, 60)), ('moss', (90, 130, 45)), ('snow', (240, 245, 250)),
    ('ice', (160, 190, 240)), ('sand', (219, 207, 160)), ('gravel', (130, 125, 120)),
    ('clay', (160, 166, 180)), ('podzol', (100, 70, 30)), ('mud', (60, 55, 50)),
    ('dirt', (134, 96, 67)), ('log', (90, 70, 45)), ('wood', (90, 70, 45)), ('planks', (160, 130, 80)),
    ('deepslate', (80, 80, 85)), ('ore', (120, 120, 120)), ('tuff', (108, 109, 102)),
    ('stone', (125, 125, 125)), ('andesite', (136, 136, 137)), ('diorite', (188, 188, 188)),
    ('granite', (149, 103, 85)), ('bedrock', (50, 50, 50)),
]


def color_for(name):
    for kw, c in KEYWORD_COLORS:
        if kw in name:
            return c
    h = zlib.crc32(name.encode())
    return (80 + (h & 0x7F), 80 + ((h >> 8) & 0x7F), 80 + ((h >> 16) & 0x7F))


def main(path, out):
    db = VoxyStorage(path)
    blocks, _ = db.load_mappings()
    max_id = max(blocks) + 1
    skip = np.zeros(max_id, bool)
    pal = np.zeros((max_id, 3), np.uint8)
    for bid, (name, _) in blocks.items():
        skip[bid] = any(s in name for s in SKIP)
        pal[bid] = color_for(name)

    sections = {}
    for key, _, data in db.iter_sections():
        sections.setdefault(decode_key(key)[0], []).append((key, data))

    panels = []
    for lvl in sorted(sections):
        secs = sections[lvl]
        xs = [decode_key(k)[1] for k, _ in secs]; zs = [decode_key(k)[3] for k, _ in secs]
        x0, z0 = min(xs), min(zs)
        W, H = (max(xs) - x0 + 1) * 32, (max(zs) - z0 + 1) * 32
        height = np.full((H, W), -1e9); bid_top = np.zeros((H, W), np.int64)
        for key, data in secs:
            _, sx, sy, sz = decode_key(key)
            ids = ((data >> 27) & 0xFFFFF).astype(np.int64).reshape(32, 32, 32)  # [y, z, x]
            solid = ~skip[ids]
            has = solid.any(axis=0)
            top = 31 - np.argmax(solid[::-1], axis=0)
            wy = (sy * 32 + top).astype(float) * (1 << lvl)
            top_id = np.take_along_axis(ids, top[None], axis=0)[0]
            ox, oz = (sx - x0) * 32, (sz - z0) * 32
            h = height[oz:oz + 32, ox:ox + 32]; b = bid_top[oz:oz + 32, ox:ox + 32]
            upd = has & (wy > h)
            h[upd] = wy[upd]; b[upd] = top_id[upd]
        # simple hillshade from height gradient
        hh = np.where(height < -1e8, np.nan, height)
        gz, gx = np.gradient(np.nan_to_num(hh, nan=np.nanmin(hh)) / (1 << lvl))
        shade = np.clip(1.0 + (-gx - gz) * 0.12, 0.55, 1.35)
        img = pal[bid_top].astype(float) * shade[..., None]
        img[np.isnan(hh)] = (20, 20, 24)
        img = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
        img = img.resize((576, 576), Image.NEAREST)  # same world footprint scale per panel-ish
        panels.append((lvl, img, len(secs)))
        print(f'LOD {lvl}: {len(secs)} sections -> {W}x{H} px, {1 << lvl} block(s)/px')

    pad = 8
    sheet = Image.new('RGB', (len(panels) * (576 + pad) + pad, 576 + 2 * pad), (12, 12, 14))
    for i, (lvl, img, _) in enumerate(panels):
        sheet.paste(img, (pad + i * (576 + pad), pad))
    sheet.save(out)
    print('wrote', out)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
