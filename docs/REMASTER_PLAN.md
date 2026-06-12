# Partial-Remastering Improvement Plan — Fog-of-War Culling

*Planning pass over `docs/REMASTER_BRIEFING.md` and the v9.3.0 codebase.*

---

## 0. Executive summary

The briefing asks for culling that is **maximally aggressive**, **precise** (no
false-cull, no void, no visible jank in legitimate play) and **efficient**. The
briefing is also right that all three cannot be 100% *simultaneously* under
view-independent chunk packets — so this plan's first job is to pick the curve.

**Decision: build fog-of-war (an "explored set") — and recognize that it is not
a compromise but the theoretical optimum.** Here is the reframing that makes
"100% aggressive AND 100% precise" coherent instead of contradictory:

- **Aggression is measured against the cheater.** The strongest possible
  guarantee a server-side packet filter can give is: *a freecam/x-ray client
  learns nothing the player has not already legitimately learned.* You cannot
  hide more than "everything the player hasn't seen" without corrupting
  legitimate play (the player would watch caves they're looking at turn to
  stone). Fog-of-war sits exactly on that floor: cheat-client marginal
  information gain ≈ 0. There is no "more aggressive" point on the curve that
  is still correct.
- **Precision is measured against the legitimate player.** Everything currently
  in the player's line of sight is real, reveals land *before* the eye arrives
  (look-driven marking, see §4), reveals are monotone within a session (no
  flicker by construction), and the no-void invariant is untouched (fog only
  ever solidifies *more* transparent space).
- **Efficiency is a budget, not a hope.** Every new main-thread cost is
  bounded by an explicit ray/visit budget with a global governor; the packet
  thread gains one bitset lookup per cell; DEEP stays player-independent and
  cached, untouched.

Per-eye occlusion culling stays **rejected** (view-dependent → re-send per
head-turn is impossible at chunk granularity, and digging toward an
occlusion-hidden block risks void). That decision is recorded in §2 so it does
not get relitigated.

The remaster is *partial* by design: no engine rewrite. Phase 0 refactors the
REAL-tier hiding passes into one composable **keep-mask** pipeline (also
retiring the 17-argument `process(...)` overload pyramid), and fog-of-war then
plugs in as one more mask source — the capstone, not a parallel system.

---

## 1. Current state (what each layer hides, what still leaks)

| Layer (v9.3.0) | Hides | Remaining leak with it on |
|---|---|---|
| SHELL/DEEP cave solidify + rock-collapse | everything sub-surface at distance | nothing significant at distance (entrance shells show vanilla-visible mouths only) |
| ore camo + `hide-block-entities` + `hide-all-ores` | x-ray ore/chest scan | — |
| `anti-base-finder` | man-made pockets + materials in SHELL/DEEP | bases inside the REAL bubble |
| `reachability-caves` | every REAL-tier cave you can't *reach* | the **whole connected system** you can reach — stand at a cave mouth (or anywhere on the surface) and everything air-connected to you is on the wire for freecam |
| `hide-sealed-caves` | entrance-less pockets in REAL | any cave with a surface opening |
| `reveal-distance` | caps the reachable reveal to an N-block bubble | everything inside the bubble, including caves you never looked at; and it *forgets nothing/remembers nothing* — it's purely geometric |
| `vertical-culling` | the deep column below you | — (composes with the above) |
| `surface-entrances` | narrow artificial surface shafts | — |

The leak that motivates this remaster is the bold one: **reachability is a
geometric predicate ("could you walk there?"), not an epistemic one ("have you
seen it?")**. A freecam user standing on the open surface has the entire
surface-connected cave network within `reveal-distance` on the wire. Fog-of-war
replaces "could reach" with "has seen", which is the strongest correct
predicate that exists.

---

## 2. Decision record

| # | Decision | Rationale |
|---|---|---|
| D1 | **Fog-of-war (explored set) is the target**, REAL-tier only | Optimum aggression point (§0); SHELL/DEEP are already maximally hidden and must stay player-independent (DEEP cache, briefing constraint 4) |
| D2 | **Per-eye occlusion culling rejected, permanently** | View-dependent → re-sends per head-turn; digging-toward-hidden = void risk; chunk granularity can't follow a frustum |
| D3 | "Explored" = **seen ∪ been-near**, *monotone per session*, persisted | Seen (eye raycasts) is the anti-freecam semantic; been-near (small body flood) is the digging-safety/UX floor; monotonicity gives zero flicker for free |
| D4 | Explored is **per player, per world**, never shared | Sharing would leak teammate exploration to anyone; also keeps the data model trivial |
| D5 | Fog **subsumes** `reachability-caves` when both are on | Fog's keep-set is a strict subset; running both means fog wins; GUI/docs say so explicitly |
| D6 | REAL-tier cave **mouths get an implicit entrance shell** under fog | A mouth is vanilla-visible information (SHELL already shows it at distance); without this, walking past an unexplored mouth inside the REAL bubble would show a visibly false-culled "shallow dent" — the one cheap concession that buys "no visible false-cull" |
| D7 | Stale explored bits are **never invalidated for correctness** | The engine only solidifies transparent cells: a stale bit over a now-solid cell is ignored; new air in an explored region is unexplored → hidden → safe direction |
| D8 | Persistence is **discardable at any time** | Corrupt/missing file → start empty → *more* hiding, never less; rollback is `rm -r` |
| D9 | Side channels (light data, entity packets, sounds) are **documented non-goals** of this remaster, with entity culling named the Phase-5 candidate | Each is a separate subsystem; bundling them would sink the remaster |

### UX contract (what pops, when — agreed expectations, not bugs)

1. Looking at anything reveals it before you reach it (rays mark at sight
   speed, re-send within ~`refresh` latency, typically &lt; 0.5 s).
2. You can never be inside or directly facing solid fog: an 8-block body
   bubble is always explored, and cave mouths carry the implicit shell (D6).
3. Walking *blind* (backwards / eyes closed around a corner) into unexplored
   space shows walls opening ~8 blocks ahead. Accepted.
4. After a teleport/join into cold territory, the first ~1 s behaves like
   today's reachability warm-up (strict fallback, §3.6). Accepted.
5. Within a session nothing ever un-reveals (monotone). Across sessions
   everything you explored is still there (persistence), unless the server
   sets an expiry/cap.

---

## 3. Architecture

### 3.1 `ExploredSetService` (new, Bukkit side — sibling of `ReachabilityService`)

Owns the per-player explored set. Mirrors the proven `ReachabilityService`
pattern exactly: main-thread sampling, immutable publish, packet-thread reads
race-free, every scan bounded, every failure drops to "feature off for this
player" (which is pass-through = safe).

**Two mark sources, both main-thread, both bounded:**

1. **Body source** — the existing reachability flood *with a hard 8-block
   leash* (the primitive already exists: it is exactly `relax()` +
   `revealDistSq` with r=8). Every cell the flood visits is marked explored.
   This is the digging-safety and "never buried in fog" floor. Cost: ≤ a few
   thousand visits — noise compared to the existing scanner.
2. **Sight source** — N rays DDA-marched (Amanatides–Woo voxel traversal) from
   the eye through *server-truth* geometry (the same `ChunkSnapshot` bubble the
   reachability scan already takes). Every transparent cell traversed is
   marked; the march stops at the first occluding block or at
   `fog-ray-distance` (default 64) or at an unloaded chunk (reads as solid —
   same convention as `passableAt`). Direction sampling per scan:
   - ~75% of rays jitter-stratified over a ~100°×70° frustum around the
     player's look vector (yaw/pitch — see PlayerTracker change, §3.5);
   - ~25% uniform over the sphere, so the F5 third-person back-camera (a
     legitimate vanilla mini-freecam) never stares at solid fog behind you.
   - Jitter pattern rotates each tick (Halton/blue-noise), so coverage
     accumulates: a cavern you look into for half a second is fully marked.
   - **Dilation:** each marked cell also marks its 6-neighbours *if
     transparent*. Covers peripheral slivers and edge cells between rays.

**Budgets (the efficiency contract):** default 96 rays/player/scan, scan every
2 ticks, ray length ≤ 64 → worst-case ~6k cell-steps per player per scan, each
step one memoised opacity lookup. A **global governor** caps total rays/tick
across all players (default 4 096), round-robin with priority to players who
recently moved or turned; stationary players with an unchanged view cost ~0.
Opacity predicate: occluding-material check memoised by `Material` (mirrors
`RegistryBlockClassifier`'s memoisation; sight passes through glass/water/
leaves, stops at occluders).

**Data structure (memory contract):** per player+world,
`ConcurrentHashMap<Long /*chunkKey*/, long[][] /*per-section 4096-bit sets*/>`.

- Section-sparse: only sections containing explored cells hold a 512-byte
  bitset; a fully-explored section collapses to a shared `FULL` sentinel.
- Bit index inside a section: `(y&15)<<8 | z<<4 | x` — same convention family
  as everything else in the engine.
- Copy-on-write at **section** granularity: marking clones only the dirty
  512-byte section array and republishes; arrays are immutable once published,
  so the packet thread reads them race-free (same publish discipline as
  `ReachabilityService.Snap`).
- Resident set: only chunks inside the player's bubble + hysteresis ring need
  to be in memory (~`(2(r+hyst)+1)²` chunks ≈ 169 at defaults ≈ well under
  0.5 MB/player). Everything else lives in the store (§3.6) and is loaded
  async as the player approaches (prefetch ring = bubble + 2).

**Dirty tracking → re-send:** cells newly marked during a scan collect their
chunk keys; the set is flushed to `RefreshScheduler.enqueue` at scan end
(throttled by the existing `refresh-per-tick`). This is exactly
`enqueueChanged()`'s contract today.

**Instant dig-reveal:** `ChunkDirtyListener.onBreak` additionally marks the
broken cell + its now-transparent neighbours explored and enqueues the chunk —
mining into a hidden pocket reveals within one refresh tick, tighter than
waiting for the next scan (the existing `reachability.invalidate` stays as the
second line of defence). Same hook on `onPlace` for the removed-cell case.

### 3.2 Engine composition (Phase 0 refactor + fog pass)

**Phase 0 — keep-mask unification (pure refactor, behavior-pinned by the
existing test suite):**

- Introduce `engine/ProcessRequest` (immutable, builder): the 17-positional-
  argument overload chain has hit its ceiling; one more flag would make it 18.
  The top overload keeps delegating so every existing test and call site
  compiles unchanged; new parameters land on the builder only.
- Refactor the REAL-tier branch of `process(...)`: today
  `solidifySealed` / `solidifyUnreachable` are two hand-rolled loops with
  subtle interaction (sealed clears `caveAir` so reachability doesn't
  double-count). Replace with: classify → compute one **keep predicate** per
  cell → one solidify loop. Sealed = "keep if surface-connected ∪ reachable";
  reachability = "keep if reachable"; both = intersection of keeps. Output
  and counters must be bit-identical — the existing tests pin this.

**Fog pass (Phase 1):** one more keep-source. With `fog-of-war` on, REAL-tier
cave air is kept iff

```
explored(idx)                      // the persisted+live bitset (long[] view)
∪ reachableSafety(idx)             // current reach mask ∩ 8-block leash (body bubble)
∪ entranceShell(idx)               // D6: within K blocks of a genuine opening,
                                   //     via the existing markRevealedShell BFS
                                   //     (K = fog-entrance-shell, default = mode's
                                   //     entrance-shell-depth) seeded w/ BorderSeed
                                   //     → seam-continuous (constraint 6)
```

Everything else below the surface solidifies — including surface-connected
caves you never looked at, which is precisely the aggression `reachability-
caves` cannot reach. The engine receives `explored` as a dense per-chunk
`long[]` bitset (packet thread flattens the sparse sections into a reusable
`ThreadLocal long[]` exactly like the `int[] blocks` buffer — zero steady-state
allocation; `long[]` is 8× smaller than the `boolean[]` masks and is the
natural read form of the stored sets; migrating the reach masks to `long[]`
too is an optional Phase-4 cleanup).

**Composition with the other layers (the briefing's "one input to the same
pass, not a parallel system"):**

- `reachability-caves` / `hide-sealed-caves`: subsumed while fog is on (D5);
  their toggles remain meaningful as the fallback regime (§3.6) and for
  servers that don't want persistence.
- `vertical-culling`: its keep-set becomes `reachable ∪ explored` (today:
  reachable only). Looking down your ravine marks it explored → it stays open
  to the floor — the exact behavior shipped in v9.2.3 is preserved and now
  also survives the scanner's warm-up.
- `anti-base-finder` (REAL): `solidifyArtificialUnreachable` generalizes to
  "scrub artificial blocks not adjacent to any *kept* cell" — a base you have
  seen stays visible, a base you haven't is scrubbed even though it is
  technically reachable.
- `reveal-distance`: unchanged — it still leashes the *scanner*. A new,
  separate `fog-recall-distance` (default 0 = off) optionally re-fogs explored
  cells beyond N blocks for servers that want the freecam bubble *on top of*
  fog. Two knobs, two names, no semantic overload.
- **Block entities:** today REAL strips nothing ("caves are real"). Under fog
  that leaks every chest/spawner position in fogged pockets, so with fog on,
  REAL also runs `stripHiddenTileEntities` against the keep-set (keep a TE iff
  its cell or a neighbour is kept / at-or-above surface). Without this, the
  whole feature is x-ray-defeatable; it ships in Phase 1, not as polish.
- SHELL/DEEP: **untouched** (constraint 4). The story is consistent: SHELL's
  entrance shell is the static approximation of "visible from outside"; fog is
  the exact dynamic version of the same idea inside the bubble.

### 3.3 What stays pure / testable

New pure code lands in `engine/`:

- `engine/SightMarch.java` — the DDA traversal over a
  `BlockAccess { int blockAt(x,y,z); }` functional interface + opacity
  predicate; deterministic, exhaustively unit-testable (axis rays, diagonal
  rays, corner-clipping, unloaded-chunk-stops, distance cap, dilation).
- `engine/ExploredCodec.java` — the (de)serialization of one player+world set
  (§3.6), pure byte-array in/out, round-trip tested standalone.
- `ProcessRequest`, the keep-mask pipeline and the fog pass — covered by
  `ChunkProcessorTest` extensions (§6).

The Bukkit-facing `ExploredSetService` stays a thin orchestrator (snapshots,
scheduling, publish), like `ReachabilityService` is today.

### 3.4 Packet-thread changes (`ChunkPacketInterceptor`)

Small and mechanical: read the explored view alongside the reach mask, flatten
to the thread-local `long[]`, set two builder fields (`fogOfWar`, `explored`),
extend the early-return condition, and run the REAL-tier TE strip when fog is
on. No new allocation per packet beyond the reused buffer. Fail-safe is
inherited: any exception → packet passes untouched.

### 3.5 Supporting changes

- `PlayerTracker`: also store yaw/pitch (float[2] alongside the pos int[3],
  fresh array per write, same race-safe pattern) — the sight sampler needs the
  look vector; the packet thread never reads it.
- `RefreshScheduler` (Phase 3): per-player **nearest-and-facing-first**
  ordering instead of FIFO, so the chunk in front of the player re-sends
  first. Bounded priority selection over the (small) pending set; no API
  change.

### 3.6 Persistence (`ExploredStore`, Phase 2)

- Layout: `plugins/CadistChunkProcessing-Pro/explored/<world-uid>/<player-uuid>.ccpf`
- Format v1: magic `CCPF`, u8 version, then per chunk: chunkKey (zig-zag
  varint delta-coded), section-presence bitmap, per present section either
  `FULL` marker or the 512-byte bitset; whole stream Deflate-compressed
  (cave-network bitsets compress 5–20×). Strictly versioned; unknown version →
  discard and start empty (D8).
- Write: async (Bukkit async task), dirty-chunk journal, flush every 60 s when
  dirty + on quit + on world change. Read: async on join/world-change,
  publish-when-ready.
- **Warm-up regime (the cold-start gap):** until *both* the explored set is
  loaded and the reachability snapshot exists, fog falls back to
  `reachability-caves` semantics when a reach mask is available, else to
  today's "feature off until warm" behavior. The fallback is strictly more
  revealing than fog but strictly safe, never buries the player, and lasts
  ~0.5–1 s. (`join-raw-seconds` continues to apply first if configured.)
- Bounds: `fog-max-chunks` per player+world (default 50 000 ≈ 5–25 MB on disk
  pre-compression; LRU-evict farthest-from-last-position), optional
  `fog-expire-days` (default 0 = never) pruned at load.
- Ops note: deleting any/all `.ccpf` files at any time is a supported
  operation (players re-explore; hiding only increases).

---

## 4. Anti-pop-in engineering (the "100% precise *feel*")

Ordered by leverage:

1. **Look-driven reveal** (§3.1 sight source) — the big one: reveals happen at
   sight speed, before arrival, which removes the briefing's "wall you walk
   through" almost entirely (it remains only when walking blind, UX contract
   #3).
2. **Implicit entrance shell** (D6) — mouths inside the bubble always read as
   mouths; the worst fog artifact ("visibly false-culled cave mouth") cannot
   occur.
3. **Monotone session reveals** — hysteresis by construction; nothing
   re-hides, so nothing can flicker.
4. **Dilation** — kills single-cell peripheral slivers between rays.
5. **Priority re-sends** (§3.5) — the chunk you're looking at wins the
   refresh-per-tick budget.
6. **Dig-instant marking** (§3.1) — mining reveal latency = one refresh tick.
7. **F5 sphere sampling** — third-person camera never shows fog walls hugging
   the player.

Residual accepted artifacts are exactly the five items in the UX contract — no
silent surprises.

---

## 5. Config / GUI / command surface

```yaml
# Fog of war: only what you have actually seen or been near is ever sent.
fog-of-war: false            # master toggle (REAL tier; subsumes reachability-caves)
fog-ray-distance: 64         # max sight-marking distance (blocks)
fog-rays-per-scan: 96        # per player, every 2 ticks; governor caps globally
fog-entrance-shell: -1       # -1 = use the mode's entrance-shell-depth; 0 disables (max aggression)
fog-recall-distance: 0       # >0: re-fog explored cells farther than this (freecam bubble on top)
fog-persist: true            # save/load explored sets
fog-max-chunks: 50000        # per player+world cap (LRU)
fog-expire-days: 0           # 0 = remember forever
```

- GUI: one `FOG` toggle (consistent with existing toggles; lore states the
  subsumption rule D5 and the persistence path), `fog-ray-distance` ±8 slider;
  stats lore: explored chunks, resident KB, rays/s, scan-µs EMA.
- Commands: `/cadistchunk fog` (toggle), `/cadistchunk fogstats`,
  and debug-gated `/cadistchunk fogaudit` — samples the player's current chunk
  and reports `sent-cave-air ⊆ keep-set` violations (0 expected; this is the
  live aggression acceptance check, §7).
- README: new section + the "Hide bases from freecam" recommended setup
  upgrades to `fog-of-war: true` as the headline profile.

---

## 6. Phasing

Each phase is independently shippable, releasable via the existing CI/release
pipeline, and lands behind defaults that change nothing.

| Phase | Contents | Size / risk | Acceptance |
|---|---|---|---|
| **P0 — Groundwork** | `ProcessRequest` builder; REAL-tier keep-mask unification; `PlayerTracker` yaw/pitch; thread-local `long[]` plumbing | ~medium / **low** (pure refactor) | Full existing test suite green, zero behavior change (the suite pins sealed/reach interaction); release as patch version |
| **P1 — Fog core (in-memory)** | `SightMarch` + `ExploredSetService` (both sources, publish, dirty→resend, dig-instant marking); engine fog keep-source + implicit entrance shell; REAL TE-strip under fog; vertical-cull keep-set `reachable ∪ explored`; anti-base explored-adjacency; config/GUI/command; warm-up fallback | large / medium | New unit tests (below) green; manual checklist on test server; `fogaudit` clean; minor version |
| **P2 — Persistence** | `ExploredCodec` + `ExploredStore` (async IO, caps, expiry, corruption-discard); prefetch ring | medium / low (safe-direction failures) | Codec round-trip + corruption tests; restart retains exploration; minor version |
| **P3 — Polish** | `RefreshScheduler` priority ordering; ray governor tuning; jitter pattern quality; stats surfacing | small / low | Freecam field-test checklist (below); patch version |
| **P4 — Optional hardening** | FULL-section sentinels everywhere, reach-mask migration to `long[]`, `estimateBytes` micro-opt (it currently sorts 4096 ints twice per section per send — measure first, optimize only if it shows up), per-world fog enable | small / low | Benchmarks before/after; no behavior change |
| **P5 — Candidate (separate plan)** | Entity culling by keep-set (mobs/item frames/pets in fogged caves currently leak via entity packets — the largest remaining side channel) | — | Out of scope here (D9) |

**Test strategy** (engine stays pure; every new mechanism unit-tested without a
server):

- `SightMarchTest`: axis/diagonal/corner rays, opacity stop, distance cap,
  unloaded-as-solid, dilation correctness, determinism.
- `ExploredCodecTest`: round-trip, FULL sentinel, truncated/corrupt → clean
  discard, version bump handling.
- `ChunkProcessorTest` additions: fog keeps explored & solidifies unexplored
  surface-connected air (the case reachability can't hide); body-bubble never
  solidified around the player; implicit entrance shell present and
  BorderSeed-seam-continuous; explored ∩ vertical-cull (ravine stays open);
  anti-base scrub vs explored adjacency; **master no-void invariant extended
  over every fog path**; P0 equivalence (keep-mask refactor produces identical
  output for sealed/reach/both on the existing fixtures).
- Standalone driver (the `/tmp/*Driver.java` pattern used throughout this
  project) for sandbox verification of P0+P1 engine behavior without Maven.

**Manual field-test checklist (P1/P3):** walk into a cave (reveals ahead of
feet), stare into a cavern from its mouth (fills in &lt; 1 s), F5 spin, mine
into a sealed pocket (reveal ≤ 1 s, never void), ender-pearl into cold terrain
(strict warm-up, no void, no burial), ravine from the top (floor visible),
freecam sweep (only explored + shell + bubble visible), rejoin (exploration
retained), `/tps` before/after with 5+ players moving.

---

## 7. Definition of done (falsifiable)

- **A (aggression):** in REAL tier with fog on, every cave-air cell on the
  wire is in `explored ∪ body-bubble ∪ entrance-shell ∪ warm-up-fallback`.
  Verified by unit test on the engine and by `fogaudit` in vivo. Freecam
  shows nothing the player hasn't seen, plus mouths (vanilla-visible) and the
  8-block bubble.
- **P (precision):** no-void invariant holds on every path (unit-tested);
  the five UX-contract artifacts are the *only* observable ones in the field
  checklist; nothing visible in normal first-person play is ever false-culled
  (D6 covers the mouth case).
- **E (efficiency):** main-thread fog cost ≤ 0.5 ms/tick at 20 active players
  on defaults (scan-µs EMA in stats proves it); packet-thread adds only bitset
  reads + reused buffers; DEEP cache hit-rate unchanged.

---

## 8. Risks & rollback

| Risk | Mitigation |
|---|---|
| Ray cost on big servers | Global governor (hard cap), stationary-player skip, budget knobs; worst case: lower `fog-rays-per-scan` → only reveal latency degrades, never correctness |
| Memory growth | Section-sparse + FULL sentinel + resident-set = bubble only + `fog-max-chunks` LRU + expiry |
| Persistence corruption | Versioned format, discard-on-error → starts empty → strictly *more* hiding (safe direction); files deletable in ops at any time |
| Pop-in complaints | §4 stack; tune `fog-entrance-shell` up; worst case disable fog per world or globally — every phase is a toggle, default off |
| P0 refactor regression | Behavior pinned by the existing suite *before* any new feature lands; P0 ships alone as a patch release |
| Subsumption confusion (fog + reachability-caves both on) | D5 documented in GUI lore, README, and config comments; stats line shows the effective regime |
| Side channels remain (light, sounds, entities) | Documented limitation (D9); entity culling is the named P5 follow-up; light/sound are research notes, not promises |

---

## 9. File-by-file change map

```
engine/ProcessRequest.java        NEW  P0   parameter object (ends the overload pyramid)
engine/ChunkProcessor.java        MOD  P0+1 keep-mask pipeline; fog keep-source; implicit shell
engine/SightMarch.java            NEW  P1   pure DDA ray traversal (BlockAccess + opacity fn)
engine/ExploredCodec.java         NEW  P2   pure (de)serialization of explored sets
ExploredSetService.java           NEW  P1   sources, publish, dirty→resend, governor
ExploredStore.java                NEW  P2   async load/save, caps, expiry
ChunkPacketInterceptor.java       MOD  P1   wire fog mask; REAL TE-strip under fog
ReachabilityService.java          MOD  P1   expose the leashed flood for the body source
PlayerTracker.java                MOD  P0   yaw/pitch
RefreshScheduler.java             MOD  P3   nearest-and-facing-first ordering
ChunkDirtyListener.java           MOD  P1   dig-instant explored marking
Config.java / config.yml          MOD  P1+2 fog keys
Gui.java / CadistChunkProcessingPro.java  MOD  P1  toggle, slider, stats, commands
test/.../ChunkProcessorTest.java  MOD  P0+1 equivalence + fog invariants
test/.../SightMarchTest.java      NEW  P1
test/.../ExploredCodecTest.java   NEW  P2
README.md / docs                  MOD  per phase
```

---

*Constraint compliance check against the briefing's §5: (1) solidify-never-void
— fog only adds solidification of transparent cells; (2) digging safety — body
bubble + dig-instant marking + existing invalidate/re-send; (3) packet-thread
purity — immutable section bitsets, thread-local buffers, inherited fail-safe;
(4) DEEP cacheable — fog is REAL-only, SHELL/DEEP untouched; (5) TPS budget —
ray/visit budgets + global governor + stats proof; (6) seams — entrance shell
seeded via BorderSeed, explored set is world-coordinate (seam-free by nature);
(7) surface protection — fog touches only sub-surface cave air; the heightmap
guard is upstream of every keep/solidify decision.*
