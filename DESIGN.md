# Logistics Map: Design Notes

A web page for **monitoring and interacting with Create logistics on a 3D map** of a Minecraft world. For example: see what a town's network has in stock, order resources from it, and have them delivered through the package and postbox network. The terrain comes from the player's own Voxy LOD cache.

Status: design + prototype. Nothing here is final; see [Decisions log](#decisions-log) and [Open questions](#open-questions).

---

## Design ideals

1. **One method, local only.** The client mod serves the web page on `127.0.0.1`. There is no GitHub Pages, no access tokens, no public ports and no LAN mode. Minecraft must be running to use the map. That's acceptable, because real-time data and issuing orders need the game anyway. Open a browser tab on the same PC.
2. **The server is the authority.** All logistics state and every action lives in the server mod. The client mod is a bridge. The browser holds no truth: it renders what it's told and sends requests.
3. **Not omnipotent.** The map only sees and controls what has been deliberately connected through in-world blocks (the [Access Panel](#access-panel-block-concept)). Visibility follows Create's own mechanics, such as stock links, rather than reading the whole world.
4. **Identity comes from Minecraft.** Requests travel over the player's existing Minecraft connection, so the server always knows the player's UUID. Permissions are checked there, never in the browser.
5. **Build with Create, not around it.** Use stock links, logistics networks, packages and postboxes. When something is missing, the mod adds a block or feature for it. It's our mod, so we can.
6. **The terrain is the player's own.** It's read from the local Voxy cache, so each player sees only what they've explored.
7. **We own the stack.** The modpack always ships the mod on both client and server, so there's no need to support mixed clients. Versions are pinned. Voxy has no API, so we read its raw files from disk instead (see [Voxy storage format](#voxy-storage-format-voxy-029-alpha)).
8. **The prototype and the mod share one contract.** The Python stand-in serves the same local HTTP API the client mod will. The web page can't tell them apart, so the page can be built before the mod exists.

---

## Architecture

```
Browser  (http://127.0.0.1:PORT; page files packed inside the mod jar)
   │  HTTP + live event stream (localhost only)
Client mod ──reads raw files──▶ Voxy LOD database (on this PC)
   │  custom packets over the normal Minecraft connection
Server mod ──reads / drives──▶ Create (logistics networks, packages, postboxes; later trains)
```

In singleplayer, the server mod runs inside the integrated server. It's the same code path.

### Web page (browser)
- Renders terrain in 3D (three.js). Meshing happens in Web Workers, and each area uses a Voxy LOD level based on camera distance.
- Draws overlays on the terrain: Access Panels, logistics networks, stock, and later postboxes, tracks, trains and Aeronautics craft.
- Has UI panels for stock, orders and trade.
- Keeps no authoritative state. It reconnects to the event stream and redraws.

### Client mod
- Runs a local HTTP server bound to `127.0.0.1` only, serving:
  - the page's static files (bundled in the jar, including three.js, so it works offline),
  - terrain data read from Voxy's files for the world or server currently connected,
  - a live event stream (Server-Sent Events or WebSocket), relayed from the server,
  - an action endpoint that forwards requests to the server as packets.
- Subscribes to server updates only while a page is open.

### Server mod
- Owns the Access Panel block and its permissions.
- Reads logistics state through the stock links attached to Access Panels.
- Executes actions such as orders, after a permission check against the requesting player's UUID.
- Sends throttled updates to subscribed players only.

### Python stand-in (prototype only)
- `serve.py` serves the same API as the client mod, backed by a snapshot copy of a save's Voxy database.
- Used to develop the renderer and page before any mod code exists.

---

## Mod design: Create Trade Logistics Overhaul

Working name: **Create Trade Logistics Overhaul**.

### The problem it solves

Today, on the user's city-grid save:
- Delivery vans (trains under the roads) must visit **every** building door-to-door on a fixed loop, because nothing can task them on demand.
- Every stop is entered by hand, twice per building, because the van has to back into each stall: `PD-C01-B01 REV`, then `PD-C01-B01`, and so on.
- Intercity freight only moves because a post office sorts it by hand-built filter gates, and long-haul trains also run fixed loops.
- Stock links can't restock **another** network, e.g. "network B keeps 5 stacks of X, supplied from network A".
- Airship (Sable) stock networks are islands: there's no way for a docked ship and a ground network to trade.

The mod's core is therefore a **dispatcher**, and the 3D map is how you watch and steer it.

### Addresses, from the user's scheme

`PD-C01-B04` = Package Delivery, City 01, Building 04. Each building has a postbox with that address, a station, and usually a reverse-point station. The city's post office (`PD-C01-B01`) accepts `*` and bridges to the intercity station (e.g. "Woodbury Station").

### What the mod discovers by itself

No manual wiring for the common case, because Create already knows:
- every **station**, and which **track graph** it belongs to (`GlobalRailwayManager.trackNetworks`, `TrackGraph.getPoints(STATION)`),
- every **postbox attached to a station**, with its address (`GlobalStation.connectedPorts`),
- so **address → station → track graph** is known, and "can a van reach B from A?" is just "same graph?".
- **Reverse points:** a station named `<name> REV` (pattern configurable) is inserted as a stop before the real one. This replaces typing every stop twice.

### Conventions the user already follows (and the mod relies on)

- **Station names equal package addresses**, e.g. the station serving `PD-C01-B04` is named `PD-C01-B04`. That makes address → station lookup exact rather than guesswork.
- **Reverse points** are a second station of the same address plus a suffix (` REV`).
- **The post office** of each city takes `*` and bridges to the intercity station.

### What the mod deliberately does NOT do

- It does not replace stock links, factory gauges, packagers or postboxes; Create already does demand, packaging and addressing, including across networks.
- It does not teleport items. Everything still physically travels.
- The gap it fills is **transport tasking** (plus the map, permissions and trade).

### Blocks and concepts

1. **Logistics Panel** (new block). Tuned to one logistics network by right-clicking a stock link, like a Stock Ticker. Holds:
   - a **name** ("Casings Factory") and its **primary address** (`PD-C01-B04`),
   - role: producer, consumer, warehouse, post office,
   - **dispatch settings** (below),
   - **trade listings**: what it exports and imports, with optional prices,
   - **permissions**, and its station / reverse point when they can't be detected.
2. **Interchange.** Only needed where the mod *can't* see the connection, because the hand-off is the player's own belts and chutes. A post office panel declares "my other side is Woodbury Station". Ordinary buildings declare nothing.
3. **Couriers.** Trains registered as delivery vehicles: home stall, the track graph they serve, capacity, priority. Create's pathfinder already reverses trains that have controls at both ends, which the user's van does.
4. **Jobs.** A package waiting in a postbox, an order from the map, a supply rule, or a Redstone Requester exactly as today.
5. **Dispatcher.** Plans a route as legs (one per track graph, joined at interchanges), then builds a schedule per leg — reverse point, destination, fetch/deliver packages, wait conditions — and assigns it with `ScheduleRuntime.setSchedule`. Each run ends back at the courier's stall.
6. **Supply rules (optional convenience).** Cross-network standing orders — "keep 5 stacks of X here, sourced from network Y" — configured in the panel instead of built from gauges.
   - This duplicates what a **factory gauge pair already does** in 6.0.10 (tune the input panel to the other network). It exists only so a rule can be set without building a board, and so the map can show and edit it.
   - The user prefers keeping the gauge workflow as the simple, visual option; the mod must not replace it.
7. **Trade.** Export/import lists with optional prices.

### Dispatcher mechanics (how a run is actually built)

- **Package census.** `FetchPackagesInstruction.start()` walks every station in a track graph, reads each `connectedPorts` entry and its `offlineBuffer`, and matches package addresses against a filter. The mod can read the same data, so it knows **what is waiting where even in unloaded chunks**, with no chunk loading.
- **Transfers need the instructions.** Packages move between a station's postbox and a train only through `FetchPackagesInstruction` / `DeliverPackagesInstruction`. Simply stopping at a station does nothing, so generated schedules must use them.
- **Reverse points pair with them:** `Destination "<address> REV"`, then `Fetch packages "<address>"`. Addresses are unique, so the fetch resolves to the intended station, and the train arrives having already reversed.
- **Re-task per leg, don't write one long schedule.** The dispatcher assigns a short schedule (reverse point → stop → fetch/deliver), watches `ScheduleRuntime` for completion, then assigns the next leg.
  - This keeps exact control of stop order, which matters because `DeliverPackages` otherwise picks its own matching station and could break REV pairing.
  - It also lets the plan change mid-run when a new, higher-priority job appears.
- **Courier state** lives in the mod's saved data: idle → assigned → en route → returning, plus handling for trains that are missing, derailed or already carrying a player's own schedule.

### Dispatch triggers (per panel, combinable)

- **Batch:** wait until N packages are waiting.
- **Time:** dispatch anyway after N minutes.
- **Priority:** a weight that makes a job jump the queue.

### Worked example: `PD-C01-B04` → `PD-C02-B10`

1. B04's postbox holds a package addressed `PD-C02-B10`.
2. The dispatcher looks up the destination: its station is on a different track graph, so a direct run is impossible.
3. It plans legs: **B04 → post office `PD-C01-B01`** (city graph), **Woodbury Station → Elder City Station** (long-haul graph), **post office `PD-C02-B01` → B10** (other city's graph). The two interchanges are the post offices, which the panels declare.
4. For the first leg it picks a free city van and gives it a schedule: `PD-C01-B04 REV`, `PD-C01-B04`, fetch packages, `PD-C01-B01 REV`, `PD-C01-B01`, deliver packages, then home. **No other building is visited.**
5. The post office's existing sorting gates move the package onto the long-haul side, and the next legs are dispatched the same way.

### Airships (Sable / Aeronautics) — deferred, on the backburner

Airships have stock networks but no track graph, so they can't be discovered the same way.
- Likely approach: pair a ship's panel with a ground panel at a **dock**, using Aeronautics' docking connector blocks or proximity, and treat the pair as an interchange while docked.
- The same mechanism would let a docked ship's network trade with the ground network.
- Needs investigation of Sable/Aeronautics internals first.

### Payments (trade)

- **Numismatics (cards/accounts):** funds are **held when the order is placed** and transferred **when the package is delivered**.
- **Physical goods barter:** both sides dispatch at the same time; there's no escrow, because the items are physically in transit.

## Create 6.0.10 logistics internals (from the jar)

This was read with `javap` from `create-1.21.1-6.0.10.jar`. These are internal classes, not a stable API.

- **Logistics network:** a `UUID` (the "frequency"). Every stock link, stock ticker and redstone requester tuned to it shares `LogisticallyLinkedBehaviour.freqId`.
- **`GlobalLogisticsManager`** (server) keeps `LogisticsNetwork{id, owner, locked, totalLinks, loadedLinks}`.
  - `totalLinks` and `loadedLinks` are sets of `GlobalPos`, so every link's position is known.
  - `mayInteract(net, player)` and `mayAdministrate(net, player)` implement Create's own owner/lock rules.
- **Stock:** `LogisticsManager.getSummaryOfNetwork(net, accurate)` returns an `InventorySummary` of item → `BigItemStack` counts.
  - Only **loaded** links contribute; `getUnloadedLinkCount` reports the rest.
- **Orders:** `LogisticsManager.broadcastPackageRequest(net, RequestType.PLAYER, PackageOrderWithCrafts, ignoredInventory, address)`.
  - An order is a list of `BigItemStack`s plus a destination **address string**.
  - Packagers on the network pack the items, and Create's transport carries the packages: frogports, chain conveyors, trains.
- **`StockCheckingBlockEntity`** is the abstract base of the Stock Ticker. It gives a block a `LogisticallyLinkedBehaviour` plus `getRecentSummary()`, `getAccurateSummary()` and `broadcastPackageRequest(...)`. It's the natural base for our own block.
- **Addresses:** `PackagePortBlockEntity.addressFilter` covers postboxes and frogports.
  - A postbox next to a train station registers with it: `GlobalStation.connectedPorts: BlockPos → GlobalPackagePort{address, offlineBuffer}`.
  - Stations are global (not chunk-bound), and a port's `offlineBuffer` lets trains exchange packages while the chunk is unloaded.
- **ComputerCraft** (Create's own peripherals) exposes the same small surface:
  - stock ticker: `stock()` and `requestFiltered(address, …)`
  - postbox, frogport and packager: get/set address
  - redstone requester: set a request and trigger it

- **Trains and scheduling** (this is what makes dispatching possible):
  - `Train{id, owner, name, runtime, navigation, doubleEnded, currentlyBackwards, carriages, …}`; `ScheduleRuntime.setSchedule(schedule, isAuto)` is public, so a mod can build and assign a schedule at runtime.
  - A `Schedule` is a list of entries, each an instruction plus wait conditions.
  - Instructions: `DestinationInstruction` (station name filter, supports wildcards), `FetchPackagesInstruction` (address filter), `DeliverPackagesInstruction`, throttle and title.
  - Conditions: idle cargo, cargo/item/fluid threshold, delay, timed wait, time of day, station powered, station unloaded, redstone link, player passenger.
  - **Reversing:** `Navigation` path search checks `hasForwardConductor()` / `hasBackwardConductor()` and tracks `destinationBehindTrain`, so a train with controls at both ends can be routed in reverse (e.g. backing into a stall). What Create lacks is on-demand tasking, not reversing.
- **Factory gauges (factory board) — important for cross-network supply:**
  - Each panel on a gauge has its **own** `network` UUID (tuned with a stock link item), a filter item, a `recipeAddress` to deliver to, input connections (`targetedBy`) and a promise queue.
  - `tickRequests()` groups each ingredient by **the source panel's network**, then calls `getSummaryOfNetwork` / `findPackagersForRequest` on *that* network with **this** panel's `recipeAddress`.
  - **So requesting across networks already works in 6.0.10**: an input panel tuned to network A supplies a panel on network B, and the goods are packaged and addressed to B.
  - Connection validation (`checkForIssues`) rejects only physical problems — already connected, too many inputs, same orientation/surface, no item, restock mode, and **16 blocks apart** — there is *no* same-network check.
  - A network is only a UUID, so a gauge can be tuned to a far-away city's network; distance never limits the request, only the gauge-to-gauge arrow.
  - **Conclusion:** Create already handles demand and packaging across networks. What's missing is *transport tasking*, which is what this mod adds.
- **Numismatics** (for a later trade layer) has `BankAccount`, `GlobalBankManager` and `BankSavedData`, so payments between accounts can be made in code.

## Create internals the dispatcher depends on (learned the hard way)

Each of these cost a failed in-game test. Upstream source is worth cloning (`reference/`, gitignored)
rather than guessing from decompiled signatures.

**A schedule entry with no wait conditions never advances.** `ScheduleRuntime.tickConditions`
increments `currentEntry` from *inside* its loop over the entry's conditions, so an empty list means
the train reaches that stop and stays there for good. `DestinationInstruction`,
`FetchPackagesInstruction` and `DeliverPackagesInstruction` all report `supportsConditions() == true`,
so every entry needs one. Station stops use `IdleCargoCondition` (3s), reverse points `ScheduledDelay`
(1s) - the same conditions a player picks in the schedule screen.

**Fetch and deliver route themselves.** Both scan every station on the graph for one holding a
matching package and call `train.navigation.findPathTo(...)`. A schedule of just those two therefore
sends the train wherever Create fancies, which is the opposite of dispatching. Both stations are
named explicitly so the schedule matches the plan the map showed.

**Deliver fails silently with an empty train.** If the train carries no packages,
`DeliverPackagesInstruction.start` sets `PRE_TRANSIT` and advances past itself rather than reporting
anything - so a fetch that loaded nothing ends the run with no error at all.

**Both package instructions need a conductor**, or they call `missingConductor()` and cool down
forever. The plan reports this rather than dispatching a train that cannot work.

**A linked block must implement `IBE`.** `LogisticallyLinkedBlockItem.assignFrequency` casts the
item's block to it, so a block that doesn't implement it can never be tuned - the item just places
instead, with no error.

**Every linked block starts on its own network.** `LogisticallyLinkedBehaviour`'s constructor does
`freqId = UUID.randomUUID()`, so "has a network id" means nothing and there is no untuned state.
Linked means *something else is on the same network*. Any migration that tests for a null frequency
is dead code.

**Create's API leaks its bundled libraries' types** - `SmartBlockEntity` implements Ponder's
`VirtualBlockEntity` - so Create's jar-in-jar libraries have to be unpacked onto the compile
classpath. `build.gradle` does this automatically.

## Measuring the mod's own cost

`GET /api/perf` reports how long each piece of the mod's work takes and, in the name, which thread it
happened on. That distinction is the point: work on the server or client thread delays the game,
work on a web thread only delays the browser. `POST` to the same path resets the counters.

It exists because a stutter got blamed on this mod twice on circumstantial timing. The numbers said
otherwise - the whole mod accounted for about 0.19s of work across four and a half minutes - and the
real cause turned out to be a Modrinth pack update resetting the instance's heap from 12GB to 6GB,
which had a 169-mod pack full-GCing every twenty seconds. The game's own log said so plainly:
`Can't keep up! Running 17160ms or 343 ticks behind` on the server thread.

Reach for `/api/perf` and `logs/latest.log` before changing anything on a hunch.

## Local HTTP API (draft)

The client mod and the Python stand-in both implement this. It'll be refined during the renderer experiments.

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | Web page (static files) |
| GET | `/api/world` | World info: name, start position, max LOD, palette with colours, names, biomes, live status |
| GET | `/api/palette` | Palette only (re-fetched when it grows live) |
| GET | `/api/assets/textures` | Texture array: `VXA1`, u32 count, u32 size (16), RGBA layers |
| GET | `/api/assets/models` | Baked block models: `VXM1`, u32 first, u32 count, u32 quad offsets, float32 quads |
| GET | `/api/terrain/roots` | Keys of every section at the coarsest LOD (the octree roots) |
| POST | `/api/terrain/sections` | Body: list of `(lod, x, y, z)`. Returns those sections, with missing ones marked (binary, gzip) |
| GET | `/api/events` | Server-Sent Events: `changed` (section keys, palette growth) and `resync` |
| POST | `/api/resync` | Take a fresh full snapshot |
| GET | `/api/terminals` | Every Logistics Terminal the player may see: position, name, address, owner, access, settings, stock |
| POST | `/api/terminals/settings` | Body: terminal position plus the fields to change. Applied if the player is ADMIN there |
| POST | `/api/orders` | Body: terminal position, item, count, address. Places a Create package order |
| GET | `/api/dispatch` | What is waiting and the runs the dispatcher would set up. Looks only |
| POST | `/api/dispatch` | Body `{"run":true}`. Actually hands the schedules to trains (operators only) |

Terminal calls are asynchronous: the page's request goes to the server as a packet and the answer
arrives on `/api/events` (`terminals`, `order`), so the browser never waits on a game tick.

## Terrain pipeline

1. The page fetches only the octree roots, then asks for sections **on demand** as the camera refines the tree. A world of any size streams in; the browser only holds what's near the camera. Each section is 32×32×32 voxels, and one voxel is 2^LOD blocks.
2. The server converts each requested section: global `(block, biome)` palette ids, cave air marked, and all-air or uniform sections stored compactly. Results go into an LRU cache.
3. The browser meshes sections in Web Workers, once the section and its 6 neighbours are loaded. Only faces that touch air are drawn, and neighbouring same-block faces are merged.
4. LOD selection walks down from the coarsest level and swaps a section for its 8 children when the camera is close. This is the same tree Voxy uses. A coarse section stays visible until all its children are meshed.
5. **Live updates:** the server tails Voxy's write-ahead log once a second. Changed section keys go out over `/api/events`, and the page re-fetches and re-meshes only those (and their neighbours' borders), briefly highlighting them.
6. Block colours come from averaging real textures in the Minecraft and mod jars, including jar-in-jar mods. Grass, foliage and water get biome tints from the biome definitions.
5. Block colours come from averaging real textures in the Minecraft and mod jars. Grass, foliage and water get biome tints from the biome definitions.

---

## Voxy storage format (Voxy 0.2.9-alpha)

This was reverse-engineered from the jar (`SaveLoadSystem3`, `WorldEngine`, `Mapper`, `RocksDBStorageBackend`). The format is version-specific and may change in future Voxy releases.

**Location**
- Singleplayer: `saves/<world>/voxy/<hash>/storage/`
- Multiplayer: `<game dir>/.voxy/saves/<server>/<hash>/storage/`
- Each also has a `config.json`, which here says: Serializer → ZSTD (level 1) → RocksDB.

**Database:** RocksDB with column families `world_sections` and `id_mappings`.

**`world_sections` key:** the 64-bit section key stored big-endian. Voxy's `swizzlePos` is currently a no-op, so single sections can be looked up directly. Every value also repeats the raw key.

**Section key (raw 64-bit)**

| Bits | Field |
|---|---|
| 60–63 | LOD level (0–15) |
| 52–59 | y, signed 8-bit |
| 28–51 | z, signed 24-bit |
| 4–27 | x, signed 24-bit |
| 0–3 | unused |

These are section coordinates: a section's world origin is `coord × 32 × 2^lvl` blocks.

**Section value:** ZSTD-compressed (standard frames). Decompressed, it contains, all little-endian:

| Field | Size | Meaning |
|---|---|---|
| key | u64 | raw section key |
| meta | u64 | bits 0–15: palette length; bits 16–23: `nonEmptyChildren` mask |
| indices | u16 × 32768 | palette index per voxel, voxel index = `(y<<10) \| (z<<5) \| x` |
| palette | u64 × palette length | voxel values |

**Voxel value (u64)**

| Bits | Field |
|---|---|
| 56–63 | light |
| 47–55 | biome id (9 bits) |
| 27–46 | block-state id (20 bits) |
| 0–26 | unused |

Block-state id 0 is air.

**Light byte:** the low nibble is **skylight** and the high nibble is **block light**.
- Quirk: sections entirely above the terrain store light 0, not 15, because Minecraft keeps no light data for empty sky sections.
- Cave detection therefore uses *air with skylight 0 **and** something solid above it in the same column*. The prototype uses this for its "show caves" toggle.

**`id_mappings`**
- Key: a big-endian int, `type << 30 | id`. Type 1 is a block state, type 2 is a biome.
- Value: gzip-compressed NBT.
  - Block states: `{id, block_state: {Name, Properties}}`.
  - Biomes: `{id, biome_id: "minecraft:plains"}`.

**Size reference:** the test world has 3,677 sections across LODs 0–4, about 12 MB compressed. Decoding everything in Python takes about 0.8 s.

## Reading Voxy while the game runs

- While a world is open, RocksDB holds `LOCK` exclusively. The other files can still be read.
- **Base snapshot:** mirror every file except `LOCK` into a cache folder and open the copy read-only. RocksDB replays its write-ahead log on open, which works on a live snapshot. SST files never change once written, so after the first copy only new SSTs and the log are copied.
- **Live changes, by tailing the write-ahead log:**
  - Every Voxy write lands in `NNNNNN.log` first. Reading only the new bytes each second gives each changed section's full new value, plus any new block or biome mappings.
  - Tailing starts at the last complete record of the snapshot's copy of the log, so nothing is missed or double-applied.
  - The tailer follows log rotation to the next file.
  - Format: 32 KiB blocks of fragmented records, each holding a WriteBatch. See `voxy_wal.py`.
  - Column family 1 is `world_sections` (ZSTD values) and 2 is `id_mappings` (gzip values).
- **Sharing:** files are opened with Windows share-read/write/delete flags, so we never block the game from writing, rotating or deleting them.
- **Lag:** Voxy saves changed sections a few seconds after ingesting them. On the test world, writes arrived every 2–5 s while the user played. Each change also rewrites the parent sections at LOD 1–4.
- **Rule:** RocksDB never opens the original database, only the mirror. The original files are only ever read with sharing flags.
- **RocksDB "secondary instance" mode** would avoid the mirror entirely. It's untested; worth trying for the mod.

---

## Prototype (Python stand-in + web page)

**Repository layout**

```
DESIGN.md
prototype/   Python stand-in + the web page (web/), shared with the mod
mod/
  workspace/ Gradle project (NeoForge 1.21.1, Java 21) — the mod source
  jars/      built mod jars
  tools/     a local Gradle download (not part of the project)
```

**Run it**
- Test world: `python prototype/serve.py`, then open http://127.0.0.1:8765/.
- Any save: `python serve.py --world "<saves/World Name>" --port 8766`, or `--storage <dir>` for a server cache. Add `--poll 0` to turn live updates off.

| File | Role |
|---|---|
| `voxy_reader.py` | Voxy storage reader (RocksDB + ZSTD + section format), NBT reader |
| `voxy_wal.py` | Live write-ahead-log tailer: shared-read file access, record reassembly, WriteBatch parsing |
| `terrain_store.py` | Incremental snapshot mirror, section index, on-demand conversion and LRU cache, live overlay from the log |
| `block_colors.py` | Asset index (vanilla jar, mod jars, jar-in-jar); flat map colours from averaged textures; biome tints |
| `block_models.py` | Bakes block states into quads and a 16×16 texture array: variants, multipart, element and blockstate rotations, uvlock, cull faces, tint, cube-face texture rotation |
| `serve.py` | Local HTTP API and Server-Sent Events hub; finds a save's Voxy folder; start position from `level.dat` |
| `web/app.js` | three.js page: streaming octree, fetch and mesh queues, live updates with highlight, hover picking, UI |
| `web/mesher.js` | Web Worker that greedy-meshes one section (34³ volume with neighbour border), opaque and water |
| `model_survey.py` | Texture and block-model feasibility survey |
| `proof_topdown.py` | First feasibility proof (top-down PNG) |

**Section wire format (`POST /api/terrain/sections`)**
- Header: `"VXS1"`, u32 count, u32 palette length. If the palette grew, the page re-fetches `/api/palette`.
- Each record:
  - i32 lod, x, y, z
  - u16 palLen, u16 flags
  - u16 palette[palLen], padded to 4 bytes
  - u8 indices[32768], or u16 indices if `flags & 1`
- `palLen`: `0xFFFF` means the section doesn't exist, 0 means all air, and 1 means uniform. None of those carry indices.
- Palette values are global ids: 0 is air, 1 is cave air, and the rest are `(block, biome-if-tinted)` pairs.

**Results (2026-09-10)**

*Test world*
- Grew from 4,300 to about 9,700 full-detail sections while the user played.
- Startup is about 1.7 s.
- Converting a section takes about 1.5 ms; cached ones return instantly.
- Live changes stream in every few seconds, and the page highlights them.

*New World (2)*
- 823 MB of Voxy data: 329,075 full-detail sections, about 382,000 across all levels.
- First start is about 31 s: a 9 s copy plus an 18 s index scan. Later starts only copy new files; the index could also be cached.
- The whole 7,000 × 25,000-block world draws at 60 FPS with only about 1,100 sections loaded in the browser.

*Sable sub-levels*
- The big world has 11 LOD 4 clusters at x = z ≈ 20,500,000. That fits Sable (Aeronautics' physics library), which stores airships as sub-levels in a far-away plot of the same dimension, and Voxy has recorded them.
- This could be used to render ships later, if their transforms can be read.

**Known issues / next experiments**
- Level-of-detail seams: small cracks between levels. The user says this is not an issue.
- The browser never unloads sections. Long flights over the big world will grow memory, so it needs eviction.
- The hidden Browser pane pauses animation frames. `?timer` drives the loop with timers for testing.

## Terminal features (implemented 2026-09-11)

Everything below exists in code and compiles; only the terrain half has been seen running in game.

**The block.** `Logistics Terminal` (`logistics_terminal`), tuned by right-clicking it with a tuned
Stock Link exactly like Create's own blocks. It keeps a `TerminalSettings` in its block entity and
registers itself in a per-level `TerminalRegistry` (a `SavedData`), so the map can list terminals
whose chunks are loaded without scanning the world.

**Settings**, all editable from the map by whoever has ADMIN on that terminal:

| Setting | Meaning |
|---|---|
| Name, Address | What the map shows; the address ties the terminal to its station |
| Role | `PRODUCER` / `CONSUMER` / `WAREHOUSE` / `POST_OFFICE` |
| Other side | For a post office: the station on the far railway, making it an interchange |
| Public access | `NONE` / `VIEW` / `ORDER` / `ADMIN` for everyone not named |
| People | Per-player access, resolved from player names through the server's profile cache |
| Auto dispatch, Batch size, Max wait, Priority | Dispatch triggers for this terminal |
| Trade listings | Item, price each, max per order. Non-owners may only order what is listed |
| Supply rules | Keep N of an item stocked, pulling from a named source terminal's network |

**Permissions** are checked server-side on every action, never in the page: `accessFor(player)`
returns the player's entry, else the public access, and the owner always has ADMIN.

**Payments** go through Numismatics by reflection (`Payments.java`), so the mod still loads without
it — a priced listing simply refuses the order and says why.

Money is **held, not paid**. Ordering withdraws the buyer's funds into `Escrow` (a `SavedData` in the
overworld, so a restart mid-delivery doesn't swallow anyone's money) and they sit there until:

- the destination terminal's stock rises by what was ordered — delivered, so the seller is paid;
- Create couldn't fill the order at all — refunded to the buyer immediately;
- ten minutes pass with nothing observed — the seller is paid anyway. Payment is only ever taken
  once Create has accepted the request, which means the stock really was packaged, so after the
  grace period it was most likely delivered and consumed before anyone looked.

`/ctlo escrow` lists what is currently held.

**Dispatching.** `Dispatcher.plan()` reads every station postbox, groups the packages by address,
finds the station serving that address (exact match, then wildcards like `PD-C01-B*`), picks an idle
train on the same track graph, and builds the stop list — inserting the `<address> REV` reverse point
where one exists. `assign()` turns a plan into a Create `Schedule` (destination / fetch packages /
deliver packages) and hands it to the train. Planning never touches a live railway; only `assign`
does. Packages bound for another railway are routed one leg at a time to that railway's interchange.

`DispatchService` runs the same thing on a tick loop for terminals with auto dispatch on, gated by a
global switch (`/ctlo auto on|off`, default off). `/ctlo packages` and `/ctlo dispatch [run]` give the
same view in chat.

**Crafting.** Electron tube over stock link + precision mechanism + display link, over a brass
casing. Create is now a declared required dependency; Voxy and Numismatics are declared optional.

**On the map**, the terminals panel lists the runs with their stops and, for blocked ones, the reason
(`no idle train on that track network`, `no station serves PD-C02-B07`). Terminals something is
waiting for get a highlighted label. "Send all ready" needs operator rights and is the only control
that moves a train.

## Mod skeleton (started 2026-09-11)

- **Identity:** mod id `createtradelogisticsoverhaul`, package `com.vrlulu.createtradelogisticsoverhaul`, version 0.1.0.
- **Toolchain:** NeoForge 21.1.249 on Minecraft 1.21.1, ModDevGradle 2.0.147, Gradle 8.12, Java 21 (downloaded by Gradle; Gradle itself runs on the Java 17 already installed).
- **Build:** `cd mod/workspace && ./gradlew build` → `mod/jars/`. `./gradlew runClient` starts a dev client.
- **What exists so far (2026-09-11):**
  - **Terrain from Voxy, in-process.** `VoxyCommon.getInstance().getNullable(worldIdentifier)` gives the live `WorldEngine`; sections come from `acquireIfExists`. No copying, no file locks, always current.
  - **Sections are found by flood fill** outwards from the player's section, so nothing scans the whole database.
  - **Textures and tints from the game itself:** baked models give each face's sprite and tint flag, the resource manager gives the pixels. Resource packs, modded blocks and custom model loaders work with no special cases. Biome tints use the biome Voxy stored per voxel.
  - **Live updates:** a mixin on Voxy's `WorldEngine.markDirty` records changed sections; `/api/events` streams their keys and the page re-fetches only those. Voxy's own dirty callback belongs to its renderer and is a single slot, so it must not be taken over.
  - **Still missing:** non-cube block models (plants, slabs, stairs are classified but drawn as cubes or not at all), and everything Create-related.
- **Earlier skeleton:**
  - `CreateTradeLogisticsOverhaul` — common entry point.
  - `client/ClientInit` — client-only; starts the web server at client setup.
  - `web/MapWebServer` — a loopback-only HTTP server (JDK built-in, no dependencies) serving the page from the jar, plus `/api/status`.
  - The page is copied into the jar from `prototype/web` at build time, so prototype and mod never diverge.
- **Voxy:** referenced at compile time only (`voxy_jar` in `gradle.properties`); at runtime the mod reads terrain in-process from the Voxy mod loaded in the same game.

## Textures and block models (implemented 2026-09-10)

**How it works**
- **Server:** `block_models.py` bakes every block state Voxy knows.
  - It picks the blockstate variant by property match, or combines every multipart part whose `when`/`OR`/`AND` applies.
  - It walks the model parent chain, then applies element rotation (with rescale), blockstate `x`/`y` rotation, `uvlock` (by re-projecting UVs), face UV rotation, `cullface` and `tintindex`.
  - Every texture it references goes into one 16 px texture array. Animated textures use their first frame; larger ones are downscaled. Leaves are filled opaque, like fast graphics.
- **Block kinds:**
  - **Solid:** full cubes, drawn with merged faces.
  - **Glass:** cutout full cubes. Neighbours see through them, and faces between identical glass are skipped.
  - **Water:** translucent. Waterlogged blocks (seagrass, kelp, waterlogged slabs) also produce water.
  - **Model:** everything else. At full detail its real quads are drawn. At coarser levels it becomes a cube if it's at least 20% filled (slabs, stairs, walls), otherwise nothing (plants, torches, rails, fences). Voxy does the same.
- **Cube faces:** each direction takes the texture of the block's largest face in that direction, plus a quarter-turn texture rotation. That way sideways logs get end rings on the correct faces and grain along the log, and furnaces and similar blocks face the right way.
- **Page:** a custom three.js shader samples the array using `fract(uv)`, with proper mip gradients so textures tile once per block at every LOD. It discards transparent pixels, multiplies by tint and shade, and applies fog. **Textures** toggles back to flat colours.
- **Blocks drawn by code in the game:** chests, barrels and shulkers become a stand-in box using their particle texture; beds become a 9 px slab. Signs, banners, skulls and heads are skipped. Other modded blocks with custom renderers become a full cube of their particle texture.

**Results:** the big world's 5,886 states bake in about 11 s with no errors, giving 61,700 quads and 1,127 textures. The texture array is about 1.2 MB, and gzipped assets total about 0.3 MB for the test world. Towns with stairs, fences, trapdoors, lanterns and walls render at 60 FPS.

**Known gaps**
- The cave heuristic can still misfire in odd places. It now ignores 16-block slices that are all air with no skylight, which fixed dark filler blocks under high builds.
- Water side faces are drawn only where there's ground under the neighbouring air, so waterfalls show only their top surface.
- Waterlogged blocks use the tint of a nearby water block in the same section.
- Voxy has no block-entity data, so there's no chest contents, sign text or copycat material, and Create's moving parts show their static model only.

### Feasibility survey (before implementing)

Measured with `model_survey.py` against every block state Voxy has seen:

| | Test world | New World (2) |
|---|---|---|
| Block states | 1,968 | 5,886 |
| Full cubes (textured cube is exact) | 324 states, **94.6% of voxels** | 679 states |
| JSON shapes (slabs, stairs, plants, torches, rails…) | 916 states, 0.7% of voxels | 2,803 |
| JSON multipart (fences, walls, panes, vines, redstone…) | 636 states, 0.5% of voxels | 2,014 |
| No JSON geometry | 91 states, 4.25% of voxels (mostly water and lava) | 389 states from **69 distinct blocks** |
| Distinct textures | 583 (576 are 16 px, 25 animated) | 1,085 (1,022 are 16 px, 35 animated) |

**Findings**
- **Textures are easy.** About 1,100 textures at 16×16 is roughly 1 MB as a WebGL texture array. Texture coordinates can come from world position, so merged faces tile per block, at every LOD. That matches how Voxy renders its LODs.
- **Block models are feasible at LOD 0.** About 98% of non-cube states have ordinary JSON models: cuboid elements, per-face UVs, variant rotations and multipart conditions, all resolvable from the block properties Voxy stores. Coarser levels stay textured cubes, like Voxy.
- **What can't come from JSON** (69 blocks) is drawn by code in the game:
  - chests, beds, signs, banners and skulls,
  - Lootr containers,
  - Copycats, whose look depends on block-entity data,
  - a few Create valve handles.
- **Hard limit:** Voxy doesn't store block-entity data (contents, sign text, copycat materials) or animation state. Create's spinning parts show only their static JSON model.

## Where data lives

- **Prototype**
  - `.cache/<World_Name>/snapshot/` in the project folder mirrors the Voxy database (about 140 MB for the test world, 820 MB for New World (2)). It persists between runs so re-syncs are incremental, and it's safe to delete at any time.
  - Everything else is in memory: the server's converted-section cache is capped at 300 MB, and the browser holds what it has streamed in.
  - Nothing is written to the save.
- **Client mod (planned)**
  - Reads Voxy's files where they are: `saves/<world>/voxy/...` for singleplayer, `.voxy/saves/<server>/...` for multiplayer.
  - Tailing the log needs no copy. The initial base read uses either a mirror in `<game dir>/logisticsmap/cache/<world>/` or RocksDB secondary mode (to test).
  - Derived per-modpack caches, such as the texture array and colours, go in `<game dir>/logisticsmap/cache/`.
- **Server mod (planned)**
  - Access Panel owners and permissions live in the world save: block-entity NBT plus a `SavedData` file under `saves/<world>/data/`.
  - That data travels and gets backed up with the world. The web page itself stores nothing permanent.

## Environment

- Minecraft 1.21.1, NeoForge 21.1.249 (Modrinth profile "Create Aeronautics")
- Create 6.0.10, Create Aeronautics 1.3.2, Voxy 0.2.9-alpha, Sodium 0.8.13
- Test save: `saves/Create Logistics World`, freshly generated at 0,0, radius of about 16 chunks
- Vanilla assets (textures, biomes): `ModrinthApp/meta/versions/1.21.1-21.1.249/1.21.1-21.1.249.jar`

## Roadmap

1. **Prototype renderer (done):** Python stand-in plus web page, with streaming 3D terrain, live updates, real textures and block models.
2. **Client mod:** local web server and Voxy file reading in Java, replacing the stand-in. Uses the game's own models and textures. **← next**
2b. **Client mod (done):** local web server, Voxy read in-process, live updates through a mixin on
   Voxy's `markDirty`, the game's own textures and baked models.
3. **Registry (working in game):** the Logistics Terminal block, permissions, stock and addresses on
   the map. Binding, the network glow and tooltips come from Create's own linked-block machinery.
4. **Dispatcher v1 (working in game):** verified end to end on 2026-09-11 - ordered 4 Honeyed Apple
   from Oranges to Apples, the package queued at the postbox, the map planned the run, the train was
   dispatched, and the goods arrived in Apples' network. Reverse points are inserted where they
   exist; this test layout has none.
5. **Routing across graphs (written, untested in game):** interchange legs.
6. **Supply rules and trade (written, untested in game):** standing orders, prices, Numismatics
   payments. Escrow until delivery still to do.
7. **Next:** verify all of the above in game, then draw tracks and trains on the map.
8. **Later:** airship (Sable/Aeronautics) docking as an interchange.

## Decisions log

- **2026-09-10:** Feasibility confirmed. Voxy data can be decoded offline; see the format section.
- **2026-09-10:** Dropped GitHub and GitHub Pages hosting. The client mod serves the page locally, because the game is needed for real-time data and actions anyway.
- **2026-09-10:** No LAN or phone mode. Use a browser tab on the PC running Minecraft.
- **2026-09-10:** The mod ships on both client and server in the modpack, so mixed clients aren't supported.
- **2026-09-10:** Voxy is read via its raw files, not its classes.
- **2026-09-10:** Permissions and visibility come from Access Panel blocks with attached stock links, not global access.
- **2026-09-10:** Dispatching uses the package and postbox network via stock links.
- **2026-09-11:** The mod's core value is **on-demand tasking** (a dispatcher), not just visibility. Round-robin vans and hand-typed stop lists are the pain being fixed.
- **2026-09-11:** A panel is tuned to one network by clicking a stock link (Create's own convention), not by attaching a stock link to it.
- **2026-09-11:** Routing is discovered from Create's own data (stations, their postbox addresses, track graphs). Only interchanges, where the hand-off runs through the player's own belts, need manual declaration.
- **2026-09-11:** Unloaded networks show their last known stock with an offline badge, and orders queue until the network loads.
- **2026-09-11:** Payments hold funds at order time and release on delivery; barter dispatches both sides at once.
- **2026-09-10:** Meshing happens in the browser (Web Workers), not the server. That keeps toggles like caves cheap, and the client mod only has to serve voxel data.
- **2026-09-10:** Terrain streams on demand through the octree, so worlds of any size work. Live updates come from tailing Voxy's write-ahead log, not from repeated snapshots.
- **2026-09-10:** Real textures are chosen over lighting effects. Block models are feasible at LOD 0 only; coarser levels stay textured cubes.
- **2026-09-11:** The block is called **Logistics Terminal**, not a panel.
- **2026-09-11:** Terminal settings travel as JSON in one packet rather than a packet per field. The
  page needs no schema, and unknown fields are ignored, so old clients stay compatible.
- **2026-09-11:** Dispatching from the map requires operator rights, and a page load never dispatches
  — `GET /api/dispatch` plans, `POST` runs.
- **2026-09-11:** Numismatics is optional and reached by reflection, so the mod loads without it.
- **2026-09-11:** Escrow releases to the seller after a grace period rather than refunding the
  buyer. Payment is only taken once Create has accepted the request, so the goods did leave; a
  refund by default would let a buyer take delivery and keep the money.
- **2026-09-10:** Textures and JSON block models are implemented. Blocks drawn by code in the game (chests, Create kinetic parts, …) aren't worth replicating; they get simple stand-ins or are skipped.

## Open questions

- **Airship bridging:** how a docked Sable/Aeronautics ship joins the routing graph (docking connector, proximity, or a dedicated block).
- **Permissions:** the shape of trade permissions (per-item shop window vs simple view/order/admin).
- **Couriers other than trains:** trucks are trains here, but airships and boats may need their own handling.
- Underground visibility on the map (cutaway by Y level? hide caves?).
- How the client mod gets fresh Voxy data: a periodic snapshot copy, or RocksDB secondary mode.
