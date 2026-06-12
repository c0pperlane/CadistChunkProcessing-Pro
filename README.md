# CadistChunkProcessing Pro (v10 — fog-of-war remaster)

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

## Anti-Base Finder (aggressive base hiding)

A natural cave mouth reveals inward (the `entrance-shell-depth` ring) so it is
never false-culled — but a player-dug entrance tunnel, ladder/water-lift shaft
or hollowed-out room is *also* a "cave mouth", so by default it would reveal too
and quietly betray a base to anyone scanning the surface or peeking through a
wall. **Anti-Base Finder** closes that gap. With it on, in the hidden tiers:

- **Man-made space never opens.** A connected pocket of cave space that touches
  *any* player-signature block — the whole tunnel / shaft / room, not just the
  part next to the block — is treated as a base and stays solid even when it
  reaches a genuine opening. The shell reveal can no longer crack a base
  entrance from a corner. Natural caves (bordered only by terrain) still reveal
  exactly as before.
- **Base materials read as rock.** Player-signature blocks *below the surface*
  (ladders, rails, redstone, lamps/lanterns, doors, signs, building blocks,
  mineral-storage blocks, …) are recoloured to the ghost rock, so nothing leaks
  through a thin wall or a window.

It is strictly sub-surface and "solidify, never void" like the rest of the
engine: surfaces stay vanilla and the **REAL bubble is untouched**, so *your*
base re-appears in full as you walk up to it. **On by default** (v10): fog of war
hides cave *air* you haven't seen, but a base's *solid* man-made blocks aren't cave
air, so anti-base is what scrubs them — the two together fully hide a base. Toggle
in the GUI, with `/cadistchunk antibase`, or `anti-base-finder` in the config.

PacketEvents (installed as its own plugin) is used to intercept `CHUNK_DATA`.
The engine never touches the Bukkit API on the packet thread (LeafMC / Moonrise
safe). On any error the packet is passed through untouched.

## Reachability ore reveal

The default ore policy keeps a vein visible if it's exposed to air within a fixed
**radius** of you — which can leak ore through a wall. **Reachability ore reveal**
(`reachability-ores`, GUI toggle, `/cadistchunk reach`) replaces that radius with
true **reachability**: an ore stays visible only if it touches air you can
*actually reach* — the connected cave/tunnel you're standing in. A vein a few
blocks away behind solid rock stays hidden, the cave you're in stays fully lit,
and it can't be peeked through a wall or with freecam (it's computed from your
real body position, and unreachable space is never sent).

A small, bounded flood-fill runs around each player on the main thread a few
times a second (only when you move into new space); while it's warming up it
falls back to `ore-reveal-radius`, so you never get a blank gap. Scanning is
bounded to the real bubble and a vertical band, but on a large/busy server keep
an eye on `/tps` after enabling. Off by default.

### Reachability cave/base hiding

The same reachability primitive can hide *geometry*, not just ore.
`reachability-caves` (GUI toggle, `/cadistchunk reachcaves`) solidifies, even in
the close-up real bubble, every cave-air cell you **can't reach** — so only the
cave you're actually standing in stays real. A cave or base you aren't inside
reads as solid rock even up close, and **freecam can't see it** because it was
never sent. With `anti-base-finder` also on, the enclosed man-made blocks of a
base you can't reach are scrubbed too, so its walls don't outline it on x-ray.

Digging in the cave you're in is correct; mining a wall into a hidden pocket
reveals it within a moment (a re-scan + re-send — never void, just a brief
catch-up). A **sealed** base (behind a closed door/trapdoor/wall) becomes fully
hidden; a cave that's genuinely open and walk-into-able from where a viewer
stands is real terrain and can't be hidden. Recommended together with
cave-hiding + anti-base-finder. Off by default.

### Sealed-cave hiding

`reachability-caves` is aggressive — it hides *every* cave you can't reach, which
can pop a genuinely-open cave out of view as you move. **Sealed-cave hiding**
(`hide-sealed-caves`, GUI toggle, `/cadistchunk sealedcaves`) is the gentle
sibling: in the close-up real bubble it solidifies only the caves that have **no
entrance to the open sky** — fully walled-off pockets and sealed rooms — while
leaving every cave that genuinely reaches the surface (real, visible mouths)
untouched. So the swiss-cheese of enclosed cavities that freecam/x-ray reveals
around you reads as solid rock, but nothing *visible* is ever false-culled.

The cave/room you're actually standing in is kept (via the same reachability
scanner, so a sealed base behind a closed door never solidifies around you), and
it's seam-continuous across chunks. Pure solidify — never void; mining into a
sealed pocket reveals it within a moment. It's the natural companion to vertical
culling: it removes the sealed caves the vertical margin would otherwise leave
floating above the cut. Off by default.

### Reveal-distance leash

By default reachability reveals the *whole* connected cave system you can reach —
so standing in or at the mouth of a big system, a viewer can still see all of it.
**`reveal-distance`** (GUI slider) caps that: only connected air within N blocks
(3-D, straight-line) of you stays revealed; anything further is hidden and
re-reveals as you move closer. Freecam then sees only a bubble around you, not the
whole system, while the cave you're actually in stays correct. It also bounds how
far down a shaft you can see (vertical culling keeps reachable air), so set it at
least as deep as the ravines you want to see into. `0` = unlimited (original
behavior). Only active while a reachability feature is on (it shapes that
scanner). This is the zero-state, no-pop-in alternative to fog-of-war.

### Fog of war (strongest anti-freecam)

`reachability-caves` hides what you *can't reach*; **fog of war** (`fog-of-war`,
GUI toggle, `/cadistchunk fog`) hides what you *haven't seen*. In the REAL bubble
it keeps a cave-air cell real only if you have actually **looked at it** (eye
raycasts) or been within ~8 blocks of it (the body bubble) — everything else
below the surface reads as solid rock, **including open caves you could walk into
but never entered**. This is the strongest predicate a server-side packet filter
can give: a freecam/x-ray client learns *nothing you haven't already legitimately
seen*. You cannot hide more than that without corrupting normal play (caves you're
looking at would turn to stone), so it is the aggressive endpoint of the curve.

It stays precise for the honest player: visible cave mouths in your bubble still
read as mouths (an entrance shell, seam-continuous across chunks), the bubble
around you is always real, looking at something reveals it *before* you reach it,
and reveals are monotone within a session (no flicker). Mining into a fogged
pocket reveals it within a moment (re-scan + re-send — never void). It **subsumes
`reachability-caves`** when both are on (fog is the stricter keep), and vertical
culling keeps everything you've explored open to its floor.

A bounded, throttled main-thread eye-raycast scan runs per moving/looking player
(a global per-tick ray governor caps the total) — watch `/tps` on a very large
server. `fog-ray-distance` (GUI slider) sets how far sight reveals;
**`fog-body-radius`** (GUI slider "Fog live radius", default 8, 2–64) sets the
always-real bubble around you — the live radius that stays visible even where you
haven't looked, for digging safety. Bigger = more is always real right around you.

Exploration **persists to disk** per player per world
(`plugins/CadistChunkProcessing-Pro/explored/<world>/<player>.ccpf`,
Deflate-compressed) so it survives restarts; those files are safe to delete at any
time (players just re-explore — hiding only ever increases). `fog-persist: false`
keeps it in-memory; `fog-expire-days` forgets stale exploration. A live settings
change resets the in-memory set, which rebuilds within a second of moving/looking.

**On by default** (v10) together with Anti-Base Finder and ore hiding — dropping the
jar in gives aggressive anti-freecam + anti-xray out of the box. Set `fog-of-war:
false` to fall back to the lighter distance-only hiding.

### Surface-entrance camouflage

A buried base is hidden, but its **door at the surface** — a trapdoor, ladder
shaft, hatch or water-lift dropping into the ground — still gives it away from a
distance. `surface-entrances` (GUI toggle, `/cadistchunk entrances`) closes that:
in the hidden tiers, a column that is a **narrow pit** (every neighbour's ground
is higher — a 1–2 wide shaft, not a slope or a wide-open cavern) **and** holds a
man-made block or fluid is capped up to the surrounding rim with the neighbour's
own surface blocks, so from afar the ground reads as untouched. It reappears in
the REAL bubble so you can use your own entrance.

This is the single deliberate exception to "never touch the surface", kept narrow
and artificial/fluid-gated so natural ravines and cave mouths are left alone, and
pure-solidify so it never creates void. A base left **wide open to the sky** (a
big sinkhole/open cavern) is surface terrain and can't be hidden this way — seal
the entrance. Off by default.

## Recommended setups

**The default (v10) is the full package** — drop the jar in and you get aggressive
anti-freecam + anti-xray with no tuning:

- **Default — everything the plugin promises, on.** `fog-of-war` + `anti-base-finder`
  + `ore-hiding` + `hide-block-entities` + `cave-hiding` + `vertical-culling`
  (`vertical-margin: 8`). Only what you've actually seen or been near is ever real;
  unexplored caves *and* a base's solid man-made blocks read as rock; ores are
  camouflaged. Runs the bounded eye-raycast scan — watch `/tps` on a very large
  server. This is the headline setup; the GUI opens on exactly these.
- **Lighter / no per-player scan.** Set `fog-of-war: false`. You keep the distance
  bandwidth saving (`cave-hiding` + `vertical-culling`) and ore anti-xray with zero
  per-player cost; nothing scans. Good for huge servers that can't spare the ticks.
- **Paranoid ores.** Add `hide-all-ores: true` for zero ore leakage (nobody sees ore
  until they mine it).

The legacy hiders fog of war supersedes — `reachability-ores`/`-caves`,
`hide-sealed-caves`, `reveal-distance` — live on the **Outdated** sub-page of the GUI
(bottom-right) and are off by default; they're only useful with `fog-of-war: false`.

All of it is live-toggleable in `/cadistchunk gui`.

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
- `/cadistchunk antibase` — toggle the Anti-Base Finder (aggressive base hiding)
- `/cadistchunk reach` — toggle reachability ore reveal (see below)
- `/cadistchunk reachcaves` — toggle reachability cave/base hiding (see below)
- `/cadistchunk sealedcaves` — toggle sealed-cave hiding (entrance-less caves; see below)
- `/cadistchunk fog` — toggle fog of war (hide everything you haven't seen; see below)
- `/cadistchunk entrances` — toggle surface-entrance camouflage (see below)
- `/cadistchunk reload`

Permission `cadistchunkprocessing.bypass` makes a player receive raw chunks.

## Build

Requires JDK 21. PacketEvents must be installed on the target server.

```bash
mvn clean package
# -> target/CadistChunkProcessing-Pro-10.2.0-beta.jar
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

## What's new in v10 (the fog-of-war remaster)

- **Fog of war** — the strongest correct anti-freecam cull: only what you've
  actually *seen* (eye raycasts) or been near (the configurable *live radius*) is
  ever sent real. Persists to disk, subsumes the old reachability hiders.
- **Deploy-easy defaults** — fog of war + anti-base + ore anti-xray + the
  bandwidth tiers are **on out of the box**; drop the jar in and it delivers the
  promise with no tuning (`fog-of-war: false` for the lighter, no-scan profile).
- **GUI split** — the main panel is the modern kit; the superseded reachability /
  sealed-cave / reveal-distance hiders moved to an **Outdated** sub-page
  (bottom-right).
- **Configurable live radius** (`fog-body-radius`, GUI slider) — the always-real
  bubble you've wanted, 2–64 blocks.

See the per-feature sections above for details.

## Metrics (bStats)

Pro reports **anonymous** usage stats via [bStats](https://bstats.org): server
count, MC/Java version, and which features are enabled — **no player data, no
chunk data, nothing identifying**. It helps gauge adoption and which features
matter. Opt out any time with `metrics: false` in the config (or globally in
`plugins/bStats/config.yml`). bStats is shaded in (relocated) so it never clashes
with another plugin's copy.

## License

CadistChunkProcessing Pro is **proprietary** — see [`LICENSE`](LICENSE). In short:
if you have a license you may **run it on your own server(s), including
commercially, and modify it for your own use** — but you may **not** redistribute,
resell, publish, or share it (modified or not). It is not open source. For
redistribution or white-label terms, contact the author.
