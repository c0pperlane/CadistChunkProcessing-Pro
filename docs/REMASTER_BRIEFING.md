# Culling Remaster — Briefing for an independent planning pass

Hand this whole file to the planner. Goal: a **Partial-Remastering Improvement
Plan** for culling that pushes toward *maximally aggressive* hiding while staying
*precise* (no false-cull, no void) and *efficient* (TPS/bandwidth). Read the
"Hard constraints" and "The core tension" sections first — they bound every
decision.

---

## 1. What the plugin is

Server-side chunk processor for **Paper 1.21.11**, using **PacketEvents 2.12.1**
to intercept outgoing `CHUNK_DATA` and rewrite the block array per player before
it leaves the server. Two goals: cut upload bandwidth, and make x-ray/freecam
useless. Java 21, Maven. Successor to a v8 that had chronic "void" bugs.

### The one invariant: **solidify, never void**
Hidden regions only ever turn *transparent space or ore* into a homogeneous
**ghost solid** (stone / deepslate / netherrack / end-stone). The engine must
**never write air where a solid was**. Reason: the client caches chunk data; if
you hide by writing air, the next block-update / chunk-border / freecam reveals
**void**. Solid-over-transparent is always safe; bandwidth still drops because a
section's wire size is set by its **palette size** (distinct block-states), not by
how many blocks are solid — collapsing a section to one state is a few bytes.

Any remaster MUST preserve this invariant. "More aggressive" = solidify *more*
transparent space; it must never mean "write air."

---

## 2. Architecture (where to work)

Two halves, deliberately separated:

- **Pure engine** (`src/main/java/.../engine/`) — no Bukkit, no PacketEvents, no
  shared mutable static. Deterministic, fully unit-tested. This is where block
  transforms live.
  - `ChunkProcessor.java` — THE engine. `process(...)` runs a pipeline over a
    flattened column of global block-state ids (`idx = (y<<8)|(z<<4)|x`). Passes:
    heightmap → cave-air classify → (SHELL) reveal BFS / (REAL) reachability or
    sealed solidify → ore camouflage → deep rock-collapse → anti-base scrub →
    surface-entrance camo → vertical cull. Many overloads chain into one full one.
  - `BlockClassifier.java` — interface: `isTransparent / isOre / isTerrain /
    isArtificial / isFluid`. Engine asks only these.
  - `OreView.java` — ore-visibility policy (hide-all / keep-exposed /
    surface-only / surface+near / surface+reachable).
  - `Tier.java` (REAL/SHELL/DEEP), `ModeParams.java`, `BorderSeed.java`
    (cross-chunk opening info for seam-continuous reveals).
- **Bukkit/packet side** (`src/main/java/.../`) — talks to the server.
  - `ChunkPacketInterceptor.java` — packet-thread entry. Picks the tier by
    distance, flattens the column (`ColumnCodec`), pulls the reachability mask,
    calls the engine, re-encodes. **Must never touch the Bukkit API on the packet
    thread** (LeafMC/Moonrise safe); reads only from concurrent caches. On any
    exception it passes the packet through untouched (fail-safe = no void).
  - `ReachabilityService.java` — **main-thread** bounded flood-fill of the air a
    player can actually reach (their connected cave). Publishes immutable
    per-chunk boolean masks the packet thread reads race-free. Throttled
    (recompute only after moving `MOVE_THRESHOLD`), bounded (`BAND=64` vertical,
    real-radius horizontal, `MAX_VISITS=400k` abort→fallback), 10-tick cadence.
    Has the `reveal-distance` 3D leash.
  - `Config.java` (typed, atomically reloaded), `Gui.java` (control panel),
    `PlayerTracker`, `RefreshScheduler` (re-send queue), `BorderCache`,
    `ProcessedChunkCache` (DEEP results cached, player-independent), `WorldMeta`,
    `BandwidthMonitor`, `ColumnCodec`, `BiomeCollapser`.

### Distance tiers (per player, per chunk)
| Tier | When | Behavior |
|---|---|---|
| REAL | ≤ `real-radius` | geometry sent real (digging safety); ore camo; optional reachability/sealed/vertical-cull |
| SHELL | ≤ `cave-render-distance` | caves reveal inward `entrance-shell-depth` from real openings (BorderSeed-seam-continuous), solidified beyond |
| DEEP | beyond | all sub-surface solidified; fully-buried sections homogenized to one block (the big saving); **cached, player-independent** |

---

## 3. Current hiding features (all off by default unless noted)

- **cave-hiding** (on) — solidify sub-surface caves in SHELL/DEEP.
- **ore-hiding** (on) — anti-xray ore camouflage in all tiers.
- **hide-block-entities** (on) — strip buried chests/spawners from hidden chunks.
- **anti-base-finder** — in SHELL/DEEP, a connected cave-air pocket touching any
  man-made block (the whole tunnel/room) never reveals; buried man-made blocks
  scrubbed to rock.
- **reachability-ores** — show ore only where it touches air the player can reach.
- **reachability-caves** — REAL tier: solidify every cave-air cell the player
  can't reach (only the cave you're in stays real). Aggressive.
- **hide-sealed-caves** — REAL tier: solidify only caves with *no air-path to the
  open sky* (entrance-less pockets / sealed rooms); keeps open caves and the room
  you're in. Gentle sibling of reachability-caves.
- **reveal-distance** (int, 0=off) — leash on the reachability scanner: only
  connected air within N blocks (3D) of the player stays revealed; bounds freecam
  to a bubble around you. Also bounds how far down a shaft you can see.
- **surface-entrances** — cap narrow artificial/fluid surface shafts (ladder /
  water-lift) and blend into the ground.
- **vertical-culling** (on) — REAL tier: per-column, solidify each column up to
  `min(playerY-margin, columnSurface)`; clamped to surface (no floating slab when
  elevated); **skips reachable air** so the cave/ravine you're in stays open to
  its floor.

---

## 4. The core tension (the wall the plan must navigate)

You cannot have **100% aggressive AND 100% precise AND 100% efficient** at once
with chunk-granularity, view-independent packets. Pick the tradeoff:

- **Precise + efficient (today):** reachability + leash + sealed. Never void,
  bounded cost. *Limitation:* a cave you could walk into stays visible; a player
  standing on the open surface keeps everything air-connected to the surface.
- **More aggressive needs one of:**
  - **(a) Per-eye occlusion culling** — hide what the real eye can't see, so
    freecam can't reveal it. *Killer:* view-dependent → would need re-sends on
    every head-turn (impossible at chunk granularity), and digging toward a
    hidden block = void risk. **Rejected** unless someone finds a non-view-
    dependent approximation.
  - **(b) Fog-of-war / explored-area reveal** — only reveal what the player has
    physically visited/seen; hide everything else even if reachable. *Costs:*
    persistent per-player "explored" set (memory + save/load), pop-in at
    boundaries, and a boundary where open cave reads as solid until you cross
    (safe re: no-void — solid is fine — but visually a wall you walk through that
    then reveals). Needs hysteresis + async reveal to feel good.

A good remaster plan = **choose the curve and engineer the chosen tradeoff
cleanly**, not "make all three 100%."

---

## 5. Hard constraints any plan MUST honor

1. **Never write air over solid** (the invariant). Aggression = solidify more,
   never void.
2. **Digging safety:** the area the player can interact with must be real, and
   newly-exposed space (mining into a hidden pocket) must reveal within ~1s via
   re-send — never show void in the meantime.
3. **Packet-thread purity:** no Bukkit API off the main thread; consume only
   immutable/concurrent snapshots. Fail-safe: on any error pass the packet
   through unmodified.
4. **DEEP stays cacheable / player-independent** (it's the bulk of the savings).
   Player-dependent transforms belong in REAL (and maybe SHELL).
5. **TPS budget:** main-thread scans must stay bounded + throttled (snapshots per
   moving player). Anything heavier needs to be async/off-thread with race-free
   publish, like `ReachabilityService` already is.
6. **Cross-chunk seams:** reveals must be continuous across chunk borders
   (`BorderSeed`/`BorderCache` pattern) — no seam walls.
7. **Surface protection:** above-terrain-surface geometry (hills, trees,
   villages, cliffs, cave mouths) is never false-culled. The one sanctioned
   exception is `surface-entrances`, kept narrow + artificial-gated.

---

## 6. Suggested scope for the plan to produce

- A decision on the target tradeoff (recommend: fog-of-war done right, since
  per-eye occlusion is infeasible) with explicit UX expectations (what pops, when).
- An architecture for an **explored/visible set** per player: data structure
  (bitset per chunk? sparse?), update source (movement + raycast sampling from the
  eye, async), persistence (save/load, decay?), memory bound, and how it feeds the
  engine as a mask (mirror `ReachabilityService`'s immutable per-chunk masks).
- **Anti-pop-in**: hysteresis, predictive reveal ahead of movement, async
  raycast-verified reveal so chunks are ready before the eye arrives.
- How it composes with existing layers (reachability, sealed, leash, vertical
  cull) — ideally the explored-set becomes another input to the same REAL-tier
  solidify pass, not a parallel system.
- Phasing (ship incrementally behind a toggle, off by default), test strategy
  (engine stays pure/unit-tested; new mask logic unit-tested standalone), and
  perf validation plan.
- Risks + rollback (every feature is a toggle; default off).

---

## 7. Build / CI facts

- `mvn clean package` → `target/CadistChunkProcessing-Pro-<ver>.jar`. `paper-api`
  + `packetevents-spigot` are `provided`; PacketEvents resolves from the **CodeMC**
  repo (in `pom.xml`).
- CI (`.github/workflows/ci.yml`) runs `mvn test` on every push/PR. Release
  (`.github/workflows/release.yml`) builds + tests + publishes a GitHub release
  with the jar on tag push or manual dispatch (takes a `version` + `changelog`).
- The pure engine can be compiled/tested standalone (no network) for quick
  verification; the Bukkit side only compiles on a real build (paper-api needed).

---

## 8. Key files cheat-sheet

```
engine/ChunkProcessor.java      pipeline + all block transforms (start here)
engine/BlockClassifier.java     block predicates
engine/OreView.java             ore visibility policy
ReachabilityService.java        main-thread reachable-air masks (+ reveal-distance)
ChunkPacketInterceptor.java     packet-thread orchestration, tier selection
Config.java / Gui.java          settings + control panel
src/test/.../ChunkProcessorTest.java   invariant + feature unit tests
```
