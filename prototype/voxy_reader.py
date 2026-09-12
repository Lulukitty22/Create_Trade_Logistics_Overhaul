"""Minimal reader for Voxy (0.2.x) LOD storage: RocksDB + ZSTD + SaveLoadSystem3 sections."""
import gzip
import io
import os
import shutil
import struct

import numpy as np
import zstandard
from rocksdict import AccessType, Options, Rdict

# ---------- minimal NBT ----------

def _read_nbt_payload(f, tag):
    if tag == 1: return struct.unpack('>b', f.read(1))[0]
    if tag == 2: return struct.unpack('>h', f.read(2))[0]
    if tag == 3: return struct.unpack('>i', f.read(4))[0]
    if tag == 4: return struct.unpack('>q', f.read(8))[0]
    if tag == 5: return struct.unpack('>f', f.read(4))[0]
    if tag == 6: return struct.unpack('>d', f.read(8))[0]
    if tag == 7:
        n = struct.unpack('>i', f.read(4))[0]; return f.read(n)
    if tag == 8:
        n = struct.unpack('>H', f.read(2))[0]; return f.read(n).decode('utf-8', 'replace')
    if tag == 9:
        sub = f.read(1)[0]; n = struct.unpack('>i', f.read(4))[0]
        return [_read_nbt_payload(f, sub) for _ in range(n)]
    if tag == 10:
        out = {}
        while True:
            t = f.read(1)[0]
            if t == 0: return out
            name = f.read(struct.unpack('>H', f.read(2))[0]).decode('utf-8', 'replace')
            out[name] = _read_nbt_payload(f, t)
    if tag == 11:
        n = struct.unpack('>i', f.read(4))[0]; return list(struct.unpack(f'>{n}i', f.read(4 * n)))
    if tag == 12:
        n = struct.unpack('>i', f.read(4))[0]; return list(struct.unpack(f'>{n}q', f.read(8 * n)))
    raise ValueError(f'bad nbt tag {tag}')


def read_nbt_gz(data: bytes):
    f = io.BytesIO(gzip.decompress(data))
    tag = f.read(1)[0]
    f.read(struct.unpack('>H', f.read(2))[0])  # root name
    return _read_nbt_payload(f, tag)

# ---------- key / voxel helpers (mirrors WorldEngine / Mapper) ----------

def _sx(v, bits):
    return v - (1 << bits) if v & (1 << (bits - 1)) else v


def decode_key(key: int):
    lvl = (key >> 60) & 0xF
    y = _sx((key >> 52) & 0xFF, 8)
    z = _sx((key >> 28) & 0xFFFFFF, 24)
    x = _sx((key >> 4) & 0xFFFFFF, 24)
    return lvl, x, y, z


def block_id(v): return (v >> 27) & 0xFFFFF
def biome_id(v): return (v >> 47) & 0x1FF
def light_id(v): return (v >> 56) & 0xFF

# ---------- storage ----------

def snapshot(storage_dir, dest_dir):
    """Copy a (possibly live) Voxy RocksDB dir, minus LOCK, so we never open the game's original.

    The WAL may be mid-write; RocksDB's default point-in-time recovery drops a torn tail on open.
    """
    if os.path.isdir(dest_dir):
        shutil.rmtree(dest_dir)
    os.makedirs(dest_dir)
    for name in os.listdir(storage_dir):
        if name == 'LOCK':
            continue
        src = os.path.join(storage_dir, name)
        if os.path.isfile(src):
            shutil.copyfile(src, os.path.join(dest_dir, name))
    open(os.path.join(dest_dir, 'LOCK'), 'wb').close()
    return dest_dir


class VoxyStorage:
    def __init__(self, path):
        self.path = path
        opts = Options(raw_mode=True)
        cfs = Rdict.list_cf(path, opts)
        self.db = Rdict(path, options=opts, column_families={c: Options(raw_mode=True) for c in cfs},
                        access_type=AccessType.read_only())
        self.sections_cf = self.db.get_column_family('world_sections')
        self.mappings_cf = self.db.get_column_family('id_mappings')
        self.dctx = zstandard.ZstdDecompressor()

    def load_mappings(self):
        """Returns (blocks: {id: (name, props)}, biomes: {id: name})."""
        blocks, biomes = {}, {}
        for k, v in self.mappings_cf.items():
            kid = struct.unpack('>i', k)[0]
            typ, idx = (kid >> 30) & 3, kid & 0x3FFFFFFF
            nbt = read_nbt_gz(v)
            if typ == 1:
                bs = nbt.get('block_state', {})
                blocks[idx] = (bs.get('Name', '?'), bs.get('Properties', {}))
            elif typ == 2:
                biomes[idx] = nbt.get('biome_id', '?')
        blocks.setdefault(0, ('minecraft:air', {}))
        return blocks, biomes

    def iter_sections_raw(self):
        """Yields (key, nonEmptyChildren, idx[32768] uint16, lut uint64): voxel i = lut[idx[i]],
        with i = (y<<10)|(z<<5)|x."""
        for _, v in self.sections_cf.items():
            raw = self.dctx.decompress(v, max_output_size=8 + 8 + 65536 + 65536 * 8)
            key, meta = struct.unpack_from('<QQ', raw, 0)
            lut_len = meta & 0xFFFF
            idx = np.frombuffer(raw, dtype='<u2', count=32768, offset=16)
            lut = np.frombuffer(raw, dtype='<u8', count=lut_len, offset=16 + 65536)
            yield key, (meta >> 16) & 0xFF, idx, lut

    def iter_sections(self):
        """Yields (key, nonEmptyChildren, data[32768] uint64) with data indexed (y<<10)|(z<<5)|x."""
        for key, nec, idx, lut in self.iter_sections_raw():
            yield key, nec, lut[idx]
