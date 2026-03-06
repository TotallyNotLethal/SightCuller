package com.lastbreath.hc.sightculler.visibility;

import com.lastbreath.hc.sightculler.SightCullerPlugin;
import com.lastbreath.hc.sightculler.cache.PlayerVisibilityCache;
import com.lastbreath.hc.sightculler.config.SightCullerConfig;
import com.lastbreath.hc.sightculler.mask.MaskPaletteResolver;
import com.lastbreath.hc.sightculler.movement.MovementTracker;
import com.lastbreath.hc.sightculler.snapshot.ChunkSectionSnapshot;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.BitSet;

public final class VisibilityEngine {
    private final SightCullerPlugin plugin;
    private volatile SightCullerConfig config;
    private final PlayerVisibilityCache cache;
    private final SectionDirtyTracker dirtyTracker;
    private final MovementTracker movementTracker;
    private final ExposureGraphBuilder exposureGraphBuilder;
    private final MaskPaletteResolver maskPaletteResolver;

    public VisibilityEngine(
            SightCullerPlugin plugin,
            SightCullerConfig config,
            PlayerVisibilityCache cache,
            SectionDirtyTracker dirtyTracker,
            MovementTracker movementTracker,
            ExposureGraphBuilder exposureGraphBuilder,
            MaskPaletteResolver maskPaletteResolver
    ) {
        this.plugin = plugin;
        this.config = config;
        this.cache = cache;
        this.dirtyTracker = dirtyTracker;
        this.movementTracker = movementTracker;
        this.exposureGraphBuilder = exposureGraphBuilder;
        this.maskPaletteResolver = maskPaletteResolver;
    }

    public void reload(SightCullerConfig config) {
        this.config = config;
        this.maskPaletteResolver.reload(config);
    }

    public boolean isWorldEnabled(World world) {
        return config.enabled() && config.worldEnabled(world.getName());
    }

    public boolean shouldReveal(Player player, World world, int x, int y, int z, Material originalMaterial) {
        if (!isWorldEnabled(world)) return true;
        if (!config.hiddenMaterials().contains(originalMaterial)) return true;

        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int sectionY = y >> 4;
        PlayerVisibilityCache.VisibilityValue value = compute(player, world, chunkX, chunkZ, sectionY);
        int local = ChunkSectionSnapshot.index(x & 15, y & 15, z & 15);
        return value.revealedSolidCells().get(local);
    }

    public Material maskMaterial(World world, int y) {
        return maskPaletteResolver.maskFor(world.getEnvironment(), y);
    }

    public PlayerVisibilityCache.VisibilityValue compute(Player player, World world, int chunkX, int chunkZ, int sectionY) {
        MovementTracker.TrackedState state = movementTracker.get(player);
        PlayerVisibilityCache.VisibilityKey key = new PlayerVisibilityCache.VisibilityKey(
                player.getUniqueId(), world.getName(), chunkX, chunkZ, sectionY, state.yawBucket(), state.pitchBucket()
        );

        boolean dirty = dirtyTracker.consumeDirty(world.getName(), chunkX, chunkZ, sectionY);
        if (!dirty) {
            PlayerVisibilityCache.VisibilityValue cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        ChunkSectionSnapshot snapshot = ChunkSectionSnapshot.fromChunkSnapshot(
                world.getName(), chunkX, chunkZ, sectionY, chunk.getChunkSnapshot(true, false, false)
        );

        ExposureGraphBuilder.ExposureGraph graph = exposureGraphBuilder.compute(snapshot, world.getHighestBlockYAt((chunkX << 4) + 8, (chunkZ << 4) + 8));
        BitSet revealed = gateByPlayerCone(player, snapshot, graph);
        PlayerVisibilityCache.VisibilityValue value = new PlayerVisibilityCache.VisibilityValue(revealed, graph.exposedAirCells(), System.currentTimeMillis(), "computed");
        cache.put(key, value);
        return value;
    }

    private BitSet gateByPlayerCone(Player player, ChunkSectionSnapshot snapshot, ExposureGraphBuilder.ExposureGraph graph) {
        BitSet revealed = new BitSet(4096);
        Vector eye = player.getEyeLocation().toVector();
        Vector look = player.getLocation().getDirection().normalize();

        double maxDistSq = config.maxRevealDistance() * config.maxRevealDistance();
        double horizDot = Math.cos(Math.toRadians(config.horizontalFovDegrees() / 2.0));
        double vertDot = Math.cos(Math.toRadians(config.verticalFovDegrees() / 2.0));

        BitSet candidates = graph.candidateSolidCells();
        for (int idx = candidates.nextSetBit(0); idx >= 0; idx = candidates.nextSetBit(idx + 1)) {
            int lx = idx & 15;
            int lz = (idx >> 4) & 15;
            int ly = (idx >> 8) & 15;
            int wx = (snapshot.chunkX() << 4) + lx;
            int wy = (snapshot.sectionY() << 4) + ly;
            int wz = (snapshot.chunkZ() << 4) + lz;

            Vector to = new Vector(wx + 0.5, wy + 0.5, wz + 0.5).subtract(eye);
            double distSq = to.lengthSquared();
            if (distSq > maxDistSq) continue;

            Vector norm = to.clone().normalize();
            Vector flatLook = look.clone().setY(0).normalize();
            Vector flatNorm = norm.clone().setY(0);
            if (flatNorm.lengthSquared() > 0.0001) {
                flatNorm.normalize();
                if (flatLook.dot(flatNorm) < horizDot) continue;
            }
            if (look.clone().setX(0).setZ(0).dot(norm.clone().setX(0).setZ(0)) < vertDot) continue;
            revealed.set(idx);
        }
        return revealed;
    }

    public String inspect(Player player) {
        MovementTracker.TrackedState tracked = movementTracker.get(player);
        return "bucket(yaw=" + tracked.yawBucket() + ",pitch=" + tracked.pitchBucket() + ")";
    }
}
