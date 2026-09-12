"""Streaming terrain store over a Voxy database: on-demand section conversion + live WAL updates.

- Opens an incremental snapshot copy of the Voxy RocksDB (SSTs are immutable, so only new files and
  the WAL are re-copied). The game's own files are never opened by RocksDB.
- Converts sections on request into the web wire format (global palette ids, cave air marked),
  with an LRU cache of encoded records.
- Tails the live WAL: new/changed sections go into an overlay and are reported as changed keys,
  including sections below them whose cave marking depends on the column above.
"""
import os
import re
import shutil
import struct
import threading
import time
from collections import OrderedDict, defaultdict

import numpy as np
import zstandard
from rocksdict import AccessType, Options, Rdict

from block_models import (FLAG_CUBE_AT_LOD, KIND_AIR, KIND_CAVE, KIND_GLASS, KIND_MODEL, KIND_SOLID, KIND_WATER,
                          ModelBaker)
from voxy_reader import decode_key, read_nbt_gz
from voxy_wal import WalTailer, open_shared, parse_batch, parse_records

KIND_CAVE_AIR = KIND_CAVE
CAVE_COLOR = (26, 24, 30)
GID_AIR, GID_CAVE = 0, 1
ZSTD_MAGIC, GZIP_MAGIC = b'\x28\xb5\x2f\xfd', b'\x1f\x8b'
MAX_RAW = 16 + 65536 + 65536 * 8
MISSING = 0xFFFF
_Z = np.zeros((1, 32, 32), bool)


def make_key(l, x, y, z):
    return (l << 60) | ((y & 0xFF) << 52) | ((z & 0xFFFFFF) << 28) | ((x & 0xFFFFFF) << 4)


def sync_snapshot(src, dst):
    """Incrementally mirror a live RocksDB dir (minus LOCK) into dst. Returns bytes copied."""
    os.makedirs(dst, exist_ok=True)
    names = {n for n in os.listdir(src) if n != 'LOCK' and os.path.isfile(os.path.join(src, n))}
    for n in os.listdir(dst):
        if n not in names and n != 'LOCK':
            os.remove(os.path.join(dst, n))
    copied = 0
    for n in sorted(names, key=lambda n: (n.endswith('.log'), n)):   # SSTs first, logs last
        s, d = os.path.join(src, n), os.path.join(dst, n)
        if n.endswith('.sst') and os.path.exists(d) and os.path.getsize(d) == os.path.getsize(s):
            continue
        with open_shared(s) as fi, open(d, 'wb') as fo:
            shutil.copyfileobj(fi, fo, 1 << 20)
        copied += os.path.getsize(d)
    open(os.path.join(dst, 'LOCK'), 'wb').close()
    return copied


def encode_record(l, x, y, z, g=None):
    """i32 l,x,y,z | u16 palLen (0 air, 0xFFFF missing) | u16 flags | u16 pal[] (4-aligned) | indices."""
    if g is None:
        return struct.pack('<iiiiHH', l, x, y, z, MISSING, 0)
    pal, inv = np.unique(g, return_inverse=True)
    if len(pal) == 1 and pal[0] == GID_AIR:
        return struct.pack('<iiiiHH', l, x, y, z, 0, 0)
    head = struct.pack('<iiiiHH', l, x, y, z, len(pal), 0 if len(pal) <= 256 else 1)
    palb = pal.astype('<u2').tobytes()
    palb += b'\0' * (-len(palb) % 4)
    if len(pal) == 1:
        return head + palb
    return head + palb + inv.astype(np.uint8 if len(pal) <= 256 else '<u2').tobytes()


class TerrainStore:
    def __init__(self, storage_dir, cache_dir, colorizer, cache_bytes=300 << 20):
        self.storage_dir, self.cache_dir, self.bc = storage_dir, cache_dir, colorizer
        self.mb = ModelBaker(colorizer)   # append-only textures/models, stable across resyncs
        self._assets = {}
        self.cache_budget = cache_bytes
        self.lock = threading.RLock()
        self.dctx = zstandard.ZstdDecompressor()
        self.db = None
        self.version = 0
        self.snapshot_time = self.last_change = None
        self.stats = {}

    # ------------------------------------------------------------------ open / snapshot
    def open(self):
        with self.lock:
            t0 = time.time()
            snap = os.path.join(self.cache_dir, 'snapshot')
            if self.db is not None:
                self.db.close(); self.db = None
            last = None
            for _ in range(4):   # the game may rotate/compact files mid-copy; just retry
                try:
                    copied = sync_snapshot(self.storage_dir, snap)
                    opts = Options(raw_mode=True)
                    cfs = Rdict.list_cf(snap, opts)
                    self.db = Rdict(snap, options=opts, column_families={c: Options(raw_mode=True) for c in cfs},
                                    access_type=AccessType.read_only())
                    break
                except Exception as e:  # noqa: BLE001 - any copy/open failure => retry
                    last = e; time.sleep(0.5)
            else:
                raise last
            self.snapshot_time = time.time()
            self.sections_cf = self.db.get_column_family('world_sections')
            self.mappings_cf = self.db.get_column_family('id_mappings')
            t1 = time.time()

            self.overlay = {}                   # key -> compressed value (None = deleted)
            self.records = OrderedDict(); self.records_bytes = 0
            self.colmasks = {}                  # key -> [z,x] bool: anything drawn in that column
            self.index = defaultdict(set)       # lvl -> {(x,y,z)}
            self.columns = defaultdict(set)     # (lvl,x,z) -> {y}
            for k in self.sections_cf.keys():
                self._index_add(struct.unpack('>Q', k)[0])
            t2 = time.time()

            self.blocks, self.biomes = {}, {}
            for k, v in self.mappings_cf.items():
                self._read_mapping(k, v)
            self._build_palette()

            # Start tailing the live WAL right where the copied WAL's last complete record ends.
            self.tailer = WalTailer(self.storage_dir)
            logs = sorted(int(n[:-4]) for n in os.listdir(snap) if re.fullmatch(r'\d+\.log', n))
            if logs:
                with open(os.path.join(snap, f'{logs[-1]:06d}.log'), 'rb') as f:
                    _, resume = parse_records(f.read(), 0, 0)
                self.tailer.start_at(logs[-1], resume)
            self.version += 1
            self.stats = {
                'copiedMB': round(copied / 1e6, 1), 'copySeconds': round(t1 - t0, 2),
                'indexSeconds': round(t2 - t1, 2), 'openSeconds': round(time.time() - t0, 2),
                'sections': {str(l): len(s) for l, s in sorted(self.index.items())},
            }
            return self.stats

    def _index_add(self, key):
        l, x, y, z = decode_key(key)
        self.index[l].add((x, y, z)); self.columns[(l, x, z)].add(y)

    def _index_remove(self, key):
        l, x, y, z = decode_key(key)
        self.index[l].discard((x, y, z)); self.columns[(l, x, z)].discard(y)

    # ------------------------------------------------------------------ palette
    def _read_mapping(self, k, v):
        kid = struct.unpack('>i', k)[0]
        typ, idx = (kid >> 30) & 3, kid & 0x3FFFFFFF
        nbt = read_nbt_gz(v)
        if typ == 1:
            bs = nbt.get('block_state', {})
            self.blocks[idx] = (bs.get('Name', '?'), bs.get('Properties', {}))
            return 'block', idx
        if typ == 2:
            self.biomes[idx] = nbt.get('biome_id', '?')
            return 'biome', idx
        return None, None

    def _build_palette(self):
        self.blocks.setdefault(0, ('minecraft:air', {}))
        self.pal = {'kind': [KIND_AIR, KIND_CAVE_AIR], 'top': [0, 0, 0, *CAVE_COLOR], 'side': [0, 0, 0, *CAVE_COLOR],
                    'block': [-1, -1], 'biome': [-1, -1],
                    'faces': [0] * 12, 'tint': [255] * 6, 'flags': [0, 0], 'model': [-1, -1], 'rot': [0, 0]}
        self.names, self.name_idx = [], {}
        self.gid_of = {}                 # (block id, biome id or -1) -> gid
        self.infos, self.tints, self.baked = {}, {}, {}
        self.tinted = set()
        for bio in sorted(self.biomes):
            self.tints[bio] = self.bc.biome_tints(self.biomes[bio])
        self.default_tint = self.bc.biome_tints('minecraft:plains')
        for bid in sorted(self.blocks):
            self._add_block(bid)
        self._rebuild_luts()

    def _add_entry(self, bid, bio):
        info, baked = self.infos[bid], self.baked[bid]
        tints = self.tints.get(bio, self.default_tint)
        top, side = self.bc.face_colors(info, tints) if 'top' in info else ((128, 128, 128), (128, 128, 128))
        t = baked.tint_type
        tint = (255, 255, 255) if t is None else tints[t] if isinstance(t, str) else t
        name = self.blocks[bid][0]
        if name not in self.name_idx:
            self.name_idx[name] = len(self.names); self.names.append(name)
        g = len(self.pal['kind'])
        self.gid_of[(bid, bio)] = g
        p = self.pal
        p['kind'].append(baked.kind); p['top'].extend(top); p['side'].extend(side)
        p['block'].append(self.name_idx[name]); p['biome'].append(bio)
        p['faces'].extend(baked.faces); p['tint'].extend(tint); p['flags'].append(baked.flags); p['model'].append(baked.model)
        p['rot'].append(baked.rot)

    def _add_block(self, bid):
        name, props = self.blocks[bid]
        info = self.infos[bid] = self.bc.block_info(name, props)
        baked = self.baked[bid] = self.mb.bake(name, props)
        if baked.kind == KIND_AIR:
            self.gid_of[(bid, -1)] = GID_AIR
        elif baked.tint_type is not None:
            self.tinted.add(bid)
            self._add_entry(bid, -1)                 # fallback for unknown biomes
            for bio in sorted(self.biomes):
                self._add_entry(bid, bio)
        else:
            self._add_entry(bid, -1)

    def _add_biome(self, bio):
        self.tints[bio] = self.bc.biome_tints(self.biomes[bio])
        for bid in sorted(self.tinted):
            self._add_entry(bid, bio)

    def _rebuild_luts(self):
        nb, nbio = max(self.blocks) + 1, max(self.biomes, default=0) + 1
        self.base_gid = np.zeros(nb, np.int32)
        self.tint_row = np.full(nb, -1, np.int32)
        rows = sorted(self.tinted)
        # last column = fallback for biome ids we have no mapping for
        self.tint_table = np.zeros((max(len(rows), 1), nbio + 1), np.int32)
        for (bid, bio), g in self.gid_of.items():
            if bid in self.tinted:
                if bio == -1:
                    self.base_gid[bid] = g
            else:
                self.base_gid[bid] = g
        for r, bid in enumerate(rows):
            self.tint_row[bid] = r
            self.tint_table[r, :] = self.gid_of[(bid, -1)]
            for bio in range(nbio):
                g = self.gid_of.get((bid, bio))
                if g is not None:
                    self.tint_table[r, bio] = g
        kinds, flags = np.array(self.pal['kind']), np.array(self.pal['flags'])
        # what counts as ground covering the air below it (for cave marking)
        self.drawn = np.isin(kinds, [KIND_SOLID, KIND_WATER, KIND_GLASS]) | (
            (kinds == KIND_MODEL) & ((flags & FLAG_CUBE_AT_LOD) != 0))

    def palette_json(self):
        return {'palette': self.pal, 'names': self.names,
                'biomes': [self.biomes.get(i, '?') for i in range(max(self.biomes, default=-1) + 1)],
                'textureCount': len(self.mb.tex_ids), 'modelCount': len(self.mb.models),
                'waterLayer': self.mb.layer('minecraft:block/water_still', fill=True)}

    def asset(self, which):
        """Cached binary blobs: 'textures' (VXA1: u32 count, u32 size, RGBA layers) or 'models' (VXM1)."""
        with self.lock:
            n = len(self.mb.tex_ids) if which == 'textures' else len(self.mb.models)
            hit = self._assets.get(which)
            if hit is None or hit[0] != n:
                if which == 'textures':
                    blob = struct.pack('<4sII', b'VXA1', n, 16) + self.mb.atlas_bytes()
                else:
                    blob = self.mb.models_blob()
                hit = self._assets[which] = (n, blob)
            return hit[1]

    # ------------------------------------------------------------------ section conversion
    def _raw(self, key):
        v = self.overlay[key] if key in self.overlay else self.sections_cf.get(struct.pack('>Q', key))
        return None if v is None else self.dctx.decompress(v, max_output_size=MAX_RAW)

    def _convert(self, key, raw=None):
        raw = raw if raw is not None else self._raw(key)
        if raw is None:
            return None
        n = struct.unpack_from('<Q', raw, 8)[0] & 0xFFFF
        idx = np.frombuffer(raw, '<u2', 32768, 16)
        lut = np.frombuffer(raw, '<u8', n, 16 + 65536)
        bids = ((lut >> 27) & 0xFFFFF).astype(np.int64)
        bids[bids >= len(self.base_gid)] = 0                          # unknown block state -> air
        bios = np.minimum((lut >> 47) & 0x1FF, self.tint_table.shape[1] - 1).astype(np.int64)
        g = self.base_gid[bids]
        rows = self.tint_row[bids]
        t = rows >= 0
        g[t] = self.tint_table[rows[t], bios[t]]
        sky = (lut >> 56).astype(np.uint8) & 15
        return g.astype(np.uint16)[idx], sky[idx]

    def _colmask_of(self, conv):
        return np.zeros((32, 32), bool) if conv is None else self.drawn[conv[0]].reshape(32, 32, 32).any(axis=0)

    def _colmask(self, key):
        m = self.colmasks.get(key)
        if m is None:
            m = self.colmasks[key] = self._colmask_of(self._convert(key))
        return m

    def _cover(self, l, x, y, z):
        cover = np.zeros((32, 32), bool)
        for y2 in self.columns.get((l, x, z), ()):
            if y2 > y:
                cover |= self._colmask(make_key(l, x, y2, z))
        return cover

    def record(self, l, x, y, z):
        with self.lock:
            key = make_key(l, x, y, z)
            r = self.records.get(key)
            if r is not None:
                self.records.move_to_end(key)
                return r
            conv = self._convert(key) if (x, y, z) in self.index.get(l, ()) else None
            if conv is None:
                r = encode_record(l, x, y, z)
            else:
                g, sky = conv
                g = g.reshape(32, 32, 32).copy(); sky = sky.reshape(32, 32, 32)   # [y, z, x]
                solid = self.drawn[g]
                self.colmasks[key] = solid.any(axis=0)
                at_or_above = np.maximum.accumulate(solid[::-1], axis=0)[::-1]
                strictly_above = np.concatenate([at_or_above[1:], _Z]) | self._cover(l, x, y, z)
                air = g == GID_AIR
                cave = air & (sky == 0) & strictly_above
                # Minecraft keeps no light data for 16-block slices that were empty sky; Voxy stores 0
                # there. A slice that is ~all air with no skylight anywhere is open sky, not a cave
                # (otherwise air under builds high up, e.g. bridges, turns into dark "cave" blocks).
                slab = max(1, 16 >> l)
                a4 = air.reshape(32 // slab, slab * 1024)
                lit = ((sky.reshape(32 // slab, slab * 1024) > 0) & a4).any(axis=1)
                sky_gap = (a4.mean(axis=1) > 0.9) & ~lit
                cave &= ~np.repeat(sky_gap, slab)[:, None, None]
                g[cave] = GID_CAVE
                r = encode_record(l, x, y, z, g.reshape(-1))
            self.records[key] = r; self.records_bytes += len(r)
            while self.records_bytes > self.cache_budget and self.records:
                _, old = self.records.popitem(last=False); self.records_bytes -= len(old)
            return r

    def roots(self):
        with self.lock:
            top = max(self.index) if self.index else 0
            return top, sorted(self.index.get(top, ()))

    # ------------------------------------------------------------------ live updates
    def poll_live(self):
        """Apply new WAL writes. Returns an event dict, or None if nothing changed."""
        with self.lock:
            batches = self.tailer.poll()
            if not batches:
                return None
            ops = [op for b in batches for op in parse_batch(b)]
            pal_before = len(self.pal['kind'])
            # Mappings first: sections in the same poll may already use new block states.
            new_blocks, new_biomes = [], []
            for op in ops:
                if op[0] == 'put' and op[3][:2] == GZIP_MAGIC and len(op[2]) == 4:
                    kid = struct.unpack('>i', op[2])[0]
                    typ, idx = (kid >> 30) & 3, kid & 0x3FFFFFFF
                    is_new = idx not in (self.blocks if typ == 1 else self.biomes)   # Voxy re-saves known ones too
                    kind, idx = self._read_mapping(op[2], op[3])
                    if is_new and kind:
                        (new_blocks if kind == 'block' else new_biomes).append(idx)
            for bio in new_biomes:
                self._add_biome(bio)
            for bid in new_blocks:
                self._add_block(bid)
            if new_blocks or new_biomes:
                self._rebuild_luts()

            changed = set()
            for op in ops:
                if op[0] == 'put' and op[3][:4] == ZSTD_MAGIC:
                    self._apply_section(op[3], changed)
                elif op[0] == 'del' and len(op[2]) == 8:
                    key = struct.unpack('>Q', op[2])[0]
                    self.overlay[key] = None
                    self._index_remove(key)
                    self._invalidate(key, changed, below=True)
            if not changed and len(self.pal['kind']) == pal_before:
                return None
            self.version += 1
            self.last_change = time.time()
            ev = {'v': self.version, 'keys': [list(decode_key(k)) for k in changed], 'lost': self.tailer.lost}
            if len(self.pal['kind']) != pal_before:
                ev['paletteLen'] = len(self.pal['kind'])
            return ev

    def _apply_section(self, value, changed):
        raw = self.dctx.decompress(value, max_output_size=MAX_RAW)
        key = struct.unpack_from('<Q', raw, 0)[0]
        l, x, y, z = decode_key(key)
        known = (x, y, z) in self.index.get(l, ())
        old_mask = self.colmasks.get(key)
        if old_mask is None and known:
            old_mask = self._colmask_of(self._convert(key))
        self.overlay[key] = value
        if not known:
            self._index_add(key)
        new_mask = self._colmask_of(self._convert(key, raw))
        self.colmasks[key] = new_mask
        below = old_mask is None or not np.array_equal(old_mask, new_mask)
        self._invalidate(key, changed, below)

    def _invalidate(self, key, changed, below):
        l, x, y, z = decode_key(key)
        for k in [key] + ([make_key(l, x, y2, z) for y2 in self.columns.get((l, x, z), ()) if y2 < y] if below else []):
            r = self.records.pop(k, None)
            if r is not None:
                self.records_bytes -= len(r)
            changed.add(k)
