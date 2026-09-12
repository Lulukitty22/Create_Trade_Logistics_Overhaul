// Logistics Map prototype: streams Voxy LOD terrain from the local API and renders it with three.js.
// Sections are fetched on demand as the LOD octree refines, and live changes arrive over SSE.
import * as THREE from 'three';
import { MapControls } from 'three/addons/controls/MapControls.js';
import { Terminals } from './terminals.js';

const P = 34;                        // padded section size for the mesher
const KIND_SOLID = 1, KIND_WATER = 2;
const MISSING = 0xffff;
const LOD_TINTS = [0xff8f8f, 0x8fff9a, 0x8fb4ff, 0xffe98f, 0xe68fff, 0x8ffff0];
const FLASH_COLOR = new THREE.Color(0xffc04a), FLASH_MS = 1800;
const SKY = 0xa9c8e8;
const FETCH_BATCH = 160, FETCH_PARALLEL = 3;

const $ = (id) => document.getElementById(id);
const ui = {
  status: $('status'), live: $('live'), stats: $('stats'), detail: $('detail'), caves: $('caves'),
  lodtint: $('lodtint'), flash: $('flash'), textures: $('textures'), refresh: $('refresh'), tip: $('tip'),
  name: $('world-name'),
};

// ---------- three.js setup ----------
// Block colours are authored sRGB values: pass them straight through, no linear conversion.
THREE.ColorManagement.enabled = false;
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.setSize(innerWidth, innerHeight);
document.body.prepend(renderer.domElement);

const scene = new THREE.Scene();
scene.background = new THREE.Color(SKY);
scene.fog = new THREE.Fog(SKY, 1400, 5000);
const camera = new THREE.PerspectiveCamera(60, innerWidth / innerHeight, 0.5, 20000);
const controls = new MapControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.dampingFactor = 0.12;
controls.maxPolarAngle = Math.PI * 0.495;
controls.zoomToCursor = true;
addEventListener('resize', () => {
  camera.aspect = innerWidth / innerHeight; camera.updateProjectionMatrix();
  renderer.setSize(innerWidth, innerHeight);
});

// Terrain shader: texture-array sample (tiling per block via fract, with proper mip gradients),
// cutout alpha test, vertex colour (tint x shade), a per-material tint (LOD debug / live flash) and fog.
const VS = /* glsl */`
in float layer;
out vec2 vUv; out float vLayer; out vec3 vColor; out float vDepth;
void main() {
  vUv = uv; vLayer = layer; vColor = color;
  vec4 mv = modelViewMatrix * vec4(position, 1.0);
  vDepth = -mv.z;
  gl_Position = projectionMatrix * mv;
}`;
const FS = /* glsl */`
precision highp sampler2DArray;
uniform sampler2DArray atlas;
uniform float useTex, opacity, fogNear, fogFar;
uniform vec3 tint, fogColor;
in vec2 vUv; in float vLayer; in vec3 vColor; in float vDepth;
layout(location = 0) out highp vec4 outColor;
void main() {
  vec2 gx = dFdx(vUv), gy = dFdy(vUv);
  vec4 t = vec4(1.0);
  if (useTex > 0.5 && vLayer >= 0.0) {
    t = textureGrad(atlas, vec3(fract(vUv), vLayer), gx, gy);
    if (t.a < 0.5) discard;
  }
  vec3 c = t.rgb * vColor * tint;
  outColor = vec4(mix(c, fogColor, smoothstep(fogNear, fogFar, vDepth)), opacity);
}`;
const shared = {
  atlas: { value: null }, useTex: { value: 1 },
  fogColor: { value: new THREE.Color(SKY) }, fogNear: { value: 1000 }, fogFar: { value: 4000 },
};
function makeMat(tintHex, water) {
  return new THREE.ShaderMaterial({
    glslVersion: THREE.GLSL3, vertexShader: VS, fragmentShader: FS, vertexColors: true,
    uniforms: { ...shared, tint: { value: new THREE.Color(tintHex) }, opacity: { value: water ? 0.78 : 1 } },
    transparent: water, depthWrite: !water,
  });
}
const solidMats = [], waterMats = [];
for (let l = 0; l < 16; l++) { solidMats.push(makeMat(0xffffff, false)); waterMats.push(makeMat(0xffffff, true)); }
const lodColor = (l) => (ui.lodtint.checked ? LOD_TINTS[l % LOD_TINTS.length] : 0xffffff);
function applyLodTint() {
  for (let l = 0; l < 16; l++) { solidMats[l].uniforms.tint.value.setHex(lodColor(l)); waterMats[l].uniforms.tint.value.setHex(lodColor(l)); }
}

// ---------- block assets: texture array + baked models ----------
let models = { mOff: new Uint32Array(1), mData: new Float32Array(0) };
let assetCounts = { textures: -1, models: -1 };
async function loadAssets() {
  if (world.textureCount !== assetCounts.textures) {
    const buf = await (await fetch('/api/assets/textures')).arrayBuffer();
    const dv = new DataView(buf), count = dv.getUint32(4, true), size = dv.getUint32(8, true);
    const tex = new THREE.DataArrayTexture(new Uint8Array(buf, 12), size, size, count);
    tex.format = THREE.RGBAFormat; tex.type = THREE.UnsignedByteType;
    tex.magFilter = THREE.NearestFilter; tex.minFilter = THREE.LinearMipmapLinearFilter;
    tex.generateMipmaps = true; tex.anisotropy = renderer.capabilities.getMaxAnisotropy();
    tex.needsUpdate = true;
    shared.atlas.value?.dispose();
    shared.atlas.value = tex;
    assetCounts.textures = count;
  }
  if (world.modelCount !== assetCounts.models) {
    const buf = await (await fetch('/api/assets/models')).arrayBuffer();
    const dv = new DataView(buf), count = dv.getUint32(8, true);
    const mOff = new Uint32Array(buf.slice(12, 12 + (count + 1) * 4));
    models = { mOff, mData: new Float32Array(buf.slice(12 + (count + 1) * 4)) };
    assetCounts.models = count;
  }
}

const terminals = new Terminals(scene, camera, renderer);

// ---------- world state ----------
let world = null;          // /api/world JSON (palette, names, biomes, start, ...)
let nodes = new Map();     // "l,x,y,z" -> node
let roots = [];
let maxLod = 0;
let gen = 0;               // bumps when meshing parameters change (caves toggle)
let epoch = 0;             // bumps on full reload; stale fetch responses are dropped
let frameNo = 0;
let lastChange = null, liveConnected = false;
const key = (l, x, y, z) => `${l},${x},${y},${z}`;

// node.state: 'queued' | 'loading' | 'loaded' | 'missing'
function need(l, x, y, z) {
  const k = key(l, x, y, z);
  let n = nodes.get(k);
  if (!n) {
    n = { k, l, x, y, z, state: 'queued', empty: false, uniform: -1, pal: null, idx: null,
      mesh: null, water: null, meshGen: -1, serial: 0, meshSerial: -1, pending: false, flash: false, refetch: false };
    nodes.set(k, n);
    fetchQueue.add(n);
  }
  return n;
}
const resolved = (n) => n.state === 'loaded' || n.state === 'missing';
const voxel = (n, i) => (n.state !== 'loaded' || n.empty ? 0 : n.uniform >= 0 ? n.uniform : n.pal[n.idx[i]]);
const FACES = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
const neighbours = (n) => FACES.map(([dx, dy, dz]) => need(n.l, n.x + dx, n.y + dy, n.z + dz));

// ---------- fetching ----------
const fetchQueue = new Set();
let fetching = 0;
function nodeDist(n) {
  const s = 32 << n.l, c = camera.position;
  return Math.hypot((n.x + 0.5) * s - c.x, (n.y + 0.5) * s - c.y, (n.z + 0.5) * s - c.z);
}
function pumpFetch() {
  while (fetching < FETCH_PARALLEL && fetchQueue.size) {
    const batch = [...fetchQueue].sort((a, b) => nodeDist(a) / (1 << a.l) - nodeDist(b) / (1 << b.l)).slice(0, FETCH_BATCH);
    for (const n of batch) { fetchQueue.delete(n); if (n.state === 'queued') n.state = 'loading'; }
    const body = new Int32Array(batch.length * 4);
    batch.forEach((n, i) => body.set([n.l, n.x, n.y, n.z], i * 4));
    fetching++;
    const myEpoch = epoch;
    fetch('/api/terrain/sections', { method: 'POST', body })
      .then((r) => r.arrayBuffer())
      .then((buf) => (myEpoch === epoch ? applyRecords(buf) : null))
      .catch((e) => { console.error(e); for (const n of batch) if (n.state === 'loading') { n.state = 'queued'; fetchQueue.add(n); } })
      .finally(() => { fetching--; selectionDirty = true; });
  }
}
async function applyRecords(buf) {
  const dv = new DataView(buf);
  const count = dv.getUint32(4, true), palLen = dv.getUint32(8, true);
  if (palLen > world.palette.kind.length) await refreshPalette();
  let off = 12;
  for (let i = 0; i < count; i++) {
    const l = dv.getInt32(off, true), x = dv.getInt32(off + 4, true), y = dv.getInt32(off + 8, true), z = dv.getInt32(off + 12, true);
    const palLen = dv.getUint16(off + 16, true), flags = dv.getUint16(off + 18, true);
    off += 20;
    let pal = null, idx = null, uniform = -1;
    if (palLen !== MISSING && palLen > 0) {
      pal = new Uint16Array(buf, off, palLen);
      off += palLen * 2; off += (4 - (off % 4)) % 4;
      if (palLen === 1) uniform = pal[0];
      else if (flags & 1) { idx = new Uint16Array(buf, off, 32768); off += 65536; }
      else { idx = new Uint8Array(buf, off, 32768); off += 32768; }
    }
    const n = nodes.get(key(l, x, y, z));
    if (!n) continue;
    const wasResolved = resolved(n);
    n.state = palLen === MISSING ? 'missing' : 'loaded';
    n.empty = palLen === 0; n.pal = pal; n.idx = idx; n.uniform = uniform;
    if (wasResolved) {                      // live update: remesh it and its neighbours' borders
      n.serial++; n.flash = ui.flash.checked;
      for (const [dx, dy, dz] of FACES) {
        const m = nodes.get(key(l, x + dx, y + dy, z + dz));
        if (m && m.meshGen >= 0) m.serial++;
      }
      if (n.empty || n.state === 'missing') disposeNodeMeshes(n);
    }
  }
}
async function refreshPalette() {
  const p = await (await fetch('/api/palette')).json();
  Object.assign(world, p);
  await loadAssets();
  initWorkers();
}

// ---------- worker pool / meshing ----------
const workers = [];
const meshQueue = new Set();
let inFlight = 0;
function startWorkers() {
  const count = Math.max(2, Math.min(8, (navigator.hardwareConcurrency || 4) - 1));
  for (let i = 0; i < count; i++) {
    const w = new Worker('mesher.js');
    w.busy = false;
    w.onmessage = (e) => { w.busy = false; inFlight--; onMeshed(e.data); pumpMesh(); };
    workers.push(w);
  }
}
function initWorkers() {
  const p = world.palette;
  for (const w of workers) {
    w.postMessage({ type: 'init', kind: p.kind, flags: p.flags, faces: p.faces, tint: p.tint, top: p.top, side: p.side,
      model: p.model, rot: p.rot, mOff: models.mOff, mData: models.mData, waterLayer: world.waterLayer });
  }
}
const wantsMesh = (n) => n.meshGen !== gen || n.meshSerial !== n.serial;
function requestMesh(n) {
  if (n.pending || !wantsMesh(n)) return;
  if (!neighbours(n).every(resolved)) return;    // wait for border data (fetch already queued)
  n.pending = true;
  meshQueue.add(n);
}
function buildVolume(n) {
  const vol = new Uint16Array(P * P * P);
  if (n.uniform >= 0) vol.fill(n.uniform);
  else if (!n.empty) {
    for (let y = 0; y < 32; y++) for (let z = 0; z < 32; z++) {
      const src = (y << 10) | (z << 5), dst = ((y + 1) * P + (z + 1)) * P + 1;
      for (let x = 0; x < 32; x++) vol[dst + x] = n.pal[n.idx[src | x]];
    }
  }
  // Border voxels from same-LOD face neighbours (missing neighbour = air => explored-edge walls).
  for (const [dx, dy, dz] of FACES) {
    const m = nodes.get(key(n.l, n.x + dx, n.y + dy, n.z + dz));
    for (let a = 0; a < 32; a++) for (let b = 0; b < 32; b++) {
      let x, y, z;
      if (dx) { x = dx > 0 ? 0 : 31; y = a; z = b; }
      else if (dy) { y = dy > 0 ? 0 : 31; x = a; z = b; }
      else { z = dz > 0 ? 0 : 31; x = a; y = b; }
      const px = dx > 0 ? 33 : dx < 0 ? 0 : x + 1;
      const py = dy > 0 ? 33 : dy < 0 ? 0 : y + 1;
      const pz = dz > 0 ? 33 : dz < 0 ? 0 : z + 1;
      vol[(py * P + pz) * P + px] = m ? voxel(m, (y << 10) | (z << 5) | x) : 0;
    }
  }
  return vol;
}
function pumpMesh() {
  if (!meshQueue.size) return;
  const idle = workers.filter((w) => !w.busy);
  if (!idle.length) return;
  const sorted = [...meshQueue].sort((a, b) => nodeDist(a) / (1 << a.l) - nodeDist(b) / (1 << b.l));
  for (const w of idle) {
    const n = sorted.shift();
    if (!n) break;
    meshQueue.delete(n);
    const vol = buildVolume(n);
    let missing = 0;   // unexplored neighbours: no water walls towards them
    FACES.forEach(([dx, dy, dz], i) => {
      const m = nodes.get(key(n.l, n.x + dx, n.y + dy, n.z + dz));
      if (!m || m.state === 'missing') missing |= 1 << i;
    });
    w.busy = true; inFlight++;
    w.postMessage({ type: 'mesh', id: n.k, gen, serial: n.serial, vol, lod: n.l, missing,
      showCaves: ui.caves.checked, textured: ui.textures.checked }, [vol.buffer]);
  }
}
function makeMesh(g, mat, n) {
  if (!g.idx.length) return null;
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(g.pos, 3));
  geo.setAttribute('uv', new THREE.BufferAttribute(g.uv, 2));
  geo.setAttribute('layer', new THREE.BufferAttribute(g.lay, 1));
  geo.setAttribute('color', new THREE.BufferAttribute(g.col, 3, true));
  geo.setIndex(new THREE.BufferAttribute(g.idx, 1));
  const m = new THREE.Mesh(geo, mat);
  const s = 32 << n.l;
  m.position.set(n.x * s, n.y * s, n.z * s);
  m.scale.setScalar(1 << n.l);
  m.matrixAutoUpdate = false; m.updateMatrix();
  m.visible = visible.has(n);
  m.userData.node = n;
  scene.add(m);
  return m;
}
function disposeNodeMeshes(n) {
  for (const m of [n.mesh, n.water]) if (m) {
    scene.remove(m); m.geometry.dispose();
    if (m.userData.flashMat) { m.userData.flashMat.dispose(); flashing.delete(m); }
  }
  n.mesh = n.water = null;
}
const flashing = new Set();
function onMeshed(msg) {
  const n = nodes.get(msg.id);
  if (!n) return;
  n.pending = false;
  selectionDirty = true;
  if (msg.gen !== gen) return;               // stale; selection re-requests if still needed
  disposeNodeMeshes(n);
  n.mesh = makeMesh(msg.solid, solidMats[n.l], n);
  n.water = makeMesh(msg.water, waterMats[n.l], n);
  n.meshGen = msg.gen; n.meshSerial = msg.serial;
  if (n.flash) {
    n.flash = false;
    for (const m of [n.mesh, n.water]) if (m) {
      m.userData.flashMat = makeMat(FLASH_COLOR.getHex(), m.material.transparent); m.material = m.userData.flashMat;
      m.userData.flashStart = performance.now(); flashing.add(m);
    }
  }
}
const tmpColor = new THREE.Color();
function updateFlashes(now) {
  for (const m of flashing) {
    const t = (now - m.userData.flashStart) / FLASH_MS;
    const n = m.userData.node;
    if (t >= 1) {
      m.material = m.userData.flashMat.transparent ? waterMats[n.l] : solidMats[n.l];
      m.userData.flashMat.dispose(); delete m.userData.flashMat; flashing.delete(m);
    } else {
      m.material.uniforms.tint.value.copy(FLASH_COLOR).lerp(tmpColor.setHex(lodColor(n.l)), t * t);
    }
  }
}

// ---------- LOD selection (Voxy-style octree: refine a node into its 8 children when close) ----------
let selectionDirty = true;
let visible = new Set();
function distToNode(n) {
  const s = 32 << n.l, c = camera.position;
  const dx = Math.max(n.x * s - c.x, 0, c.x - (n.x + 1) * s);
  const dy = Math.max(n.y * s - c.y, 0, c.y - (n.y + 1) * s);
  const dz = Math.max(n.z * s - c.z, 0, c.z - (n.z + 1) * s);
  return Math.hypot(dx, dy, dz);
}
// Returns true if the node's space is covered by something drawable (possibly a stale mesh).
function resolve(n, out) {
  if (n.state === 'missing') return true;
  if (n.state !== 'loaded') return false;
  if (n.empty) return true;
  if (n.l > 0 && distToNode(n) < parseFloat(ui.detail.value) * (32 << n.l)) {
    const kids = [];
    let known = true;
    for (let i = 0; i < 8; i++) {
      const c = need(n.l - 1, n.x * 2 + (i & 1), n.y * 2 + ((i >> 1) & 1), n.z * 2 + ((i >> 2) & 1));
      if (!resolved(c)) known = false; else if (c.state === 'loaded') kids.push(c);
    }
    if (known && kids.length) {
      const tmp = [];
      let ok = true;
      for (const c of kids) if (!resolve(c, tmp)) ok = false;
      if (ok) { for (const t of tmp) out.push(t); return true; }
    }
    // children not ready yet: keep showing this coarser node meanwhile
  }
  requestMesh(n);
  if (n.meshGen >= 0) { out.push(n); return true; }
  return false;
}
function runSelection() {
  selectionDirty = false;
  const out = [];
  for (const n of roots) resolve(n, out);
  const next = new Set(out);
  for (const n of visible) if (!next.has(n)) { if (n.mesh) n.mesh.visible = false; if (n.water) n.water.visible = false; }
  for (const n of next) { if (n.mesh) n.mesh.visible = true; if (n.water) n.water.visible = true; }
  visible = next;
  pumpFetch();
  pumpMesh();
}

// ---------- loading ----------
async function loadWorld(first) {
  epoch++;
  ui.status.textContent = 'Loading world…';
  world = await (await fetch('/api/world')).json();
  ui.name.textContent = world.name;
  if (world.textures === false) {          // server has no texture/model assets (mod, for now)
    ui.textures.checked = false; ui.textures.disabled = true; shared.useTex.value = 0;
  }
  for (const n of nodes.values()) disposeNodeMeshes(n);
  nodes = new Map(); visible = new Set(); fetchQueue.clear(); meshQueue.clear(); gen++;
  ui.status.textContent = 'Loading textures and models…';
  await loadAssets();
  initWorkers();
  const buf = await (await fetch('/api/terrain/roots')).arrayBuffer();
  const dv = new DataView(buf);
  maxLod = dv.getUint32(0, true);
  roots = [];
  for (let i = 0, c = dv.getUint32(4, true); i < c; i++) {
    roots.push(need(maxLod, dv.getInt32(8 + i * 12, true), dv.getInt32(12 + i * 12, true), dv.getInt32(16 + i * 12, true)));
  }
  if (first) {
    const s = world.start;
    controls.target.set(s.x, s.y, s.z);
    camera.position.set(s.x - 180, s.y + 160, s.z + 220);
    controls.update();
  }
  lastChange = world.lastChange ? world.lastChange * 1000 : null;
  terminals.refresh();
  selectionDirty = true;
}

function connectLive() {
  if (!world?.live) return;
  const es = new EventSource('/api/events');
  es.onopen = () => { liveConnected = true; };
  es.onerror = () => { liveConnected = false; };
  es.addEventListener('changed', (e) => {
    const d = JSON.parse(e.data);
    lastChange = Date.now();
    for (const [l, x, y, z] of d.keys) {
      const n = nodes.get(key(l, x, y, z));
      if (n && resolved(n)) fetchQueue.add(n);   // keep old data on screen until the new arrives
    }
    if (d.paletteLen && d.paletteLen > world.palette.kind.length) refreshPalette();
    selectionDirty = true;
  });
  es.addEventListener('resync', () => loadWorld(false));
  es.addEventListener('terminals', () => terminals.refresh());
  es.addEventListener('order', (e) => {
    const d = JSON.parse(e.data);
    terminals.setStatus(d.message, false);
  });
}

// ---------- UI ----------
ui.detail.addEventListener('input', () => { selectionDirty = true; });
ui.caves.addEventListener('change', () => { gen++; selectionDirty = true; });
ui.textures.addEventListener('change', () => { shared.useTex.value = ui.textures.checked ? 1 : 0; gen++; selectionDirty = true; });
ui.lodtint.addEventListener('change', applyLodTint);
ui.refresh.addEventListener('click', async () => {
  ui.refresh.disabled = true;
  ui.status.textContent = 'Re-syncing from game…';
  try {
    await fetch('/api/resync', { method: 'POST' });   // server also broadcasts 'resync' -> loadWorld
  } catch (e) { ui.status.textContent = 'Resync failed: ' + e.message; }
  ui.refresh.disabled = false;
});
controls.addEventListener('change', () => { selectionDirty = true; });

// WASD / QE movement relative to view heading
const keys = new Set();
addEventListener('keydown', (e) => { if (!e.target.matches('input')) keys.add(e.key.toLowerCase()); });
addEventListener('keyup', (e) => keys.delete(e.key.toLowerCase()));
addEventListener('blur', () => keys.clear());
const tmpF = new THREE.Vector3(), tmpR = new THREE.Vector3(), move = new THREE.Vector3();
function applyKeys(dt) {
  if (!keys.size) return;
  camera.getWorldDirection(tmpF); tmpF.y = 0; tmpF.normalize();
  tmpR.crossVectors(tmpF, camera.up).normalize();
  move.set(0, 0, 0);
  if (keys.has('w')) move.add(tmpF); if (keys.has('s')) move.sub(tmpF);
  if (keys.has('d')) move.add(tmpR); if (keys.has('a')) move.sub(tmpR);
  if (keys.has('e')) move.y += 1; if (keys.has('q')) move.y -= 1;
  if (!move.lengthSq()) return;
  const speed = Math.max(40, camera.position.distanceTo(controls.target) * 1.2) * (keys.has('shift') ? 3 : 1);
  move.normalize().multiplyScalar(speed * dt);
  camera.position.add(move); controls.target.add(move);
  selectionDirty = true;
}

// Hover picking: raycast visible meshes, then look the voxel up in that node's data.
const ray = new THREE.Raycaster(), mouse = new THREE.Vector2();
let mouseDirty = false, mouseX = 0, mouseY = 0, lastPick = 0;
renderer.domElement.addEventListener('pointermove', (e) => { mouseX = e.clientX; mouseY = e.clientY; mouseDirty = true; });
renderer.domElement.addEventListener('pointerleave', () => { ui.tip.style.display = 'none'; mouseDirty = false; });
function prettyName(id) {
  const [ns, path] = id.includes(':') ? id.split(':') : ['minecraft', id];
  const nice = path.split(/[_/]/).filter(Boolean).map((w) => w[0].toUpperCase() + w.slice(1)).join(' ');
  return { nice, ns };
}
function pick() {
  mouseDirty = false;
  mouse.set((mouseX / innerWidth) * 2 - 1, -(mouseY / innerHeight) * 2 + 1);
  ray.setFromCamera(mouse, camera);
  const meshes = [];
  for (const n of visible) { if (n.mesh) meshes.push(n.mesh); if (n.water) meshes.push(n.water); }
  const hit = ray.intersectObjects(meshes, false)[0];
  if (!hit) { ui.tip.style.display = 'none'; return; }
  const n = hit.object.userData.node, vs = 1 << n.l, s = 32 << n.l;
  const p = hit.point.clone().addScaledVector(hit.face.normal, -0.5 * vs);
  const lx = Math.floor((p.x - n.x * s) / vs), ly = Math.floor((p.y - n.y * s) / vs), lz = Math.floor((p.z - n.z * s) / vs);
  if (lx < 0 || ly < 0 || lz < 0 || lx > 31 || ly > 31 || lz > 31) return;
  const g = voxel(n, (ly << 10) | (lz << 5) | lx);
  const pal = world.palette;
  let title, ns = '';
  if (g === 1) title = 'Cave (hidden)';
  else if (pal.block[g] >= 0) ({ nice: title, ns } = prettyName(world.names[pal.block[g]]));
  else title = 'Air';
  const biome = pal.biome[g] >= 0 ? world.biomes[pal.biome[g]] : null;
  ui.tip.innerHTML = `<div class="name">${title}${ns && ns !== 'minecraft' ? ` <span class="ns">${ns}</span>` : ''}</div>`
    + `<div class="meta">${Math.floor(p.x)}, ${Math.floor(p.y)}, ${Math.floor(p.z)} · LOD ${n.l}${vs > 1 ? ` (${vs}×${vs} blocks)` : ''}</div>`
    + (biome ? `<div class="meta">${prettyName(biome).nice}</div>` : '');
  ui.tip.style.display = 'block';
  ui.tip.style.left = Math.min(mouseX + 16, innerWidth - ui.tip.offsetWidth - 8) + 'px';
  ui.tip.style.top = Math.min(mouseY + 16, innerHeight - ui.tip.offsetHeight - 8) + 'px';
}

// ---------- status / stats ----------
function ago(ms) {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  return s < 60 ? `${s}s ago` : s < 3600 ? `${Math.floor(s / 60)}m ${s % 60}s ago` : `${Math.floor(s / 3600)}h ago`;
}
function updateStatus() {
  if (!world) return;
  const snap = world.snapshotTime * 1000;
  if (world.live) {
    ui.live.className = liveConnected ? 'on' : 'off';
    ui.status.textContent = liveConnected
      ? (lastChange ? `Live · last change ${ago(lastChange)}` : 'Live · waiting for changes')
      : 'Live connection lost, retrying…';
  } else {
    ui.live.className = 'off';
    ui.status.textContent = `Snapshot ${new Date(snap).toLocaleTimeString()} (${ago(snap)})`;
  }
}
function updateStats() {
  let loaded = 0;
  for (const n of nodes.values()) if (n.state === 'loaded' && !n.empty) loaded++;
  const perLod = {};
  for (const n of visible) perLod[n.l] = (perLod[n.l] || 0) + 1;
  const info = renderer.info.render;
  ui.stats.innerHTML = [
    ['FPS', fps], ['Triangles', info.triangles.toLocaleString()], ['Draw calls', info.calls],
    ['Sections loaded', loaded.toLocaleString()], ['Fetch / mesh queue', `${fetchQueue.size + fetching} / ${meshQueue.size + inFlight}`],
    ['Drawn by LOD', Object.keys(perLod).sort().map((l) => `${l}:${perLod[l]}`).join(' ') || '–'],
  ].map(([k, v]) => `<span>${k}</span><b>${v}</b>`).join('');
  updateStatus();
}

// ---------- main loop ----------
// ?timer drives the loop with setTimeout (for hidden/background previews where rAF is paused).
const nextFrame = new URLSearchParams(location.search).has('timer')
  ? (cb) => setTimeout(cb, 16) : (cb) => requestAnimationFrame(cb);
const clock = new THREE.Clock();
let frames = 0, fpsTime = 0, fps = 0, lastTerminalPoll = 0;
const lastSelCam = new THREE.Vector3(1e9, 0, 0);
function animate() {
  nextFrame(animate);
  frameNo++;
  const dt = Math.min(clock.getDelta(), 0.1);
  applyKeys(dt);
  controls.update();
  const viewDist = camera.position.distanceTo(controls.target);   // fog scales with zoom
  shared.fogNear.value = Math.max(1000, viewDist * 1.6); shared.fogFar.value = Math.max(4000, viewDist * 5);
  if (camera.position.distanceToSquared(lastSelCam) > 4) { selectionDirty = true; lastSelCam.copy(camera.position); }
  if (selectionDirty && roots.length) runSelection();
  const now = performance.now();
  updateFlashes(now);
  renderer.render(scene, camera);
  terminals.updateLabels();
  if (mouseDirty && now - lastPick > 60) { lastPick = now; pick(); }
  frames++;
  if (now - fpsTime > 500) {
    fps = Math.round(frames * 1000 / (now - fpsTime)); frames = 0; fpsTime = now; updateStats();
    if (now - lastTerminalPoll > 5000) { lastTerminalPoll = now; terminals.refresh(); }
  }
}

// Debug handle for the devtools console.
window.map = { THREE, camera, controls, scene, renderer, get nodes() { return nodes; }, get world() { return world; } };

applyLodTint();
startWorkers();
animate();
loadWorld(true).then(connectLive).catch((e) => { ui.status.textContent = 'Failed to load: ' + e.message; console.error(e); });
