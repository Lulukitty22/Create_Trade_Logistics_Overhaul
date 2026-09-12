# Create Trade Logistics Overhaul

A mod that enables more advanced networking, with a package + track network functionality boost,
with a new visual layer.

A Minecraft mod (NeoForge 1.21.1) that adds **on-demand dispatching** to Create's package logistics,
with a **3D logistics map** you open in your browser.

Create already handles demand, packaging and addressing — including requests across stock link
networks. What it lacks is a way to *task vehicles*, so delivery vans and long-haul trains end up on
fixed round-robin loops that stop everywhere whether or not there's anything to carry. This mod
plans routes from the packages that actually exist and hands each vehicle a schedule built for the
job, reverse-point stations included.

The map is rendered from the player's own [Voxy](https://modrinth.com/mod/voxy) LOD data, so it
shows the world you've actually explored, with real block textures and shapes.

## Status

Early. The renderer works; the mod is a skeleton.

- ✅ Terrain pipeline: streaming 3D map, live updates, real textures and block models (Python prototype)
- ✅ Mod skeleton: builds, serves the map page from a local web server
- ⏳ Reading Voxy from inside the game
- ⏳ Logistics Panel block, permissions, stock on the map
- ⏳ The dispatcher
- ⏳ Trade, prices and payments

## Layout

- `prototype/` — Python stand-in for the client mod, plus the web page (`web/`) the mod ships
- `mod/workspace/` — the mod itself (Gradle, NeoForge 1.21.1, Java 21)
- `DESIGN.md` — architecture, reverse-engineering notes and the decisions log

## Building

```
cd mod/workspace
./gradlew build       # jar -> ../jars
./gradlew runClient   # dev client
```

Requires Java 17+ to run Gradle; the Java 21 toolchain is downloaded automatically.

## Requires

Minecraft 1.21.1, NeoForge 21.1.x, [Create](https://modrinth.com/mod/create) 6.0.x, and Voxy for the map.
