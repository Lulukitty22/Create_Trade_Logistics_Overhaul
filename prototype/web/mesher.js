// Web Worker: meshes one 32^3 section, given as a 34^3 volume with a 1-voxel neighbour border.
//  - full cubes: greedy-merged faces; uv in block units so textures tile once per block at any LOD
//  - non-cube blocks (LOD 0 only): baked model quads from /api/assets/models
//  - water (+ waterlogged blocks): separate translucent pass
// Output per vertex: position (voxel units), uv, texture layer (-1 = untextured), colour (tint x shade).

const AIR = 0, SOLID = 1, WATER = 2, CAVE = 3, GLASS = 4, MODEL = 5;
const FLAG_WL = 64, FLAG_CUBE = 128;
const P = 34, Q = 24;                               // padded size; floats per baked quad
const SHADE = [0.6, 0.6, 1.0, 0.5, 0.8, 0.8];       // +x -x +y -y +z -z (Minecraft-style)
const DIRV = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
const CAVE_RGB = [26, 24, 30], WATER_RGB = [63, 118, 228];

let T = null;   // palette tables + models

class Buf {
  constructor(n) { this.alloc(n); this.n = 0; }
  alloc(n) {
    const o = this.pos;
    const pos = new Float32Array(n * 3), uv = new Float32Array(n * 2), lay = new Float32Array(n), col = new Uint8Array(n * 3);
    if (o) { pos.set(this.pos); uv.set(this.uv); lay.set(this.lay); col.set(this.col); }
    Object.assign(this, { pos, uv, lay, col, cap: n });
  }
  vert(x, y, z, u, v, l, r, g, b) {
    if (this.n >= this.cap) this.alloc(this.cap * 2);
    const i = this.n++;
    this.pos[i * 3] = x; this.pos[i * 3 + 1] = y; this.pos[i * 3 + 2] = z;
    this.uv[i * 2] = u; this.uv[i * 2 + 1] = v; this.lay[i] = l;
    this.col[i * 3] = r; this.col[i * 3 + 1] = g; this.col[i * 3 + 2] = b;
  }
  finish() {
    const n = this.n, quads = n / 4;
    const idx = n > 65535 ? new Uint32Array(quads * 6) : new Uint16Array(quads * 6);
    for (let q = 0; q < quads; q++) {
      const v = q * 4, i = q * 6;
      idx[i] = v; idx[i + 1] = v + 1; idx[i + 2] = v + 2; idx[i + 3] = v; idx[i + 4] = v + 2; idx[i + 5] = v + 3;
    }
    return { pos: this.pos.slice(0, n * 3), uv: this.uv.slice(0, n * 2), lay: this.lay.slice(0, n), col: this.col.slice(0, n * 3), idx };
  }
}

// Texture coords (block units) for a point on a face in direction d; matches Minecraft's default UVs.
function faceUV(d, x, y, z) {
  switch (d) {
    case 0: return [-z, -y]; case 1: return [z, -y];
    case 2: return [x, z]; case 3: return [x, -z];
    case 4: return [x, -y]; default: return [-x, -y];
  }
}

function mesh(vol, lod, showCaves, textured, missing) {
  const K = lod === 0 ? T.kind0 : T.kindN;
  const opaque = (g) => { const k = K[g]; return k === SOLID || (k === CAVE && !showCaves); };
  const waterish = (g) => K[g] === WATER || (lod === 0 && K[g] === MODEL && (T.flags[g] & FLAG_WL));
  const scale = 1 << lod;
  const solid = new Buf(4096), water = new Buf(1024);
  const at = (x, y, z) => ((y + 1) * P + (z + 1)) * P + (x + 1);

  let hasWater = false, waterRep = -1, hasModel = false;
  for (let i = 0; i < vol.length; i++) {
    const g = vol[i];
    if (K[g] === WATER) { hasWater = true; if (waterRep < 0) waterRep = g; }
    else if (waterish(g)) hasWater = true;
    if (lod === 0 && K[g] === MODEL) hasModel = true;
  }

  // Colour of a face: tint (if this face is tinted) x shade, or flat average colour when untextured.
  const faceColor = (g, d, sh, out) => {
    const k = K[g];
    if (k === CAVE) { out[0] = CAVE_RGB[0] * sh; out[1] = CAVE_RGB[1] * sh; out[2] = CAVE_RGB[2] * sh; return -1; }
    if (!textured) {
      const src = d === 2 || k === WATER ? T.top : T.side;
      out[0] = src[g * 3] * sh; out[1] = src[g * 3 + 1] * sh; out[2] = src[g * 3 + 2] * sh; return -1;
    }
    const tinted = T.flags[g] & (1 << d);
    out[0] = (tinted ? T.tint[g * 3] : 255) * sh; out[1] = (tinted ? T.tint[g * 3 + 1] : 255) * sh; out[2] = (tinted ? T.tint[g * 3 + 2] : 255) * sh;
    return T.faces[g * 6 + d];
  };

  // ---- greedy passes: 0 = opaque/glass cubes, 1 = water surfaces ----
  const mask = new Int32Array(32 * 32);
  const x = [0, 0, 0], rgb = [0, 0, 0];
  for (let pass = 0; pass < (hasWater ? 2 : 1); pass++) {
    const out = pass === 0 ? solid : water;
    for (let d = 0; d < 3; d++) {
      const u = (d + 1) % 3, v = (d + 2) % 3;
      for (let s = -1; s <= 1; s += 2) {
        const dir = d * 2 + (s < 0 ? 1 : 0);
        const dx = DIRV[dir][0], dy = DIRV[dir][1], dz = DIRV[dir][2];
        for (x[d] = 0; x[d] < 32; x[d]++) {
          // water facing an unexplored neighbour section: leave it open instead of drawing a wall
          if (pass === 1 && (missing & (1 << dir)) && x[d] + s === (s > 0 ? 32 : -1)) continue;
          let any = false;
          for (x[v] = 0; x[v] < 32; x[v]++) {
            for (x[u] = 0; x[u] < 32; x[u]++) {
              const g = vol[at(x[0], x[1], x[2])];
              const n = vol[at(x[0] + dx, x[1] + dy, x[2] + dz)];
              let f = 0;
              if (pass === 0) {
                const k = K[g];
                if ((k === SOLID || (k === CAVE && !showCaves)) && !opaque(n)) f = g + 1;
                else if (k === GLASS && !opaque(n) && n !== g) f = g + 1;
              } else if (waterish(g) && !opaque(n) && !waterish(n) &&
                         // sides only where there's ground under the neighbouring air (shorelines), not
                         // over a void (edge of the explored area, which would look like a glass wall)
                         (d === 1 || opaque(vol[at(x[0] + dx, x[1] - 1, x[2] + dz)]) || waterish(vol[at(x[0] + dx, x[1] - 1, x[2] + dz)]))) {
                f = (K[g] === WATER ? g : waterRep) + 1;   // waterlogged blocks borrow nearby water's tint
                if (f === 0) f = -1;                        // no water gid in this section: default colour
              }
              mask[x[v] * 32 + x[u]] = f;
              if (f) any = true;
            }
          }
          if (!any) continue;
          for (let j = 0; j < 32; j++) {
            for (let i = 0; i < 32;) {
              const f = mask[j * 32 + i];
              if (!f) { i++; continue; }
              let w = 1;
              while (i + w < 32 && mask[j * 32 + i + w] === f) w++;
              let h = 1;
              outer: for (; j + h < 32; h++) for (let k = 0; k < w; k++) if (mask[(j + h) * 32 + i + k] !== f) break outer;
              for (let l = 0; l < h; l++) for (let k = 0; k < w; k++) mask[(j + l) * 32 + i + k] = 0;

              let layer;
              if (pass === 1) {
                const g = f > 0 ? f - 1 : -1;
                if (g >= 0 && textured) { layer = T.faces[g * 6 + dir]; rgb[0] = T.tint[g * 3]; rgb[1] = T.tint[g * 3 + 1]; rgb[2] = T.tint[g * 3 + 2]; }
                else if (g >= 0) { layer = -1; rgb[0] = T.top[g * 3]; rgb[1] = T.top[g * 3 + 1]; rgb[2] = T.top[g * 3 + 2]; }
                else { layer = textured ? T.waterLayer : -1; rgb[0] = WATER_RGB[0]; rgb[1] = WATER_RGB[1]; rgb[2] = WATER_RGB[2]; }
              } else {
                layer = faceColor(f - 1, dir, SHADE[dir], rgb);
              }
              const base = [0, 0, 0], du = [0, 0, 0], dv = [0, 0, 0];
              base[d] = x[d] + (s > 0 ? 1 : 0); base[u] = i; base[v] = j; du[u] = w; dv[v] = h;
              const c1 = [base[0] + du[0], base[1] + du[1], base[2] + du[2]];
              const c2 = [c1[0] + dv[0], c1[1] + dv[1], c1[2] + dv[2]];
              const c3 = [base[0] + dv[0], base[1] + dv[1], base[2] + dv[2]];
              const order = s > 0 ? [base, c1, c2, c3] : [base, c3, c2, c1];   // CCW seen from outside
              const rot = pass === 0 ? (T.rot[f - 1] >> (2 * dir)) & 3 : 0;   // e.g. bark on sideways logs
              for (const c of order) {
                let [tu, tv] = faceUV(dir, c[0] * scale, c[1] * scale, c[2] * scale);
                if (rot === 1) [tu, tv] = [1 - tv, tu];
                else if (rot === 2) [tu, tv] = [1 - tu, 1 - tv];
                else if (rot === 3) [tu, tv] = [tv, 1 - tu];
                out.vert(c[0], c[1], c[2], tu, tv, layer, rgb[0], rgb[1], rgb[2]);
              }
              i += w;
            }
          }
        }
      }
    }
  }

  // ---- baked models (LOD 0): slabs, stairs, fences, plants, torches, rails, ... ----
  if (hasModel) {
    const { mOff, mData } = T;
    for (let y = 0; y < 32; y++) for (let z = 0; z < 32; z++) for (let xx = 0; xx < 32; xx++) {
      const g = vol[at(xx, y, z)];
      if (K[g] !== MODEL) continue;
      const m = T.model[g];
      if (m < 0) continue;
      let buried = true;   // fully enclosed (e.g. inside hidden caves): skip entirely
      for (let d = 0; d < 6 && buried; d++) if (!opaque(vol[at(xx + DIRV[d][0], y + DIRV[d][1], z + DIRV[d][2])])) buried = false;
      if (buried) continue;
      for (let q = mOff[m]; q < mOff[m + 1]; q++) {
        const o = q * Q;
        const cull = mData[o + 22];
        if (cull >= 0) {
          const dv = DIRV[cull];
          if (opaque(vol[at(xx + dv[0], y + dv[1], z + dv[2])])) continue;
        }
        const sh = mData[o + 23], tinted = mData[o + 21] > 0;
        let layer = mData[o + 20], r, gg, b;
        if (textured) {
          r = (tinted ? T.tint[g * 3] : 255) * sh; gg = (tinted ? T.tint[g * 3 + 1] : 255) * sh; b = (tinted ? T.tint[g * 3 + 2] : 255) * sh;
        } else {
          layer = -1; r = T.side[g * 3] * sh; gg = T.side[g * 3 + 1] * sh; b = T.side[g * 3 + 2] * sh;
        }
        for (let c = 0; c < 4; c++) {
          solid.vert(xx + mData[o + c * 3], y + mData[o + c * 3 + 1], z + mData[o + c * 3 + 2],
            mData[o + 12 + c * 2], mData[o + 13 + c * 2], layer, r, gg, b);
        }
      }
    }
  }
  return { solid: solid.finish(), water: water.finish() };
}

self.onmessage = (e) => {
  const m = e.data;
  if (m.type === 'init') {
    const kind = Uint8Array.from(m.kind), flags = Uint16Array.from(m.flags);
    const kind0 = kind, kindN = new Uint8Array(kind.length);
    for (let g = 0; g < kind.length; g++) {
      let k = kind[g];
      if (k === MODEL) k = flags[g] & FLAG_CUBE ? SOLID : flags[g] & FLAG_WL ? WATER : AIR;   // coarse LODs: cube or nothing
      kindN[g] = k;
    }
    T = {
      kind0, kindN, flags, faces: Uint16Array.from(m.faces), tint: Uint8Array.from(m.tint),
      top: Uint8Array.from(m.top), side: Uint8Array.from(m.side), model: Int32Array.from(m.model),
      rot: Uint16Array.from(m.rot),
      mOff: m.mOff, mData: m.mData, waterLayer: m.waterLayer,
    };
    return;
  }
  if (m.type === 'mesh') {
    const r = mesh(m.vol, m.lod, m.showCaves, m.textured, m.missing);
    const t = [];
    for (const g of [r.solid, r.water]) t.push(g.pos.buffer, g.uv.buffer, g.lay.buffer, g.col.buffer, g.idx.buffer);
    self.postMessage({ type: 'mesh', id: m.id, gen: m.gen, serial: m.serial, solid: r.solid, water: r.water }, t);
  }
};
