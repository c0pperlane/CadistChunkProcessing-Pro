package net.cadistenmc.cadistchunkprocessing;

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import org.bukkit.World;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches the few per-world facts the packet thread needs (min height, name,
 * environment-appropriate ghost blocks) so we never touch the Bukkit API while
 * processing a packet — required for LeafMC / Moonrise (threaded chunks).
 */
public final class WorldMeta {

    /** Immutable per-world snapshot. */
    public record Meta(String name, int minY, int ghostHigh, int ghostLow) {}

    private static volatile int STONE, DEEPSLATE, NETHERRACK, END_STONE;

    /** Resolve ghost ids once PacketEvents' registry is available (onEnable). */
    public static void initIds() {
        STONE = idOf("minecraft:stone", 1);
        DEEPSLATE = idOf("minecraft:deepslate", STONE);
        NETHERRACK = idOf("minecraft:netherrack", STONE);
        END_STONE = idOf("minecraft:end_stone", STONE);
    }

    private static int idOf(String key, int fallback) {
        try {
            WrappedBlockState s = WrappedBlockState.getByString(key);
            return s != null ? s.getGlobalId() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private final ConcurrentHashMap<UUID, Meta> byWorld = new ConcurrentHashMap<>();

    public void refresh(World w) {
        int high, low;
        switch (w.getEnvironment()) {
            case NETHER -> { high = NETHERRACK; low = NETHERRACK; }
            case THE_END -> { high = END_STONE; low = END_STONE; }
            // Overworld: fill EVERYTHING with stone (incl. the deepslate layer).
            // Stone's global id is version-stable; the deepslate id from
            // PacketEvents can mismatch this build and render dark/void. Hidden
            // fill is never seen until revealed real, so the colour is moot —
            // this makes deepslate behave exactly like the stone layer.
            default -> { high = STONE; low = STONE; }
        }
        byWorld.put(w.getUID(), new Meta(w.getName(), w.getMinHeight(), high, low));
    }

    public Meta get(UUID worldId) {
        return byWorld.get(worldId);
    }

    public void remove(UUID worldId) {
        byWorld.remove(worldId);
    }
}
