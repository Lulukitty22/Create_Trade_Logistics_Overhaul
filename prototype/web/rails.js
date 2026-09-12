// Create's railway drawn over the terrain: the track itself, the stations on it, and the trains
// running on it right now.
//
// The data comes from /api/rails, which the mod reads straight out of Create on the client - Create
// syncs its track graphs so it can draw the rails itself, so nothing here needs the server.
//
// Track geometry changes rarely and is rebuilt only when it actually differs; train positions change
// constantly and are polled on their own, quicker, timer.
import * as THREE from 'three';

const TRACK_COLOR = 0x3d3a34;
const STATION_COLOR = 0x6fa8dc;
const TRAIN_IDLE = 0x9aa1b2;
const TRAIN_BUSY = 0x4cd17a;

export class Rails {
  constructor(scene, camera) {
    this.camera = camera;
    this.group = new THREE.Group();
    this.trainGroup = new THREE.Group();
    this.group.add(this.trainGroup);
    scene.add(this.group);
    this.trackSignature = '';
    this.stations = [];
    this.trains = [];
    this.visible = true;
    this.tmp = new THREE.Vector3();
    this.trainGeometry = new THREE.SphereGeometry(0.9, 8, 6);
  }

  setVisible(visible) {
    this.visible = visible;
    this.group.visible = visible;
  }

  async refresh() {
    if (!this.visible || this.unsupported) return;
    try {
      const res = await fetch('/api/rails');
      if (!res.ok) { this.unsupported = true; return; }   // the Python stand-in has no railway
      const data = await res.json();
      this.drawTracks(data.tracks || []);
      this.stations = data.stations || [];
      this.drawTrains(data.trains || []);
    } catch {
      this.unsupported = true;      // nothing serving a railway; stop asking
    }
  }

  /** Rebuilds the track lines, but only when the geometry has actually changed. */
  drawTracks(tracks) {
    const signature = tracks.length + ':' + tracks.reduce((n, t) => n + t.length, 0);
    if (signature === this.trackSignature) return;
    this.trackSignature = signature;

    if (this.trackMesh) {
      this.group.remove(this.trackMesh);
      this.trackMesh.geometry.dispose();
    }
    // One LineSegments for the whole railway: a separate object per edge would cost a draw call each.
    const points = [];
    for (const flat of tracks) {
      for (let i = 0; i + 5 < flat.length; i += 3) {
        points.push(flat[i], flat[i + 1] + 0.2, flat[i + 2]);
        points.push(flat[i + 3], flat[i + 4] + 0.2, flat[i + 5]);
      }
    }
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(points, 3));
    this.trackMesh = new THREE.LineSegments(geometry,
      new THREE.LineBasicMaterial({ color: TRACK_COLOR }));
    this.trackMesh.renderOrder = 5;
    this.group.add(this.trackMesh);
  }

  drawTrains(trains) {
    this.trains = trains;
    // Trains come and go rarely; rebuild the markers only when the count changes.
    if (this.trainGroup.children.length !== trains.length) {
      this.trainGroup.clear();
      for (let i = 0; i < trains.length; i++) {
        const mesh = new THREE.Mesh(this.trainGeometry, new THREE.MeshBasicMaterial({
          color: TRAIN_IDLE, depthTest: false, transparent: true, opacity: 0.95,
        }));
        mesh.renderOrder = 998;
        this.trainGroup.add(mesh);
      }
    }
    trains.forEach((train, i) => {
      const mesh = this.trainGroup.children[i];
      if (!mesh) return;
      mesh.position.set(train.x, train.y + 1.2, train.z);
      mesh.material.color.setHex(train.busy ? TRAIN_BUSY : TRAIN_IDLE);
    });
  }

  /** Station names and train names drawn as HTML, like the terminal labels. */
  labelTargets() {
    if (!this.visible) return [];
    const out = [];
    for (const s of this.stations) {
      out.push({ key: 'st:' + s.name, text: s.name, x: s.x + 0.5, y: s.y + 1.4, z: s.z + 0.5, kind: 'station' });
    }
    for (const t of this.trains) {
      out.push({ key: 'tr:' + t.name, text: t.name, x: t.x, y: t.y + 2.4, z: t.z, kind: 'train' });
    }
    return out;
  }
}
