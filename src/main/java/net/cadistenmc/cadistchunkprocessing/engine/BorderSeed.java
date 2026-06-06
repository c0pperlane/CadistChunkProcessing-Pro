package net.cadistenmc.cadistchunkprocessing.engine;

/**
 * Supplies cross-chunk "opening" information for the SHELL tier, so a cave that
 * spans a chunk boundary reveals to the same depth on both sides (no flat seam
 * wall). A face block is an "opening" if the adjacent block in the neighbouring
 * chunk is cave space the neighbour kept revealed, or exterior air.
 *
 * <p>All coordinates are this chunk's local coordinates on the shared face.
 * Implementations must be safe to call from the packet thread (no Bukkit API).
 * May be {@code null} at call sites, meaning "no neighbour info" — the engine
 * then seeds from this chunk's own surface openings only.
 */
public interface BorderSeed {
    /** Opening just west of x=0, at local (y,z). */
    boolean openingWest(int y, int z);
    /** Opening just east of x=15, at local (y,z). */
    boolean openingEast(int y, int z);
    /** Opening just north of z=0, at local (y,x). */
    boolean openingNorth(int y, int x);
    /** Opening just south of z=15, at local (y,x). */
    boolean openingSouth(int y, int x);
}
