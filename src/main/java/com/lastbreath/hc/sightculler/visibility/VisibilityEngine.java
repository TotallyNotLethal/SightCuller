package com.lastbreath.hc.sightculler.visibility;

import com.lastbreath.hc.sightculler.SightCullerPlugin;
import com.lastbreath.hc.sightculler.cache.PlayerVisibilityCache;
import com.lastbreath.hc.sightculler.config.SightCullerConfig;
import com.lastbreath.hc.sightculler.mask.MaskPaletteResolver;
import com.lastbreath.hc.sightculler.movement.MovementTracker;
import com.lastbreath.hc.sightculler.snapshot.ChunkSectionSnapshot;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.BitSet;

public final class VisibilityEngine {
    private static final double RAY_EPSILON = 1.0e-4;

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
        if (originalMaterial.isAir()) return true;
        if (!shouldMaskMaterial(originalMaterial)) return true;
        if (movementTracker.isInJoinGracePeriod(player)) return true;
        if (!movementTracker.hasServerView(player)) return true;

        int topLayerY = world.getHighestBlockYAt(x, z);
        boolean belowSurfaceMaskDepth = isWithinBelowSurfaceMaskDepth(y, topLayerY);
        if (belowSurfaceMaskDepth) {
            if (config.lineOfSightEnforcement() && isPointVisible(player, world, x, y, z)) {
                return true;
            }
            return false;
        }

        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int sectionY = y >> 4;
        PlayerVisibilityCache.VisibilityValue value = compute(player, world, chunkX, chunkZ, sectionY);
        int local = ChunkSectionSnapshot.index(x & 15, y & 15, z & 15);
        return value.revealedSolidCells().get(local);
    }

    public double maxRevealDistance() {
        return config.maxRevealDistance();
    }

    public boolean shouldMaskMaterialType(Material material) {
        return shouldMaskMaterial(material);
    }

    public Material maskMaterial(World world, int y) {
        return maskPaletteResolver.maskFor(world.getEnvironment(), y);
    }

    public boolean isPointVisible(Player player, World world, int x, int y, int z) {
        if (!isWorldEnabled(world)) {
            return true;
        }
        if (!movementTracker.hasServerView(player)) {
            return true;
        }
        return isPointWithinServerFov(player, x, y, z) && isPointWithinServerRaycast(player, world, x, y, z);
    }

    private boolean shouldMaskMaterial(Material material) {
        return config.hiddenMaterials().contains(material);
    }

    private boolean isWithinBelowSurfaceMaskDepth(int y, int topLayerY) {
        return y < topLayerY;
    }

    public PlayerVisibilityCache.VisibilityValue compute(Player player, World world, int chunkX, int chunkZ, int sectionY) {
        int[] columnSurfaceY = computeTopLayerByColumn(world, chunkX, chunkZ);
        return compute(player, world, chunkX, chunkZ, sectionY, columnSurfaceY);
    }

    public PlayerVisibilityCache.VisibilityValue compute(Player player, World world, int chunkX, int chunkZ, int sectionY, int[] columnSurfaceY) {
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

        ExposureGraphBuilder.ExposureGraph graph = exposureGraphBuilder.compute(snapshot, columnSurfaceY);
        BitSet revealed = gateByPlayerView(player, snapshot, graph, columnSurfaceY);
        PlayerVisibilityCache.VisibilityValue value = new PlayerVisibilityCache.VisibilityValue(revealed, graph.exposedAirCells(), System.currentTimeMillis(), "computed");
        cache.put(key, value);
        return value;
    }

    private BitSet gateByPlayerView(Player player, ChunkSectionSnapshot snapshot, ExposureGraphBuilder.ExposureGraph graph, int[] columnSurfaceY) {
        BitSet revealed = new BitSet(4096);
        boolean enforceLos = config.lineOfSightEnforcement();

        if (!movementTracker.hasServerView(player)) {
            revealed.or(graph.candidateSolidCells());
            return revealed;
        }

        BitSet candidates = graph.candidateSolidCells();
        for (int idx = candidates.nextSetBit(0); idx >= 0; idx = candidates.nextSetBit(idx + 1)) {
            int lx = idx & 15;
            int lz = (idx >> 4) & 15;
            int ly = (idx >> 8) & 15;
            int wx = (snapshot.chunkX() << 4) + lx;
            int wy = (snapshot.sectionY() << 4) + ly;
            int wz = (snapshot.chunkZ() << 4) + lz;

            if (!isWithinPlayerServerView(player, player.getWorld(), wx, wy, wz)) {
                continue;
            }

            int columnTopY = columnSurfaceY[(lz << 4) | lx];
            if (isWithinBelowSurfaceMaskDepth(wy, columnTopY)) {
                if (enforceLos && isPointVisible(player, player.getWorld(), wx, wy, wz)) {
                    revealed.set(idx);
                }
                continue;
            }

            if (!enforceLos || isPointVisible(player, player.getWorld(), wx, wy, wz)) {
                revealed.set(idx);
            }
        }

        if (enforceLos) {
            for (int idx = 0; idx < 4096; idx++) {
                if (revealed.get(idx)) {
                    continue;
                }
                int lx = idx & 15;
                int lz = (idx >> 4) & 15;
                int ly = (idx >> 8) & 15;
                if (snapshot.isAir(lx, ly, lz)) {
                    continue;
                }

                int wy = (snapshot.sectionY() << 4) + ly;
                int columnTopY = columnSurfaceY[(lz << 4) | lx];
                if (!isWithinBelowSurfaceMaskDepth(wy, columnTopY)) {
                    continue;
                }

                int wx = (snapshot.chunkX() << 4) + lx;
                int wz = (snapshot.chunkZ() << 4) + lz;
                if (!isWithinPlayerServerView(player, player.getWorld(), wx, wy, wz)) {
                    continue;
                }
                if (isPointVisible(player, player.getWorld(), wx, wy, wz)) {
                    revealed.set(idx);
                }
            }
        }

        return revealed;
    }

    public boolean isWithinPlayerServerView(Player player, World world, int x, int y, int z) {
        return isPointWithinServerFov(player, x, y, z) && isPointWithinServerRaycast(player, world, x, y, z);
    }

    private boolean isPointWithinServerFov(Player player, int x, int y, int z) {
        Location eyeLocation = player.getEyeLocation();
        Vector eye = eyeLocation.toVector();
        Vector look = eyeLocation.getDirection();
        if (look.lengthSquared() <= 0.0001) {
            return false;
        }
        look.normalize();

        Vector target = new Vector(x + 0.5, y + 0.5, z + 0.5);
        Vector toTarget = target.clone().subtract(eye);
        double targetDistance = toTarget.length();
        if (targetDistance <= RAY_EPSILON) {
            return true;
        }

        Vector norm = toTarget.clone().normalize();
        double horizDot = Math.cos(Math.toRadians(config.horizontalFovDegrees() / 2.0));
        double vertDot = Math.cos(Math.toRadians(config.verticalFovDegrees() / 2.0));

        Vector flatLook = look.clone().setY(0);
        if (flatLook.lengthSquared() > 0.0001) {
            flatLook.normalize();
            Vector flatNorm = norm.clone().setY(0);
            if (flatNorm.lengthSquared() > 0.0001) {
                flatNorm.normalize();
                if (flatLook.dot(flatNorm) < horizDot) {
                    return false;
                }
            }
        }

        return look.clone().setX(0).setZ(0).dot(norm.clone().setX(0).setZ(0)) >= vertDot;
    }

    private boolean isPointWithinServerRaycast(Player player, World world, int x, int y, int z) {
        Location eyeLocation = player.getEyeLocation();
        Vector eye = eyeLocation.toVector();
        Vector target = new Vector(x + 0.5, y + 0.5, z + 0.5);
        Vector toTarget = target.clone().subtract(eye);

        double distSq = toTarget.lengthSquared();
        double maxDistSq = config.maxRevealDistance() * config.maxRevealDistance();
        if (distSq > maxDistSq) {
            return false;
        }

        double targetDistance = toTarget.length();
        if (targetDistance <= RAY_EPSILON) {
            return true;
        }

        Vector norm = toTarget.clone().normalize();
        RayTraceResult hit = world.rayTraceBlocks(
                eyeLocation,
                norm,
                targetDistance + RAY_EPSILON,
                FluidCollisionMode.NEVER,
                true
        );

        if (hit == null || hit.getHitBlock() == null) {
            return true;
        }

        if (hit.getHitBlock().getX() == x && hit.getHitBlock().getY() == y && hit.getHitBlock().getZ() == z) {
            return true;
        }

        Vector hitPosition = hit.getHitPosition();
        return hitPosition != null && hitPosition.distance(eye) + RAY_EPSILON >= targetDistance;
    }

    public int[] computeTopLayerByColumn(World world, int chunkX, int chunkZ) {
        int[] columnSurfaceY = new int[256];
        int minY = world.getMinHeight();
        for (int lz = 0; lz < 16; lz++) {
            int z = (chunkZ << 4) + lz;
            for (int lx = 0; lx < 16; lx++) {
                int x = (chunkX << 4) + lx;
                int highestY = world.getHighestBlockYAt(x, z);
                columnSurfaceY[(lz << 4) | lx] = Math.max(minY, highestY);
            }
        }
        return columnSurfaceY;
    }

    public String inspect(Player player) {
        MovementTracker.TrackedState tracked = movementTracker.get(player);
        return "bucket(yaw=" + tracked.yawBucket() + ",pitch=" + tracked.pitchBucket() + ")";
    }
}
