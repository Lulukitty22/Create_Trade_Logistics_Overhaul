"""Python stand-in for the client mod: serves the web page + the local HTTP API from DESIGN.md.

Streams terrain on demand from a snapshot of a Voxy database (never opening the game's copy) and,
while the game runs, tails Voxy's write-ahead log to push changed sections to the page live.

    python serve.py                                   # Create Logistics World test save
    python serve.py --world "<saves/World Name>"      # any singleplayer save (finds voxy/*/storage)
    python serve.py --storage "<.../storage>"         # explicit Voxy storage dir (e.g. a server cache)
"""
import argparse
import glob
import gzip
import json
import mimetypes
import os
import struct
import sys
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from block_colors import AssetIndex, BlockColorizer, default_jars
from terrain_store import TerrainStore
from voxy_reader import read_nbt_gz

HERE = os.path.dirname(os.path.abspath(__file__))
WEB_DIR = os.path.join(HERE, 'web')
PROFILE = r'C:\Users\Nicol\AppData\Roaming\ModrinthApp\profiles\Create Aeronautics'
VERSION_JAR = r'C:\Users\Nicol\AppData\Roaming\ModrinthApp\meta\versions\1.21.1-21.1.249\1.21.1-21.1.249.jar'
DEFAULT_WORLD = os.path.join(PROFILE, 'saves', 'Create Logistics World')


def find_storage(world_dir):
    """A save's Voxy data lives in saves/<world>/voxy/<hash>/storage; pick the most recently written."""
    cands = glob.glob(os.path.join(world_dir, 'voxy', '*', 'storage'))
    if not cands:
        sys.exit(f'no voxy/*/storage under {world_dir}')
    return max(cands, key=lambda p: max((os.path.getmtime(os.path.join(p, f)) for f in os.listdir(p)), default=0))


def start_position(world_dir):
    """Player position from level.dat (singleplayer), else world spawn, else origin."""
    try:
        with open(os.path.join(world_dir, 'level.dat'), 'rb') as f:
            data = read_nbt_gz(f.read()).get('Data', {})
        pos = data.get('Player', {}).get('Pos')
        if pos:
            return {'x': pos[0], 'y': pos[1], 'z': pos[2]}
        return {'x': data.get('SpawnX', 0), 'y': data.get('SpawnY', 64), 'z': data.get('SpawnZ', 0)}
    except (OSError, ValueError, KeyError):
        return {'x': 0, 'y': 64, 'z': 0}


class EventHub:
    """Fan-out of server-sent events to any number of open pages."""

    def __init__(self):
        self.cond = threading.Condition()
        self.events = deque(maxlen=500)   # (seq, name, json)
        self.seq = 0

    def publish(self, name, data):
        with self.cond:
            self.seq += 1
            self.events.append((self.seq, name, json.dumps(data, separators=(',', ':'))))
            self.cond.notify_all()

    def wait(self, after, timeout):
        with self.cond:
            self.cond.wait_for(lambda: self.seq > after, timeout)
            return [e for e in self.events if e[0] > after]


class Handler(BaseHTTPRequestHandler):
    store: TerrainStore = None
    hub: EventHub = None
    meta: dict = {}
    protocol_version = 'HTTP/1.1'

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, body, ctype, extra=None):
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, code=200):
        self._send(code, json.dumps(obj, separators=(',', ':')).encode(), 'application/json')

    def _world(self):
        s = self.store
        top, _ = s.roots()
        return {**self.meta, **s.palette_json(), 'maxLod': top, 'version': s.version,
                'snapshotTime': s.snapshot_time, 'lastChange': s.last_change, 'stats': s.stats}

    def do_GET(self):
        path = self.path.split('?', 1)[0]
        if path == '/api/world':
            return self._json(self._world())
        if path == '/api/palette':
            with self.store.lock:
                return self._json(self.store.palette_json())
        if path == '/api/terrain/roots':
            top, keys = self.store.roots()
            body = struct.pack('<II', top, len(keys)) + b''.join(struct.pack('<iii', *k) for k in keys)
            return self._send(200, body, 'application/octet-stream')
        if path == '/api/events':
            return self._events()
        if path in ('/api/assets/textures', '/api/assets/models'):
            blob = gzip.compress(self.store.asset(path.rsplit('/', 1)[1]), compresslevel=6)
            return self._send(200, blob, 'application/octet-stream', {'Content-Encoding': 'gzip'})
        rel = 'index.html' if path in ('/', '') else path.lstrip('/')
        full = os.path.normpath(os.path.join(WEB_DIR, rel))
        if not full.startswith(WEB_DIR) or not os.path.isfile(full):
            return self._json({'error': 'not found'}, 404)
        ctype = 'text/javascript' if full.endswith('.js') else (mimetypes.guess_type(full)[0] or 'application/octet-stream')
        with open(full, 'rb') as f:
            self._send(200, f.read(), ctype)

    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(n) if n else b''
        if self.path == '/api/terrain/sections':
            keys = struct.iter_unpack('<iiii', body[:len(body) // 16 * 16])
            recs = [self.store.record(*k) for k in keys]
            with self.store.lock:
                pal_len = len(self.store.pal['kind'])
            out = struct.pack('<4sII', b'VXS1', len(recs), pal_len) + b''.join(recs)
            return self._send(200, gzip.compress(out, compresslevel=1), 'application/octet-stream',
                              {'Content-Encoding': 'gzip'})
        if self.path == '/api/resync':
            stats = self.store.open()
            self.hub.publish('resync', {'v': self.store.version})
            return self._json({'ok': True, 'stats': stats})
        self._json({'error': 'not found'}, 404)

    def _events(self):
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'close')
        self.end_headers()
        last = self.hub.seq
        try:
            self.wfile.write(b'retry: 2000\n\n'); self.wfile.flush()
            while True:
                evs = self.hub.wait(last, timeout=15)
                if not evs:
                    self.wfile.write(b': ping\n\n')
                for seq, name, data in evs:
                    self.wfile.write(f'event: {name}\ndata: {data}\n\n'.encode())
                    last = seq
                self.wfile.flush()
        except (ConnectionError, OSError):
            pass


def live_loop(store, hub, interval):
    while True:
        time.sleep(interval)
        try:
            ev = store.poll_live()
        except Exception as e:  # noqa: BLE001 - keep the loop alive, report
            print('live poll error:', repr(e), flush=True)
            continue
        if ev:
            hub.publish('changed', ev)
            print(f"live: {len(ev['keys'])} sections changed (v{ev['v']})", flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--world', help='singleplayer save dir (default: Create Logistics World)')
    ap.add_argument('--storage', help='explicit Voxy storage dir (overrides --world)')
    ap.add_argument('--profile', default=PROFILE, help='game profile dir (for mod jars)')
    ap.add_argument('--version-jar', default=VERSION_JAR, help='vanilla client jar (textures/biomes)')
    ap.add_argument('--port', type=int, default=8765)
    ap.add_argument('--poll', type=float, default=1.0, help='seconds between live WAL polls (0 = off)')
    args = ap.parse_args()

    world_dir = args.world or (None if args.storage else DEFAULT_WORLD)
    storage = args.storage or find_storage(world_dir)
    name = os.path.basename(os.path.normpath(world_dir)) if world_dir else os.path.basename(
        os.path.dirname(os.path.dirname(os.path.normpath(storage))))
    t = time.time()
    assets = AssetIndex(default_jars(args.profile, args.version_jar))
    print(f'indexed {len(assets.where)} asset files in {time.time() - t:.1f}s', flush=True)

    cache = os.path.join(HERE, '.cache', ''.join(c if c.isalnum() else '_' for c in name))
    store = TerrainStore(storage, cache, BlockColorizer(assets))
    stats = store.open()
    print(f'opened {name}: {stats}', flush=True)

    Handler.store, Handler.hub = store, EventHub()
    Handler.meta = {'name': name, 'live': args.poll > 0,
                    'start': start_position(world_dir) if world_dir else {'x': 0, 'y': 64, 'z': 0}}
    if args.poll > 0:
        threading.Thread(target=live_loop, args=(store, Handler.hub, args.poll), daemon=True).start()
    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    server.daemon_threads = True
    print(f'serving {name} on http://127.0.0.1:{args.port}/', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
