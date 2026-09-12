# Create Trade Logistics Overhaul (mod)

- `workspace/` — the Gradle project (NeoForge 1.21.1, Java 21).
- `jars/` — built mod jars land here.
- `tools/` — a local Gradle distribution, downloaded once; not part of the project.

## Build

```
cd workspace
./gradlew build          # jar -> ../jars
./gradlew runClient      # dev client with the mod loaded
```

Gradle itself needs Java 17+; the mod compiles against Java 21, which Gradle downloads on its own.
Voxy is only a compile-time reference (see `voxy_jar` in `gradle.properties`); at runtime the mod
reads terrain from the Voxy mod loaded in the same game.

See ../DESIGN.md for the architecture.
