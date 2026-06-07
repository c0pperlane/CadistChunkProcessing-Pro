package net.cadistenmc.cadistchunkprocessing;

import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.SimplePacketListenerAbstract;
import com.github.retrooper.packetevents.event.simple.PacketPlaySendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import net.cadistenmc.cadistchunkprocessing.engine.BlockClassifier;
import net.cadistenmc.cadistchunkprocessing.engine.BorderSeed;
import net.cadistenmc.cadistchunkprocessing.engine.ChunkProcessor;
import net.cadistenmc.cadistchunkprocessing.engine.ModeParams;
import net.cadistenmc.cadistchunkprocessing.engine.OreView;
import net.cadistenmc.cadistchunkprocessing.engine.Tier;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.UUID;

/**
 * Intercepts outgoing CHUNK_DATA and rewrites it per the player's distance tier.
 * Everything it reads is from concurrent caches ({@link PlayerTracker},
 * {@link WorldMeta}) so it never calls the Bukkit API on the packet thread.
 * Any failure leaves the packet untouched — the plugin can never produce void.
 */
public final class ChunkPacketInterceptor extends SimplePacketListenerAbstract {

    private final CadistChunkProcessingPro plugin;
    private final Config config;
    private final WorldMeta worldMeta;
    private final PlayerTracker tracker;
    private final BorderCache borderCache;
    private final ProcessedChunkCache chunkCache;
    private final BandwidthMonitor monitor;
    private final BlockClassifier classifier;
    private final ReachabilityService reachability;

    private final ThreadLocal<int[]> blocks = ThreadLocal.withInitial(() -> new int[24 * 16 * 256]);
    private final ThreadLocal<ChunkProcessor> processor;

    /**
     * The {@code private final TileEntity[]} backing {@link Column}. Resolved
     * once; if PacketEvents ever renames it, this stays null and block-entity
     * stripping is silently skipped (the packet is still safe, just leakier).
     */
    private static final Field COLUMN_TILE_ENTITIES = resolveTileEntityField();

    private static Field resolveTileEntityField() {
        try {
            Field f = Column.class.getDeclaredField("tileEntities");
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    public ChunkPacketInterceptor(CadistChunkProcessingPro plugin, Config config, WorldMeta worldMeta,
                                  PlayerTracker tracker, BorderCache borderCache, ProcessedChunkCache chunkCache,
                                  BandwidthMonitor monitor, BlockClassifier classifier,
                                  ReachabilityService reachability) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        this.config = config;
        this.worldMeta = worldMeta;
        this.tracker = tracker;
        this.borderCache = borderCache;
        this.chunkCache = chunkCache;
        this.monitor = monitor;
        this.classifier = classifier;
        this.reachability = reachability;
        this.processor = ThreadLocal.withInitial(() -> new ChunkProcessor(classifier));
    }

    @Override
    public void onPacketPlaySend(PacketPlaySendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.CHUNK_DATA) return;
        try {
            handle(event);
        } catch (Exception ex) {
            if (config.debug()) {
                plugin.getLogger().warning("[CCP] chunk processing skipped: " + ex);
            }
            // Swallow — an unmodified packet is always safe.
        }
    }

    private void handle(PacketPlaySendEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID id = player.getUniqueId();
        if (player.hasPermission("cadistchunkprocessing.bypass")) return;

        boolean caves = config.caveHiding();
        boolean ores = config.oreHiding();
        if (!caves && !ores && !config.reachabilityCaves()) return;

        UUID worldId = tracker.worldUID(id);
        if (worldId == null) return;
        WorldMeta.Meta meta = worldMeta.get(worldId);
        if (meta == null || !config.worldEnabled(meta.name())) return;
        if (tracker.inJoinGrace(id, config.joinRawSeconds())) return;

        Long pk = tracker.chunkKey(id);
        if (pk == null) return;
        int pcx = (int) (pk >> 32), pcz = (int) (long) pk;

        WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
        Column column = wrapper.getColumn();
        BaseChunk[] sections = column.getChunks();
        if (sections == null || sections.length == 0) return;

        int cx = column.getX(), cz = column.getZ();
        int dist = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));

        ModeParams params = config.params();
        Tier tier;
        if (!caves) {
            tier = Tier.REAL;
        } else if (dist <= params.realRadius()) {
            tier = Tier.REAL;
        } else if (dist <= params.caveRenderDistance()) {
            tier = Tier.SHELL;
        } else {
            tier = Tier.DEEP;
        }

        // DEEP fast path: a DEEP transform is player-independent, so reuse a
        // cached result for this chunk if we have one (skips the engine's many
        // full-column passes). Only when chunk-cache is on; "live" mode forces a
        // fresh process every send so behaviour can be watched in production.
        boolean cacheable = config.chunkCache() && tier == Tier.DEEP;
        if (cacheable) {
            ProcessedChunkCache.Entry hit = chunkCache.get(worldId, cx, cz);
            if (hit != null && hit.ySize() == sections.length * 16) {
                applyProcessed(event, column, hit, meta.minY());
                monitor.addPacket(Tier.DEEP, hit.bytesBefore(), hit.bytesAfter(),
                        hit.oresHidden(), hit.blocksSolidified(), true);
                return;
            }
        }

        int[] buf = ensureBuffer(sections.length);
        int ySize = ColumnCodec.flatten(column, buf);
        if (ySize == 0) return;

        BorderSeed seed = (tier == Tier.SHELL) ? borderCache.seedFor(worldId, cx, cz, ySize) : null;

        // Reachability mask for this chunk (REAL tier only): the air the player can
        // actually reach. Used for ore reveal and/or cave hiding; null while warming
        // up so both gracefully fall back.
        boolean reachSnap = tier == Tier.REAL && config.reachabilityActive()
                && reachability.hasSnapshot(id, worldId, ySize);
        boolean[] reachMask = reachSnap ? reachability.maskFor(id, cx, cz) : null;

        OreView oreView = oreViewFor(tier, id, cx, cz, reachSnap, reachMask);
        int verticalCut = verticalCutFor(tier, id, meta.minY(), ySize);
        boolean[] caveReach = config.reachabilityCaves() ? reachMask : null;
        ChunkProcessor.Result res = processor.get().process(
                buf, ySize, meta.minY(), tier, params, ores, oreView,
                meta.ghostHigh(), meta.ghostLow(), seed, verticalCut, config.antiBaseFinder(),
                caveReach, config.reachabilityCaves());

        if (caves) {
            storeFaces(worldId, cx, cz, ySize, buf);
        }

        boolean reEncode = res.modified;
        if (res.modified) {
            ColumnCodec.apply(column, buf);
        }

        // Collapse the biome palette of fully-hidden (uniform) sections so the
        // biome bytes compress away alongside the block bytes. Invisible.
        if (caves && tier != Tier.REAL && config.collapseBiomes()
                && BiomeCollapser.collapse(column, buf, ySize) > 0) {
            reEncode = true;
        }

        // Anti-xray for block entities: a chunk packet carries a block-entity
        // list (chests, spawners, end portals…) independent of the block array,
        // so camouflaging ores/caves alone still leaks their exact positions.
        // In SHELL/DEEP, drop the ones below the surface (they return when the
        // chunk re-sends as REAL on approach). REAL keeps all — caves are real.
        if (caves && tier != Tier.REAL && config.hideBlockEntities()
                && stripHiddenTileEntities(column, res.heightMap, meta.minY())) {
            reEncode = true;
        }

        if (reEncode) {
            event.markForReEncode(true);
        }

        // Store the finished DEEP transform for replay to other players / re-sends.
        if (cacheable && res.modified) {
            chunkCache.put(worldId, cx, cz, new ProcessedChunkCache.Entry(
                    Arrays.copyOf(buf, ySize << 8), res.heightMap, ySize,
                    res.bytesBefore, res.bytesAfter, res.oresHidden, res.blocksSolidified));
        }

        // Palette-accurate block-data reduction (light excluded — it is sent
        // unchanged and compresses to almost nothing on the wire).
        monitor.addPacket(tier, res.bytesBefore, res.bytesAfter, res.oresHidden, res.blocksSolidified, res.modified);
    }

    /** Replay a cached DEEP result onto this player's column (blocks + biomes + TE strip). */
    private void applyProcessed(PacketPlaySendEvent event, Column column,
                                ProcessedChunkCache.Entry e, int minY) {
        ColumnCodec.apply(column, e.blocks());
        if (config.collapseBiomes()) {
            BiomeCollapser.collapse(column, e.blocks(), e.ySize());
        }
        if (config.hideBlockEntities()) {
            stripHiddenTileEntities(column, e.heightMap(), minY);
        }
        event.markForReEncode(true);
    }

    /**
     * Local-Y below which to vertically cull, or -1 to disable. Only the REAL
     * bubble (where full columns are otherwise sent) is culled — and only below
     * the player's depth margin, so the deep column under a surface player is
     * solidified. SHELL/DEEP return -1 so DEEP stays player-independent/cacheable.
     */
    private int verticalCutFor(Tier tier, UUID id, int minY, int ySize) {
        if (!config.verticalCulling() || tier != Tier.REAL) return -1;
        int[] pos = tracker.pos(id);
        if (pos == null) return -1;
        int cutLocalY = (pos[1] - config.verticalMargin()) - minY;
        if (cutLocalY <= 0) return -1;             // player near world bottom: nothing to cull
        return Math.min(cutLocalY, ySize);
    }

    /**
     * Anti-xray policy for this chunk:
     *  - hide-all-ores on  → nothing visible (paranoid).
     *  - distant chunk      → only surface-exposed ore (a hillside vein stays).
     *  - the real bubble    → surface-exposed OR ore within reveal radius of the
     *                         player (the cave you're actually standing in).
     */
    private OreView oreViewFor(Tier tier, UUID id, int cx, int cz, boolean reachSnap, boolean[] reachMask) {
        if (config.hideAllOres()) return OreView.hideAll();
        if (tier != Tier.REAL) return OreView.surfaceOnly();

        // Reachability ore reveal: show ore only where it touches air the player
        // can actually reach (the cave they're in) — never a radius through walls.
        if (config.reachabilityOres() && reachSnap) {
            // mask present -> reveal against it; absent -> this REAL chunk has no
            // reachable air, so be strict (surface veins only).
            return reachMask != null ? OreView.surfaceAndReachable(reachMask) : OreView.surfaceOnly();
        }

        int[] pos = tracker.pos(id);
        if (pos == null) return OreView.surfaceOnly();
        return OreView.surfaceAndNear(pos[0], pos[1], pos[2], config.oreRevealRadius(), cx << 4, cz << 4);
    }

    private int[] ensureBuffer(int sectionCount) {
        int need = sectionCount * 16 * 256;
        int[] buf = blocks.get();
        if (buf.length < need) {
            buf = new int[need];
            blocks.set(buf);
        }
        return buf;
    }

    /**
     * Remove block entities that sit below their column's surface from a hidden
     * (SHELL/DEEP) chunk, so a cheat client cannot read buried chest / spawner
     * positions out of the packet. Returns true if any were removed.
     *
     * <p>Uses reflection to replace {@link Column}'s {@code private final
     * TileEntity[]} in place — preserving every other field (heightmaps, biomes)
     * exactly, which the version-specific {@code Column} constructors cannot
     * guarantee on 1.21. Any failure leaves the list untouched (still safe).
     */
    private boolean stripHiddenTileEntities(Column column, int[] heightMap, int minY) {
        if (COLUMN_TILE_ENTITIES == null || heightMap == null) return false;
        TileEntity[] tes = column.getTileEntities();
        if (tes == null || tes.length == 0) return false;

        TileEntity[] kept = new TileEntity[tes.length];
        int n = 0;
        for (TileEntity te : tes) {
            if (te == null) continue;
            int lx = te.getX() & 15, lz = te.getZ() & 15;
            int surface = heightMap[(lz << 4) | lx];
            int localY = te.getY() - minY;
            // Keep surface/above (or columns with no terrain we couldn't classify).
            if (surface < 0 || localY >= surface) kept[n++] = te;
        }
        if (n == tes.length) return false;   // nothing was below the surface

        try {
            COLUMN_TILE_ENTITIES.set(column, Arrays.copyOf(kept, n));
            return true;
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    /** Record processed border transparency so neighbours' SHELL reveal stays seamless. */
    private void storeFaces(UUID world, int cx, int cz, int ySize, int[] buf) {
        boolean[] w = new boolean[ySize * 16];
        boolean[] e = new boolean[ySize * 16];
        boolean[] n = new boolean[ySize * 16];
        boolean[] s = new boolean[ySize * 16];
        for (int y = 0; y < ySize; y++) {
            int yShift = y << 8;
            for (int i = 0; i < 16; i++) {
                int pos = y * 16 + i;
                w[pos] = classifier.isTransparent(buf[yShift | (i << 4) | 0]);
                e[pos] = classifier.isTransparent(buf[yShift | (i << 4) | 15]);
                n[pos] = classifier.isTransparent(buf[yShift | (0 << 4) | i]);
                s[pos] = classifier.isTransparent(buf[yShift | (15 << 4) | i]);
            }
        }
        borderCache.store(world, cx, cz, new BorderCache.Faces(ySize, w, e, n, s));
    }
}
