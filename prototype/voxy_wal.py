"""Tail a live RocksDB write-ahead log (WAL) to see Voxy's writes as they happen.

RocksDB appends every write batch to NNNNNN.log before it reaches the SST files. Reading only the
new bytes of that file each poll gives near-real-time section updates without opening the database.
Files are opened with full share flags, so we never block the game from writing/deleting them.

WAL format: 32 KiB blocks of physical records
    [crc32c u32][length u16][type u8]                (legacy, 7-byte header)
    [crc32c u32][length u16][type u8][log_no u32]    (recyclable, 11-byte header)
  types: 1 full, 2 first, 3 middle, 4 last (5-8 = recyclable variants); others are metadata.
Each logical record is a WriteBatch: [sequence u64][count u32] then tagged ops.
"""
import os
import re
import struct
import sys

BLOCK = 32768
_FULL, _FIRST, _MIDDLE, _LAST = (1, 5), (2, 6), (3, 7), (4, 8)


def open_shared(path):
    """Open a file read-only without denying the owner write/delete access (Windows-safe)."""
    if sys.platform != 'win32':
        return open(path, 'rb')
    import ctypes
    import msvcrt
    from ctypes import wintypes
    k32 = ctypes.WinDLL('kernel32', use_last_error=True)
    k32.CreateFileW.restype = wintypes.HANDLE
    k32.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, wintypes.LPVOID,
                                wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    GENERIC_READ, SHARE_ALL, OPEN_EXISTING = 0x80000000, 0x7, 3
    h = k32.CreateFileW(path, GENERIC_READ, SHARE_ALL, None, OPEN_EXISTING, 0, None)
    if h in (None, wintypes.HANDLE(-1).value):
        raise OSError(ctypes.get_last_error(), 'CreateFileW failed', path)
    fd = msvcrt.open_osfhandle(h, os.O_RDONLY | os.O_BINARY)
    return os.fdopen(fd, 'rb')


def parse_records(buf, base, start):
    """Parse physical records in `buf` (file bytes beginning at absolute offset `base`) from `start`.

    Returns (logical_payloads, resume_offset). resume_offset is the end of the last *complete*
    logical record, so a partially written record is re-read on the next poll.
    """
    out, pos, end = [], start, base + len(buf)
    frag, resume = None, start
    while True:
        left = BLOCK - (pos % BLOCK)
        if left < 7:                      # block trailer padding
            if pos + left > end:
                break
            pos += left
            if frag is None:
                resume = pos
            continue
        if pos + 7 > end:
            break
        i = pos - base
        length, typ = buf[i + 4] | (buf[i + 5] << 8), buf[i + 6]
        if typ == 0 and length == 0:      # zeroes: preallocated / not yet written
            break
        hdr = 11 if typ in (5, 6, 7, 8) else 7
        if pos + hdr + length > end:      # record not fully written yet
            break
        data = bytes(buf[i + hdr:i + hdr + length])
        pos += hdr + length
        if typ in _FULL:
            out.append(data); frag = None
        elif typ in _FIRST:
            frag = [data]
        elif typ in _MIDDLE:
            if frag is not None:
                frag.append(data)
        elif typ in _LAST:
            if frag is not None:
                frag.append(data)
                out.append(b''.join(frag)); frag = None
        if frag is None:
            resume = pos
    return out, resume


def _varint(b, p):
    r = s = 0
    while True:
        c = b[p]; p += 1
        r |= (c & 0x7F) << s
        if c < 0x80:
            return r, p
        s += 7


def _varstr(b, p):
    n, p = _varint(b, p)
    return b[p:p + n], p + n


_CF_TAGS = {0x4, 0x5, 0x6, 0x8, 0xE, 0x10, 0x17}


def parse_batch(data):
    """Yields ('put', cf, key, value) / ('del', cf, key) ops from one WriteBatch payload."""
    if len(data) < 12:
        return
    p = 12
    while p < len(data):
        tag = data[p]; p += 1
        cf = 0
        if tag in _CF_TAGS:
            cf, p = _varint(data, p)
        if tag in (0x1, 0x5):                     # value
            k, p = _varstr(data, p); v, p = _varstr(data, p)
            yield ('put', cf, k, v)
        elif tag in (0x0, 0x4, 0x7, 0x8):         # deletion / single deletion
            k, p = _varstr(data, p)
            yield ('del', cf, k)
        elif tag in (0x2, 0x6, 0xE, 0xF, 0x10, 0x11, 0x16, 0x17):   # merge, range del, blob, entity: skip
            _, p = _varstr(data, p); _, p = _varstr(data, p)
        elif tag == 0x3:                          # log data blob
            _, p = _varstr(data, p)
        elif tag in (0x9, 0xA, 0xB, 0xC, 0x12, 0x13):   # 2PC markers (xid)
            if tag != 0x9:
                _, p = _varstr(data, p)
        elif tag == 0xD:                          # noop
            pass
        else:
            return                                # unknown tag: stop rather than misparse


class WalTailer:
    """Follows the newest NNNNNN.log in a RocksDB dir across rotations."""

    def __init__(self, storage_dir):
        self.dir = storage_dir
        self.cur = None       # (log number, path)
        self.offset = 0
        self.lost = False     # set if a rotation was missed and data may have been skipped

    def logs(self):
        found = []
        for name in os.listdir(self.dir):
            m = re.fullmatch(r'(\d+)\.log', name)
            if m:
                found.append((int(m.group(1)), os.path.join(self.dir, name)))
        return sorted(found)

    def start_at(self, log_number, offset):
        self.cur = (log_number, os.path.join(self.dir, f'{log_number:06d}.log'))
        self.offset = offset

    def poll(self):
        """Returns a list of WriteBatch payloads written since the last poll."""
        logs = self.logs()
        if not logs:
            return []
        if self.cur is None:
            self.cur, self.offset = logs[-1], 0
        batches = []
        while True:
            try:
                with open_shared(self.cur[1]) as f:
                    f.seek(self.offset)
                    buf = f.read()
                recs, self.offset = parse_records(buf, self.offset, self.offset)
                batches.extend(recs)
            except FileNotFoundError:
                self.lost = True
            newer = [l for l in logs if l[0] > self.cur[0]]
            if not newer:
                return batches
            self.cur, self.offset = newer[0], 0   # finished the old log; move to the next one
