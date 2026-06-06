package net.cadistenmc.cadistchunkprocessing.engine;

/**
 * How the anti-xray layer decides which ores stay visible for a given chunk.
 *
 * <ul>
 *   <li>{@link Mode#HIDE_ALL} — camouflage every ore (paranoid; nobody sees ore
 *       until they mine it).</li>
 *   <li>{@link Mode#KEEP_EXPOSED} — keep any ore touching air (vanilla-ish; used
 *       by tests).</li>
 *   <li>{@link Mode#SURFACE_ONLY} — keep only ores exposed to open-sky/exterior
 *       air. Used for distant (SHELL/DEEP) chunks: a surface vein on a hillside
 *       stays visible, but cave/buried ore is hidden.</li>
 *   <li>{@link Mode#SURFACE_AND_NEAR} — keep surface-exposed ore, OR ore exposed
 *       to air within {@code radius} blocks of the player. Used for the REAL
 *       bubble so you see ore in the cave you are actually standing in, but not
 *       by digging down and peering through walls.</li>
 * </ul>
 */
public final class OreView {

    public enum Mode { HIDE_ALL, KEEP_EXPOSED, SURFACE_ONLY, SURFACE_AND_NEAR }

    public final Mode mode;
    public final int px, py, pz;   // player block position (world coords)
    public final long radiusSq;    // squared reveal radius
    public final int worldX, worldZ; // this chunk's block origin (cx*16, cz*16)

    private OreView(Mode mode, int px, int py, int pz, int radius, int worldX, int worldZ) {
        this.mode = mode;
        this.px = px; this.py = py; this.pz = pz;
        this.radiusSq = (long) radius * radius;
        this.worldX = worldX; this.worldZ = worldZ;
    }

    public static OreView hideAll() { return new OreView(Mode.HIDE_ALL, 0, 0, 0, 0, 0, 0); }
    public static OreView keepExposed() { return new OreView(Mode.KEEP_EXPOSED, 0, 0, 0, 0, 0, 0); }
    public static OreView surfaceOnly() { return new OreView(Mode.SURFACE_ONLY, 0, 0, 0, 0, 0, 0); }

    public static OreView surfaceAndNear(int px, int py, int pz, int radius, int worldX, int worldZ) {
        return new OreView(Mode.SURFACE_AND_NEAR, px, py, pz, radius, worldX, worldZ);
    }
}
