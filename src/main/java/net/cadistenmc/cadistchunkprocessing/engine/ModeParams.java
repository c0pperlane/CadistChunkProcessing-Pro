package net.cadistenmc.cadistchunkprocessing.engine;

/**
 * The numeric knobs for one mode (BALANCED / MAX_SAVINGS / GENEROUS).
 *
 * @param realRadius          chunks around the player sent fully real (>= 2 so
 *                            no reachable block is ever solidified).
 * @param caveRenderDistance  chunks within which cave openings still reveal
 *                            inward; beyond it, the DEEP tier applies.
 * @param entranceShellDepth  blocks of cave revealed inward from a real opening.
 * @param homogenizeBelow     a DEEP section collapses to one block when it lies
 *                            entirely this many blocks below the lowest surface.
 * @param rockCollapse        DEEP also merges varied rock into the ghost block
 *                            for true single-palette sections.
 * @param revealHysteresis    chunks; a revealed chunk is only re-hidden once it
 *                            is this far beyond realRadius (anti-thrash).
 */
public record ModeParams(
        int realRadius,
        int caveRenderDistance,
        int entranceShellDepth,
        int homogenizeBelow,
        boolean rockCollapse,
        int revealHysteresis
) {
    public ModeParams {
        realRadius = Math.max(2, realRadius);
        caveRenderDistance = Math.max(realRadius, caveRenderDistance);
        entranceShellDepth = Math.max(0, entranceShellDepth);
        homogenizeBelow = Math.max(0, homogenizeBelow);
        revealHysteresis = Math.max(0, revealHysteresis);
    }
}
