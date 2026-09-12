// Logistics terminals: markers on the map, a stock list, and placing orders.
// Data comes from /api/terminals (the mod asks the server); orders POST to /api/orders and the
// result arrives on the event stream. With the Python prototype these endpoints don't exist, so
// the panel simply stays hidden.
import * as THREE from 'three';

const $ = (id) => document.getElementById(id);
const MARKER_COLOR = 0xffc04a;

export class Terminals {
  constructor(scene, camera, renderer) {
    this.scene = scene;
    this.camera = camera;
    this.renderer = renderer;
    this.list = [];
    this.selected = null;
    this.markers = new THREE.Group();
    this.labels = new Map();          // key -> HTMLElement
    scene.add(this.markers);
    this.ui = {
      panel: $('terminals'), list: $('terminal-list'), detail: $('terminal-detail'),
      title: $('terminal-title'), stock: $('terminal-stock'), search: $('stock-search'),
      address: $('order-address'), status: $('order-status'), back: $('terminal-back'),
      labels: $('map-labels'),
    };
    this.ui.back?.addEventListener('click', () => this.select(null));
    this.ui.search?.addEventListener('input', () => this.renderStock());
    this.ui.address && (this.ui.address.value = localStorage.getItem('ctlo.address') || '');
    this.ui.address?.addEventListener('change', () => localStorage.setItem('ctlo.address', this.ui.address.value));
    this.tmp = new THREE.Vector3();
  }

  async refresh() {
    try {
      const res = await fetch('/api/terminals');
      if (!res.ok) { this.ui.panel.hidden = true; return; }
      const data = await res.json();
      this.list = data.terminals || [];
      this.ui.panel.hidden = false;
      this.rebuildMarkers();
      this.renderList();
      if (this.selected) {
        const still = this.list.find((t) => key(t) === key(this.selected));
        this.selected = still || null;
        still ? this.renderStock() : this.select(null);
      }
    } catch {
      this.ui.panel.hidden = true;      // prototype server: no terminals endpoint
    }
  }

  rebuildMarkers() {
    this.markers.clear();
    for (const el of this.labels.values()) el.remove();
    this.labels.clear();
    const geo = new THREE.BoxGeometry(1.4, 1.4, 1.4);
    const mat = new THREE.MeshBasicMaterial({ color: MARKER_COLOR, depthTest: false, transparent: true, opacity: 0.9 });
    for (const t of this.list) {
      const mesh = new THREE.Mesh(geo, mat);
      mesh.position.set(t.x + 0.5, t.y + 1.6, t.z + 0.5);
      mesh.renderOrder = 999;
      this.markers.add(mesh);
      const label = document.createElement('div');
      label.className = 'map-label';
      label.textContent = t.name + (t.address ? ` · ${t.address}` : '');
      label.addEventListener('click', () => this.select(t));
      this.ui.labels.appendChild(label);
      this.labels.set(key(t), label);
    }
  }

  /** Keeps the HTML labels sitting over their markers. */
  updateLabels() {
    if (!this.list.length) return;
    const w = innerWidth / 2, h = innerHeight / 2;
    for (const t of this.list) {
      const el = this.labels.get(key(t));
      if (!el) continue;
      this.tmp.set(t.x + 0.5, t.y + 2.6, t.z + 0.5).project(this.camera);
      const visible = this.tmp.z < 1;
      el.style.display = visible ? 'block' : 'none';
      if (visible) {
        el.style.left = `${(this.tmp.x * w) + w}px`;
        el.style.top = `${(-this.tmp.y * h) + h}px`;
      }
    }
  }

  renderList() {
    if (!this.ui.list) return;
    this.ui.detail.hidden = true;
    this.ui.list.hidden = false;
    this.ui.list.innerHTML = this.list.length
      ? this.list.map((t, i) => `<button class="terminal-row" data-i="${i}">
           <span class="tname">${escape(t.name)}</span>
           <span class="tmeta">${escape(t.address || 'no address')} · ${t.stock.length} items</span>
         </button>`).join('')
      : '<div class="tmeta">No terminals found. Place one and tune it with a stock link.</div>';
    for (const button of this.ui.list.querySelectorAll('.terminal-row')) {
      button.addEventListener('click', () => this.select(this.list[+button.dataset.i]));
    }
  }

  select(terminal) {
    this.selected = terminal;
    if (!terminal) { this.renderList(); return; }
    this.ui.list.hidden = true;
    this.ui.detail.hidden = false;
    this.ui.title.textContent = terminal.name + (terminal.address ? ` · ${terminal.address}` : '');
    this.renderStock();
  }

  renderStock() {
    const t = this.selected;
    if (!t) return;
    const filter = (this.ui.search.value || '').toLowerCase();
    const rows = t.stock.filter((s) => !filter || s.name.toLowerCase().includes(filter)
      || s.item.toLowerCase().includes(filter)).slice(0, 200);
    this.ui.stock.innerHTML = rows.length
      ? rows.map((s) => `<div class="stock-row">
          <span class="sname" title="${escape(s.item)}">${escape(s.name)}</span>
          <span class="scount">${s.count.toLocaleString()}</span>
          <input class="sqty" type="number" min="1" value="1" data-item="${escape(s.item)}">
          <button class="sorder" data-item="${escape(s.item)}">Order</button>
        </div>`).join('')
      : '<div class="tmeta">Nothing in stock (or the network is unloaded).</div>';
    for (const button of this.ui.stock.querySelectorAll('.sorder')) {
      button.addEventListener('click', () => this.order(button.dataset.item));
    }
  }

  async order(item) {
    const t = this.selected;
    const qty = this.ui.stock.querySelector(`.sqty[data-item="${cssEscape(item)}"]`);
    const count = Math.max(1, parseInt(qty?.value || '1', 10));
    const address = (this.ui.address.value || '').trim();
    if (!address) { this.setStatus('Enter a delivery address first', false); return; }
    this.setStatus(`Ordering ${count} x ${item.split(':').pop()}…`, true);
    try {
      const res = await fetch('/api/orders', {
        method: 'POST',
        body: JSON.stringify({ x: t.x, y: t.y, z: t.z, item, count, address }),
      });
      if (!res.ok) this.setStatus('Order rejected: ' + (await res.text()), false);
    } catch (e) {
      this.setStatus('Order failed: ' + e.message, false);
    }
  }

  setStatus(message, pending) {
    if (!this.ui.status) return;
    this.ui.status.textContent = message;
    this.ui.status.className = pending ? 'pending' : '';
  }
}

const key = (t) => `${t.x},${t.y},${t.z}`;
const escape = (s) => String(s ?? '').replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
const cssEscape = (s) => String(s).replace(/["\\]/g, '\\$&');
