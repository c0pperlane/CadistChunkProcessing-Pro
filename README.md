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
base re-appears in full as you walk up to it. It is aggressive by design — a
natural cave that a base tunnels into will also be hidden while far away — so it
is **off by default**; enable it in the GUI, with `/cadistchunk antibase`, or via
`anti-base-finder: true` in the config.

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
- `/cadistchunk entrances` — toggle surface-entrance camouflage (see below)
- `/cadistchunk reload`

Permission `cadistchunkprocessing.bypass` makes a player receive raw chunks.

## Build

Requires JDK 21. PacketEvents must be installed on the target server.

```bash
mvn clean package
# -> target/CadistChunkProcessing-Pro-9.1.0.jar
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
