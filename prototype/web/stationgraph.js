// Which station can reach which, drawn as a schematic.
//
// The 3D map shows where the railway is; this shows what it means. A siding that can only be
// entered from its reverse point appears as exactly that - two nodes and one line - so the reason a
// train has to go the long way round is visible rather than inferred.
//
// Links come from the mod, which walks the track outward from each station and stops at the first
// station it meets in each direction.

const NODE_W = 116;
const NODE_H = 26;

export class StationGraph {
  constructor() {
    this.panel = document.getElementById('stationgraph');
    this.svg = document.getElementById('stationgraph-svg');
    this.close = document.getElementById('stationgraph-close');
    this.close?.addEventListener('click', () => this.setOpen(false));
    this.open = false;
    this.signature = '';
  }

  setOpen(open) {
    this.open = open;
    if (this.panel) this.panel.hidden = !open;
    const toggle = document.getElementById('graphtoggle');
    if (toggle) toggle.checked = open;
    if (open) this.signature = '';       // force a redraw on next data
  }

  /** Lays the stations out and draws them. Cheap, and only when something changed. */
  update(rails) {
    if (!this.open || !this.svg) return;
    const stations = rails.stations || [];
    const links = rails.links || [];
    const signature = stations.map((s) => s.name).join('|') + '#' + links.length;
    if (signature === this.signature) return;
    this.signature = signature;

    if (!stations.length) {
      this.svg.innerHTML = '<text x="12" y="24" fill="#9aa1b2" font-size="12">No stations on this railway.</text>';
      return;
    }

    // Undirected, de-duplicated.
    const seen = new Set();
    const edges = [];
    for (const l of links) {
      const key = [l.from, l.to].sort().join('\u0000');
      if (seen.has(key)) continue;
      seen.add(key);
      edges.push(l);
    }

    const pos = this.layout(stations, edges);
    const pad = 14;
    const xs = [...pos.values()].map((p) => p.x);
    const ys = [...pos.values()].map((p) => p.y);
    const w = Math.max(...xs) + NODE_W / 2 + pad;
    const h = Math.max(...ys) + NODE_H / 2 + pad;
    this.svg.setAttribute('viewBox', `0 0 ${w} ${h}`);
    this.svg.setAttribute('width', w);
    this.svg.setAttribute('height', h);

    const parts = [];
    for (const e of edges) {
      const a = pos.get(e.from);
      const b = pos.get(e.to);
      if (!a || !b) continue;
      parts.push(`<line x1="${a.x}" y1="${a.y}" x2="${b.x}" y2="${b.y}" stroke="#5c6474" stroke-width="2"/>`);
    }
    for (const s of stations) {
      const p = pos.get(s.name);
      if (!p) continue;
      const reverse = /\sREV$/i.test(s.name);
      const fill = reverse ? '#2c3446' : '#333c2c';
      const stroke = reverse ? '#6fa8dc' : '#8cc07a';
      const label = s.name.length > 15 ? s.name.slice(0, 14) + '…' : s.name;
      const addr = (s.addresses || []).join(', ');
      parts.push(
        `<g><title>${esc(s.name)}${addr ? ' — serves ' + esc(addr) : ' — no postbox'}</title>` +
        `<rect x="${p.x - NODE_W / 2}" y="${p.y - NODE_H / 2}" width="${NODE_W}" height="${NODE_H}" rx="5"` +
        ` fill="${fill}" stroke="${stroke}" stroke-width="1.5"/>` +
        `<text x="${p.x}" y="${p.y + 1}" fill="#e6e8ee" font-size="11" text-anchor="middle"` +
        ` dominant-baseline="middle">${esc(label)}</text>` +
        (addr ? '' : `<circle cx="${p.x + NODE_W / 2 - 8}" cy="${p.y - NODE_H / 2 + 8}" r="3" fill="#e08a6a"/>`) +
        '</g>');
    }
    this.svg.innerHTML = parts.join('');
  }

  /**
   * Breadth-first from each unvisited station, one column per step out. Stations one hop apart end
   * up side by side, which is the shape worth seeing: a siding sits next to its reverse point.
   */
  layout(stations, edges) {
    const neighbours = new Map(stations.map((s) => [s.name, []]));
    for (const e of edges) {
      neighbours.get(e.from)?.push(e.to);
      neighbours.get(e.to)?.push(e.from);
    }
    const pos = new Map();
    const rowCount = [];
    const seen = new Set();
    for (const start of stations) {
      if (seen.has(start.name)) continue;
      const queue = [[start.name, 0]];
      seen.add(start.name);
      while (queue.length) {
        const [name, depth] = queue.shift();
        rowCount[depth] = (rowCount[depth] || 0) + 1;
        pos.set(name, { x: 0, y: 0, depth, index: rowCount[depth] - 1 });
        for (const next of neighbours.get(name) || []) {
          if (seen.has(next)) continue;
          seen.add(next);
          queue.push([next, depth + 1]);
        }
      }
    }
    for (const p of pos.values()) {
      p.x = 14 + NODE_W / 2 + p.depth * (NODE_W + 46);
      p.y = 14 + NODE_H / 2 + p.index * (NODE_H + 16);
    }
    return pos;
  }
}

const esc = (s) => String(s ?? '').replace(/[<>&"]/g, (c) => (
  { '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
