# CadistChunkProcessing Pro (v9)

Server-side chunk processing for **Paper 1.21.11** that saves upload bandwidth
**and** makes x-ray impossible — the successor to CadistChunkProcessing v8.

## The core idea: solidify, never void

Every v0–v8 bug (mining-reveal void, chunk-border holes, freecam stone pillars,
false-culled cave entrances) came from one choice: hiding blocks by turning them
into **air**. The client caches that air, and the next block-update or chunk
border turns it into visible **void**.

Pro never does that. In hidden regions it only ever turns transparent space and
ore into a homogeneous **ghost solid** (stone / deepslate / netherrack /
end-stone). Bandwidth still drops just as much, because a chunk section's wire
size is set by its **palette size** (distinct block-states), not by how many
blocks are solid — collapsing a section to one block-state is a few bytes
whether that state is air or stone. The result:

- **No void, ever.** Nothing can expose a hole, because we never create one.
- **Mining is always correct** — you can only interact inside the always-real
  bubble around you.
- **X-ray is impossible** — buried ore and hidden caves are uniform stone.
- **Surface is untouched** — only blocks *below* a column's surface are changed,
  so hills, cliffs, structures and cave entrances are never false-culled.

## How it works

Per player, each chunk falls into a distance tier:

| Tier | When | What happens |
|------|------|--------------|
| **REAL** | within `real-radius` chunks | geometry untouched; only buried-ore camouflage |
| **SHELL** | out to `cave-render-distance` | caves reveal inward `entrance-shell-depth` blocks from real openings, solidified beyond (seam-continuous across chunks) |
| **DEEP** | beyond that | all sub-surface caves solidified; fully-buried sections homogenised to one block (the big saving) |

Buried-ore camouflage runs in every tier. As you move, chunks entering the real
bubble are re-sent real (caves "open" before you arrive) and chunks leaving are
re-hidden — no rejoin needed.

PacketEvents (installed as its own plugin) is used to intercept `CHUNK_DATA`.
The engine never touches the Bukkit API on the packet thread (LeafMC / Moonrise
safe). On any error the packet is passed through untouched.

## Modes (live-switchable)

| Mode | Real bubble | Reveal | Savings |
|------|-------------|--------|---------|
| **Max Savings** | small (2) | least | highest (your home-server upload priority) |
| **Balanced** | medium (4) | smooth | high, opens caves before you arrive |
| **Generous** | large (6) | most | lower, smoothest visuals |

## Commands (`cadistchunkprocessing.admin`)

- `/cadistchunk gui` — Catppuccin control panel (toggles, sliders, live stats, presets)
- `/cadistchunk stats` — measured bandwidth %, chunks/sec, ores hidden
- `/cadistchunk bar` — toggle a live bandwidth BossBar
- `/cadistchunk mode <BALANCED|MAX_SAVINGS|GENEROUS>`
- `/cadistchunk cave` / `ore` — toggle hiding
- `/cadistchunk reload`

Permission `cadistchunkprocessing.bypass` makes a player receive raw chunks.

## Build

Requires JDK 21. PacketEvents must be installed on the target server.

```bash
mvn clean package
# -> target/CadistChunkProcessing-Pro-9.0.0.jar
```

`paper-api 1.21.11` and `packetevents-spigot 2.12.1` are `provided` (not shaded).

## Verify (on a 1.21.11 Paper test server with PacketEvents)

1. Surface walk — terrain/hills/structures look vanilla (no false culls).
2. Hillside cave entrance — opening is visible, interior reveals as you approach.
3. Dig straight down 120 blocks quickly — no void, no desync.
4. Freecam from the surface — solid ground, no pillars, no void.
5. X-ray / spectator client — non-exposed ores are stone; ores in a cave you've
   entered are real.
6. `/cadistchunk stats` — measured savings should reach ~90%+ on deep terrain in
   Balanced / Max Savings.
7. Fly across chunk borders over a cave system — no seam walls.
8. Nether & End — correct ghost block, no void.
9. GUI — toggles, sliders, presets, and stats all apply live without a reload.

The pure culling engine is covered by unit tests (`mvn test`) that assert the
master invariant — **the engine never creates air where a solid was** — plus
shell reveal, deep homogenisation, ore camouflage, surface protection, and
cross-chunk seam seeding.

## What changed from v8

Removed (and why): occlusion-to-air culling (the void source), the 2×2
chunk-border hack (no void to hide), the mining/cave reveal listeners (the real
bubble subsumes them), per-player reprocessing, and the block-count "savings"
metric (now measured wire bytes). The PacketEvents lifecycle is no longer
double-owned — Pro uses the installed plugin's shared API.
