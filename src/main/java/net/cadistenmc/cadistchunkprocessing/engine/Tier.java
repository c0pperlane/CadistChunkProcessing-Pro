package net.cadistenmc.cadistchunkprocessing.engine;

/**
 * Per-chunk processing tier, chosen by distance from the player.
 *
 * <ul>
 *   <li>{@link #REAL}  — geometry untouched (so digging is always correct);
 *       only buried-ore camouflage is applied.</li>
 *   <li>{@link #SHELL} — caves are revealed a few blocks inward from genuine
 *       openings (so visible mouths/holes are never false-culled) and solidified
 *       beyond that.</li>
 *   <li>{@link #DEEP}  — everything below the surface is solidified, and fully
 *       sub-surface sections are homogenised to a single block for maximum
 *       compression.</li>
 * </ul>
 *
 * No tier ever writes air where a solid was — the engine only ever adds solid.
 */
public enum Tier {
    REAL,
    SHELL,
    DEEP
}
