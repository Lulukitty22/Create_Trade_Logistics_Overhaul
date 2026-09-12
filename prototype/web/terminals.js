// Logistics terminals: markers on the map, stock, ordering, and terminal settings.
// Data comes from /api/terminals (the mod asks the server); orders and settings changes post back
// and their results arrive on the event stream. The Python prototype has none of these endpoints,
// so the panel simply stays hidden there.
import * as THREE from 'three';

const $ = (id) => document.getElementById(id);
const MARKER_COLOR = 0xffc04a;
const ROLES = ['PRODUCER', 'CONSUMER', 'WAREHOUSE', 'POST_OFFICE'];
const ACCESS = ['NONE', 'VIEW', 'ORDER', 'ADMIN'];

export class Terminals {
  constructor(scene, camera) {
    this.camera = camera;
    this.list = [];
    this.dispatch = null;
    this.selected = null;
    this.tab = 'stock';
    this.markers = new THREE.Group();
    this.labels = new Map();
    scene.add(this.markers);
    this.ui = {
      panel: $('terminals'), list: $('terminal-list'), detail: $('terminal-detail'),
      title: $('terminal-title'), body: $('terminal-body'), tabs: $('terminal-tabs'),
      address: $('order-address'), status: $('order-status'), back: $('terminal-back'),
      labels: $('map-labels'), dispatch: $('dispatch'),
      dispatchSummary: $('dispatch-summary'), dispatchRuns: $('dispatch-runs'),
      dispatchRun: $('dispatch-run'),
    };
    this.ui.dispatchRun?.addEventListener('click', () => this.runDispatch());
    this.ui.back?.addEventListener('click', () => this.select(null));
    this.ui.address && (this.ui.address.value = localStorage.getItem('ctlo.address') || '');
    this.ui.address?.addEventListener('change', () => localStorage.setItem('ctlo.address', this.ui.address.value));
    this.ui.tabs?.addEventListener('click', (e) => {
      const tab = e.target.dataset?.tab;
      if (tab) { this.tab = tab; this.renderDetail(); }
    });
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
      if (this.selected) {
        this.selected = this.list.find((t) => key(t) === key(this.selected)) || null;
      }
      this.selected ? this.renderDetail() : this.renderList();
    } catch {
      this.ui.panel.hidden = true;      // prototype server: no terminals endpoint
    }
  }

  /**
   * Pulls the dispatcher's view. A plain GET only looks: the mod answers with what is waiting and
   * the runs it would set up, and the trains stay put until "Send all ready" is pressed.
   */
  async refreshDispatch(run = false) {
    try {
      const res = await fetch('/api/dispatch', run
        ? { method: 'POST', body: JSON.stringify({ run: true }) } : undefined);
      if (!res.ok) { this.dispatch = null; this.renderDispatch(); return; }
      const data = await res.json();
      this.dispatch = { auto: !!data.autoDispatch, ...(data.status || {}) };
      this.renderDispatch();
      this.paintDestinations();
    } catch {
      this.dispatch = null;             // prototype server: no dispatch endpoint
      this.renderDispatch();
    }
  }

  async runDispatch() {
    this.setStatus('Dispatching…', true);
    await this.refreshDispatch(true);
  }

  renderDispatch() {
    const box = this.ui.dispatch;
    if (!box) return;
    const d = this.dispatch;
    if (!d || !d.runs) { box.hidden = true; return; }
    box.hidden = false;
    this.ui.dispatchRun.disabled = !d.mayDispatch || !d.readyRuns;
    this.ui.dispatchRun.title = d.mayDispatch ? '' : 'Needs operator rights';
    this.ui.dispatchSummary.textContent = d.runs.length
      ? `${d.waitingPackages} package(s) waiting · ${d.readyRuns} ready · ${d.blockedRuns} blocked`
        + (d.auto ? ' · auto dispatch on' : '')
      : 'Nothing waiting' + (d.auto ? ' · auto dispatch on' : '');
    this.ui.dispatchRuns.innerHTML = d.runs.slice(0, 40).map((r) => `<div class="run${
        r.possible ? '' : ' blocked'}" title="${esc(r.stops.join(' → ') || r.problem)}">
        <span class="sname">${esc(r.possible ? r.train : r.pickup)} → ${esc(r.address)}</span>
        <span class="scount">${r.packages}</span>
        <span class="rnote">${esc(r.possible ? 'ready' : r.problem)}</span>
      </div>`).join('');
    for (const [i, el] of [...this.ui.dispatchRuns.querySelectorAll('.run')].entries()) {
      const address = d.runs[i]?.address;
      el.addEventListener('click', () => {
        const target = this.list.find((t) => t.address === address);
        if (target) this.select(target);
      });
    }
  }

  /** Terminals that something is waiting for get a marked label, so the map shows the demand. */
  paintDestinations() {
    const wanted = new Set((this.dispatch?.runs || []).map((r) => r.address));
    for (const t of this.list) {
      this.labels.get(key(t))?.classList.toggle('wanted', wanted.has(t.address));
    }
  }

  rebuildMarkers() {
    this.markers.clear();
    for (const el of this.labels.values()) el.remove();
    this.labels.clear();
    // An outline around the block itself, not a solid cube floating over it: the terminal is
    // already drawn by the terrain with its own texture, so covering it up hid the thing we're
    // pointing at.
    const geo = new THREE.EdgesGeometry(new THREE.BoxGeometry(1.02, 1.02, 1.02));
    const mat = new THREE.LineBasicMaterial({ color: MARKER_COLOR, depthTest: false, transparent: true, opacity: 0.95 });
    for (const t of this.list) {
      const mesh = new THREE.LineSegments(geo, mat);
      mesh.position.set(t.x + 0.5, t.y + 0.5, t.z + 0.5);
      mesh.renderOrder = 999;
      this.markers.add(mesh);
      const label = document.createElement('div');
      label.className = 'map-label';
      label.textContent = t.name + (t.address ? ` · ${t.address}` : '');
      label.addEventListener('click', () => this.select(t));
      this.ui.labels.appendChild(label);
      this.labels.set(key(t), label);
    }
    this.paintDestinations();
  }

  /** Keeps the HTML labels sitting over their markers. */
  updateLabels() {
    if (!this.list.length) return;
    const w = innerWidth / 2, h = innerHeight / 2;
    for (const t of this.list) {
      const el = this.labels.get(key(t));
      if (!el) continue;
      this.tmp.set(t.x + 0.5, t.y + 1.2, t.z + 0.5).project(this.camera);
      const visible = this.tmp.z < 1;
      el.style.display = visible ? 'block' : 'none';
      if (visible) {
        el.style.left = `${(this.tmp.x * w) + w}px`;
        el.style.top = `${(-this.tmp.y * h) + h}px`;
      }
    }
  }

  renderList() {
    this.ui.detail.hidden = true;
    this.ui.list.hidden = false;
    this.renderDispatch();
    this.ui.list.innerHTML = this.list.length
      ? this.list.map((t, i) => `<button class="terminal-row" data-i="${i}">
           <span class="tname">${esc(t.name)}</span>
           <span class="tmeta">${esc(t.address || 'no address')} · ${t.stock.length} items${
             t.settings?.autoDispatch ? ' · auto' : ''}</span>
         </button>`).join('')
      : '<div class="tmeta">No terminals yet. Place one and right-click it with a tuned Stock Link.</div>';
    for (const button of this.ui.list.querySelectorAll('.terminal-row')) {
      button.addEventListener('click', () => this.select(this.list[+button.dataset.i]));
    }
  }

  select(terminal) {
    this.selected = terminal;
    this.tab = 'stock';
    terminal ? this.renderDetail() : this.renderList();
  }

  renderDetail() {
    const t = this.selected;
    if (!t) return;
    this.ui.list.hidden = true;
    this.ui.detail.hidden = false;
    if (this.ui.dispatch) this.ui.dispatch.hidden = true;
    this.ui.title.textContent = t.name + (t.address ? ` · ${t.address}` : '');
    const isAdmin = t.access === 'ADMIN';
    this.ui.tabs.innerHTML = ['stock', 'trade', isAdmin && 'settings'].filter(Boolean)
      .map((tab) => `<button class="tab${this.tab === tab ? ' on' : ''}" data-tab="${tab}">${tab}</button>`).join('');
    if (this.tab === 'stock') this.renderStock();
    else if (this.tab === 'trade') this.renderTrade();
    else this.renderSettings();
  }

  renderStock() {
    const t = this.selected;
    const canOrder = t.access === 'ORDER' || t.access === 'ADMIN';
    this.ui.body.innerHTML = `
      <input id="stock-search" placeholder="search stock">
      <div id="stock-rows"></div>`;
    const rows = () => {
      const filter = ($('stock-search').value || '').toLowerCase();
      const list = t.stock.filter((s) => !filter || s.name.toLowerCase().includes(filter)
        || s.item.toLowerCase().includes(filter)).slice(0, 200);
      $('stock-rows').innerHTML = list.length ? list.map((s) => `<div class="stock-row">
          <span class="sname" title="${esc(s.item)}">${esc(s.name)}</span>
          <span class="scount">${s.count.toLocaleString()}</span>
          ${canOrder ? `<input class="sqty" type="number" min="1" value="1" data-item="${esc(s.item)}">
          <button class="sorder" data-item="${esc(s.item)}">Order</button>` : ''}
        </div>`).join('') : '<div class="tmeta">Nothing in stock, or the network is unloaded.</div>';
      for (const b of $('stock-rows').querySelectorAll('.sorder')) {
        b.addEventListener('click', () => this.order(b.dataset.item));
      }
    };
    $('stock-search').addEventListener('input', rows);
    rows();
  }

  renderTrade() {
    const t = this.selected;
    const listings = t.settings?.listings || [];
    this.ui.body.innerHTML = listings.length
      ? listings.map((l) => {
          const have = t.stock.find((s) => s.item === l.item);
          return `<div class="stock-row">
            <span class="sname" title="${esc(l.item)}">${esc(l.item.split(':').pop().replace(/_/g, ' '))}</span>
            <span class="scount">${l.price ? l.price + '¢ each' : 'free'}${have ? ` · ${have.count} in stock` : ''}</span>
            <input class="sqty" type="number" min="1" value="1" data-item="${esc(l.item)}">
            <button class="sorder" data-item="${esc(l.item)}">Buy</button>
          </div>`;
        }).join('')
      : '<div class="tmeta">Nothing offered for trade here. The owner can add listings under settings.</div>';
    for (const b of this.ui.body.querySelectorAll('.sorder')) {
      b.addEventListener('click', () => this.order(b.dataset.item));
    }
  }

  renderSettings() {
    const t = this.selected;
    const s = t.settings || {};
    this.ui.body.innerHTML = `
      <label class="f"><span>Name</span><input id="set-name" value="${esc(t.name)}"></label>
      <label class="f"><span>Address</span><input id="set-address" value="${esc(t.address)}" placeholder="PD-C01-B04"></label>
      <label class="f"><span>Role</span><select id="set-role">${ROLES.map((r) =>
        `<option${s.role === r ? ' selected' : ''}>${r}</option>`).join('')}</select></label>
      <label class="f"><span>Other side</span><input id="set-interchange" value="${esc(s.interchangeStation || '')}"
        placeholder="Woodbury Station (post office only)"></label>
      <label class="f"><span>Public access</span><select id="set-public">${ACCESS.map((a) =>
        `<option${s.publicAccess === a ? ' selected' : ''}>${a}</option>`).join('')}</select></label>
      <div class="fhead">Dispatch</div>
      <label class="f"><span>Auto dispatch</span><input id="set-auto" type="checkbox"${s.autoDispatch ? ' checked' : ''}></label>
      <label class="f"><span>Batch size</span><input id="set-batch" type="number" min="1" value="${s.batchSize ?? 1}"></label>
      <label class="f"><span>Max wait (s)</span><input id="set-wait" type="number" min="0" value="${s.maxWaitSeconds ?? 120}"></label>
      <label class="f"><span>Priority</span><input id="set-priority" type="number" value="${s.priority ?? 0}"></label>
      <div class="fhead">Trade listings <button id="add-listing" class="mini">+</button></div>
      <div id="listings"></div>
      <div class="fhead">Supply rules <button id="add-rule" class="mini">+</button></div>
      <div id="rules"></div>
      <div class="fhead">People <button id="add-person" class="mini">+</button></div>
      <div id="people"></div>
      <button id="save-settings">Save settings</button>`;

    const listings = [...(s.listings || [])];
    const rules = [...(s.supplyRules || [])];
    const people = [...(s.players || [])];
    const drawLists = () => {
      $('listings').innerHTML = listings.map((l, i) => `<div class="frow">
        <input data-l="${i}" data-k="item" value="${esc(l.item)}" placeholder="minecraft:iron_ingot">
        <input data-l="${i}" data-k="price" type="number" value="${l.price || 0}" title="price each">
        <input data-l="${i}" data-k="maxPerOrder" type="number" value="${l.maxPerOrder || 0}" title="max per order">
        <button class="mini" data-del-l="${i}">x</button></div>`).join('')
        || '<div class="tmeta">None: everything is refused to other players.</div>';
      $('rules').innerHTML = rules.map((r, i) => `<div class="frow">
        <input data-r="${i}" data-k="item" value="${esc(r.item)}" placeholder="minecraft:iron_ingot">
        <input data-r="${i}" data-k="keepStocked" type="number" value="${r.keepStocked || 0}" title="keep this many">
        <select data-r="${i}" data-k="sourceNetwork">${this.terminalOptions(r.sourceNetwork)}</select>
        <button class="mini" data-del-r="${i}">x</button></div>`).join('')
        || '<div class="tmeta">None.</div>';
      $('people').innerHTML = people.map((p, i) => `<div class="frow">
        <input data-p="${i}" data-k="name" value="${esc(p.name || '')}" placeholder="player name">
        <select data-p="${i}" data-k="access">${ACCESS.map((a) =>
          `<option${p.access === a ? ' selected' : ''}>${a}</option>`).join('')}</select>
        <button class="mini" data-del-p="${i}">x</button></div>`).join('')
        || '<div class="tmeta">Nobody listed: others get the public access above.</div>';
      bind();
    };
    const bind = () => {
      for (const el of this.ui.body.querySelectorAll('[data-l]')) {
        el.addEventListener('input', () => { listings[+el.dataset.l][el.dataset.k] =
          el.type === 'number' ? +el.value : el.value; });
      }
      for (const el of this.ui.body.querySelectorAll('[data-r]')) {
        el.addEventListener('input', () => { rules[+el.dataset.r][el.dataset.k] =
          el.type === 'number' ? +el.value : el.value; });
      }
      for (const el of this.ui.body.querySelectorAll('[data-p]')) {
        el.addEventListener('input', () => { people[+el.dataset.p][el.dataset.k] = el.value; });
      }
      for (const el of this.ui.body.querySelectorAll('[data-del-l]')) {
        el.addEventListener('click', () => { listings.splice(+el.dataset.delL, 1); drawLists(); });
      }
      for (const el of this.ui.body.querySelectorAll('[data-del-r]')) {
        el.addEventListener('click', () => { rules.splice(+el.dataset.delR, 1); drawLists(); });
      }
      for (const el of this.ui.body.querySelectorAll('[data-del-p]')) {
        el.addEventListener('click', () => { people.splice(+el.dataset.delP, 1); drawLists(); });
      }
    };
    $('add-listing').addEventListener('click', () => { listings.push({ item: '', price: 0, maxPerOrder: 0 }); drawLists(); });
    $('add-rule').addEventListener('click', () => { rules.push({ item: '', keepStocked: 0, sourceNetwork: '' }); drawLists(); });
    $('add-person').addEventListener('click', () => { people.push({ name: '', access: 'ORDER' }); drawLists(); });
    $('save-settings').addEventListener('click', () => this.saveSettings(listings, rules, people));
    drawLists();
  }

  /** Supply rules pick their source by terminal; the server stores that terminal's network id. */
  terminalOptions(selected) {
    return ['<option value="">(pick a source terminal)</option>'].concat(this.list
      .filter((t) => t !== this.selected && t.settings?.network)
      .map((t) => `<option value="${esc(t.settings.network)}"${
        t.settings.network === selected ? ' selected' : ''}>${esc(t.name)}</option>`)).join('');
  }

  async saveSettings(listings, rules, people) {
    const t = this.selected;
    const body = {
      x: t.x, y: t.y, z: t.z,
      name: $('set-name').value, address: $('set-address').value,
      role: $('set-role').value, interchangeStation: $('set-interchange').value,
      publicAccess: $('set-public').value,
      autoDispatch: $('set-auto').checked, batchSize: +$('set-batch').value,
      maxWaitSeconds: +$('set-wait').value, priority: +$('set-priority').value,
      listings: listings.filter((l) => l.item), supplyRules: rules.filter((r) => r.item), players: people.filter((p) => p.name),
    };
    this.setStatus('Saving…', true);
    try {
      const res = await fetch('/api/terminals/settings', { method: 'POST', body: JSON.stringify(body) });
      if (!res.ok) this.setStatus('Save rejected', false);
    } catch (e) {
      this.setStatus('Save failed: ' + e.message, false);
    }
  }

  async order(item) {
    const t = this.selected;
    const qty = this.ui.body.querySelector(`.sqty[data-item="${cssEscape(item)}"]`);
    const count = Math.max(1, parseInt(qty?.value || '1', 10));
    const address = (this.ui.address.value || '').trim();
    if (!address) { this.setStatus('Enter a delivery address first', false); return; }
    this.setStatus(`Ordering ${count} x ${item.split(':').pop()}…`, true);
    try {
      const res = await fetch('/api/orders', {
        method: 'POST',
        body: JSON.stringify({ x: t.x, y: t.y, z: t.z, item, count, address }),
      });
      if (!res.ok) this.setStatus('Order rejected', false);
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
const esc = (s) => String(s ?? '').replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
const cssEscape = (s) => String(s).replace(/["\\]/g, '\\$&');
