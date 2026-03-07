package com.lastbreath.hc.sightculler.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.lastbreath.hc.sightculler.SightCullerPlugin;
import com.lastbreath.hc.sightculler.metrics.MetricsService;
import com.lastbreath.hc.sightculler.visibility.SectionDirtyTracker;
import com.lastbreath.hc.sightculler.visibility.VisibilityEngine;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Array;
import java.util.BitSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PacketMaskService {
    private static final int MAX_MULTI_BLOCK_CHANGES_PER_PACKET = 256;
    private static final int INITIAL_MASK_SECTIONS_PER_TICK = 2;
    private static final int INITIAL_MASK_UPDATES_PER_TICK = 1024;
    private static final int DYNAMIC_MAX_UPDATES_PER_TICK = 1024;

    private final SightCullerPlugin plugin;
    private final VisibilityEngine visibilityEngine;
    private final MetricsService metrics;
    private final SectionDirtyTracker dirtyTracker;
    private final ProtocolManager protocolManager;
    private final Map<UUID, Set<MaskedBlockKey>> playerMaskedBlocks = new HashMap<>();
    private final Map<UUID, PlayerPose> playerPoseCache = new HashMap<>();
    private PacketAdapter adapter;
    private BukkitTask dynamicVisibilityTask;

    public PacketMaskService(SightCullerPlugin plugin, VisibilityEngine visibilityEngine, MetricsService metrics, SectionDirtyTracker dirtyTracker) {
        this.plugin = plugin;
        this.visibilityEngine = visibilityEngine;
        this.metrics = metrics;
        this.dirtyTracker = dirtyTracker;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    public void start() {
        this.adapter = new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.BLOCK_CHANGE,
                PacketType.Play.Server.MULTI_BLOCK_CHANGE,
                PacketType.Play.Server.MAP_CHUNK,
                PacketType.Play.Server.TILE_ENTITY_DATA
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (!(event.getPlayer() instanceof Player player)) return;
                if (!visibilityEngine.isWorldEnabled(player.getWorld())) return;
                switch (event.getPacketType().name()) {
                    case "BLOCK_CHANGE" -> handleBlockChange(event, player);
                    case "MULTI_BLOCK_CHANGE" -> handleMultiBlockChange(event, player);
                    case "TILE_ENTITY_DATA" -> handleTileEntity(event, player);
                    case "MAP_CHUNK" -> handleMapChunk(event, player);
                    default -> {
                    }
                }
            }
        };
        protocolManager.addPacketListener(adapter);
        dynamicVisibilityTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickDynamicVisibility, 1L, 1L);
    }

    public void stop() {
        if (adapter != null) {
            protocolManager.removePacketListener(adapter);
            adapter = null;
        }
        if (dynamicVisibilityTask != null) {
            dynamicVisibilityTask.cancel();
            dynamicVisibilityTask = null;
        }
        playerMaskedBlocks.clear();
        playerPoseCache.clear();
    }

    private void handleBlockChange(PacketEvent event, Player player) {
        BlockPosition pos = event.getPacket().getBlockPositionModifier().read(0);
        WrappedBlockData data = event.getPacket().getBlockData().read(0);
        Material type = data.getType();
        World world = player.getWorld();
        if (!visibilityEngine.shouldReveal(player, world, pos.getX(), pos.getY(), pos.getZ(), type)) {
            event.getPacket().getBlockData().write(0, WrappedBlockData.createData(visibilityEngine.maskMaterial(world, pos.getY())));
            metrics.recordMasked();
        } else {
            metrics.recordRevealed();
        }
    }

    private void handleMultiBlockChange(PacketEvent event, Player player) {
        try {
            Object sectionPos = event.getPacket().getModifier().read(0);
            short[] offsets = (short[]) event.getPacket().getModifier().read(1);
            Object dataArray = event.getPacket().getModifier().read(2);
            if (offsets == null || dataArray == null || !dataArray.getClass().isArray()) {
                return;
            }

            int sectionX = (int) sectionPos.getClass().getMethod("getX").invoke(sectionPos);
            int sectionY = (int) sectionPos.getClass().getMethod("getY").invoke(sectionPos);
            int sectionZ = (int) sectionPos.getClass().getMethod("getZ").invoke(sectionPos);
            int baseX = sectionX << 4;
            int baseY = sectionY << 4;
            int baseZ = sectionZ << 4;

            for (int i = 0; i < offsets.length; i++) {
                short packed = offsets[i];
                int x = baseX + (packed & 15);
                int y = baseY + ((packed >> 8) & 15);
                int z = baseZ + ((packed >> 4) & 15);
                Object wrapped = Array.get(dataArray, i);
                if (!(wrapped instanceof WrappedBlockData blockData)) continue;
                Material type = blockData.getType();
                if (!visibilityEngine.shouldReveal(player, player.getWorld(), x, y, z, type)) {
                    Array.set(dataArray, i, WrappedBlockData.createData(visibilityEngine.maskMaterial(player.getWorld(), y)));
                    metrics.recordMasked();
                } else {
                    metrics.recordRevealed();
                }
            }
            event.getPacket().getModifier().write(2, dataArray);
        } catch (Throwable ignored) {
            metrics.recordPacketFallback();
        }
    }

    private void handleTileEntity(PacketEvent event, Player player) {
        try {
            BlockPosition pos = event.getPacket().getBlockPositionModifier().read(0);
            Material real = player.getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ()).getType();
            if (!visibilityEngine.shouldReveal(player, player.getWorld(), pos.getX(), pos.getY(), pos.getZ(), real)) {
                event.setCancelled(true);
                metrics.recordSuppressedTile();
            }
        } catch (Exception ignored) {
            metrics.recordPacketFallback();
        }
    }

    private void handleMapChunk(PacketEvent event, Player player) {
        try {
            int chunkX = event.getPacket().getIntegers().read(0);
            int chunkZ = event.getPacket().getIntegers().read(1);
            int minSection = player.getWorld().getMinHeight() >> 4;
            int maxSection = (player.getWorld().getMaxHeight() - 1) >> 4;
            for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
                dirtyTracker.mark(player.getWorld().getBlockAt((chunkX << 4), sectionY << 4, (chunkZ << 4)));
            }

            plugin.getServer().getScheduler().runTask(plugin, new InitialChunkMaskTask(player, chunkX, chunkZ));
        } catch (Exception ignored) {
            metrics.recordPacketFallback();
        }
    }

    private final class InitialChunkMaskTask implements Runnable {
        private final Player player;
        private final int chunkX;
        private final int chunkZ;
        private final long startedAtNanos;
        private int sectionY;
        private int maxSection;
        private boolean initialized;
        private ChunkSnapshot snapshot;
        private World world;
        private int[] columnTopY;
        private int sentUpdates;
        private Set<MaskedBlockKey> trackedMasked;

        private InitialChunkMaskTask(Player player, int chunkX, int chunkZ) {
            this.player = player;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.startedAtNanos = System.nanoTime();
        }

        @Override
        public void run() {
            if (!initializeIfNeeded()) {
                return;
            }

            int sectionsProcessed = 0;
            int updatesProcessed = 0;
            while (sectionY <= maxSection && sectionsProcessed < INITIAL_MASK_SECTIONS_PER_TICK && updatesProcessed < INITIAL_MASK_UPDATES_PER_TICK) {
                int sectionUpdateCount = processSection(sectionY);
                updatesProcessed += sectionUpdateCount;
                sectionsProcessed++;
                sectionY++;
            }

            if (sectionY <= maxSection) {
                plugin.getServer().getScheduler().runTaskLater(plugin, this, 1L);
                return;
            }

            metrics.recordInitialChunkMask(System.nanoTime() - startedAtNanos, sentUpdates);
        }

        private boolean initializeIfNeeded() {
            if (initialized) {
                return true;
            }
            if (!player.isOnline() || !visibilityEngine.isWorldEnabled(player.getWorld())) {
                return false;
            }

            world = player.getWorld();
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                return false;
            }

            snapshot = world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(true, false, false);
            sectionY = world.getMinHeight() >> 4;
            maxSection = (world.getMaxHeight() - 1) >> 4;
            columnTopY = visibilityEngine.computeTopLayerByColumn(world, chunkX, chunkZ);
            trackedMasked = playerMaskedBlocks.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>());
            initialized = true;
            return true;
        }

        private int processSection(int sectionY) {
            if (!sectionContainsHiddenMaterial(sectionY)) {
                return 0;
            }

            BitSet revealedSolidCells = visibilityEngine.compute(player, world, chunkX, chunkZ, sectionY, columnTopY).revealedSolidCells();
            int baseX = chunkX << 4;
            int baseY = sectionY << 4;
            int baseZ = chunkZ << 4;
            PackedPositionBuffer maskedPositions = new PackedPositionBuffer(MAX_MULTI_BLOCK_CHANGES_PER_PACKET);
            int maskedInSection = 0;

            for (int ly = 0; ly < 16; ly++) {
                int y = baseY + ly;
                for (int lz = 0; lz < 16; lz++) {
                    int z = baseZ + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        Material type = snapshot.getBlockType(lx, y, lz);
                        if (!visibilityEngine.shouldMaskMaterialType(type)) {
                            continue;
                        }

                        int localIndex = (ly << 8) | (lz << 4) | lx;
                        if (revealedSolidCells.get(localIndex)) {
                            continue;
                        }

                        maskedPositions.add(lx, ly, lz);
                        trackedMasked.add(new MaskedBlockKey(world.getUID(), baseX + lx, y, z));
                        maskedInSection++;

                        if (maskedPositions.size() >= MAX_MULTI_BLOCK_CHANGES_PER_PACKET) {
                            sendMaskedBatch(player, world, baseX, baseY, baseZ, maskedPositions);
                            sentUpdates += maskedPositions.size();
                            maskedPositions.clear();
                        }
                    }
                }
            }

            if (!maskedPositions.isEmpty()) {
                sendMaskedBatch(player, world, baseX, baseY, baseZ, maskedPositions);
                sentUpdates += maskedPositions.size();
            }
            return maskedInSection;
        }

        private boolean sectionContainsHiddenMaterial(int sectionY) {
            int baseY = sectionY << 4;
            for (int ly = 0; ly < 16; ly++) {
                int y = baseY + ly;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        if (visibilityEngine.shouldMaskMaterialType(snapshot.getBlockType(lx, y, lz))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    private void sendMaskedBatch(Player player, World world, int baseX, int baseY, int baseZ, PackedPositionBuffer packedPositions) {
        Map<Location, BlockData> updates = new HashMap<>(packedPositions.size());
        for (int i = 0; i < packedPositions.size(); i++) {
            int packed = packedPositions.get(i);
            int lx = packed & 15;
            int ly = (packed >> 8) & 15;
            int lz = (packed >> 4) & 15;
            int y = baseY + ly;
            updates.put(new Location(world, baseX + lx, y, baseZ + lz), visibilityEngine.maskMaterial(world, y).createBlockData());
        }

        player.sendMultiBlockChange(updates);
        for (int i = 0; i < updates.size(); i++) {
            metrics.recordMasked();
        }
    }

    private void tickDynamicVisibility() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!visibilityEngine.isWorldEnabled(player.getWorld())) {
                continue;
            }

            PlayerPose nowPose = PlayerPose.capture(player);
            PlayerPose previousPose = playerPoseCache.put(player.getUniqueId(), nowPose);
            if (previousPose != null && previousPose.sameBucketsAndBlock(nowPose)) {
                continue;
            }

            reconcilePlayerVisibility(player);
        }
    }

    private void reconcilePlayerVisibility(Player player) {
        World world = player.getWorld();
        Set<MaskedBlockKey> masked = playerMaskedBlocks.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>());
        Map<Location, BlockData> updates = new HashMap<>();
        int updateBudget = DYNAMIC_MAX_UPDATES_PER_TICK;
        int chunkRadius = Math.max(1, (int) Math.ceil(visibilityEngine.maxRevealDistance() / 16.0));

        int centerChunkX = player.getLocation().getBlockX() >> 4;
        int centerChunkZ = player.getLocation().getBlockZ() >> 4;
        int minSection = world.getMinHeight() >> 4;
        int maxSection = (world.getMaxHeight() - 1) >> 4;

        for (int dx = -chunkRadius; dx <= chunkRadius && updateBudget > 0; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius && updateBudget > 0; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }

                ChunkSnapshot chunkSnapshot = world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(true, false, false);
                int[] columnTopY = visibilityEngine.computeTopLayerByColumn(world, chunkX, chunkZ);

                for (int sectionY = minSection; sectionY <= maxSection && updateBudget > 0; sectionY++) {
                    BitSet revealed = visibilityEngine.compute(player, world, chunkX, chunkZ, sectionY, columnTopY).revealedSolidCells();
                    int baseY = sectionY << 4;

                    for (int ly = 0; ly < 16 && updateBudget > 0; ly++) {
                        int y = baseY + ly;
                        for (int lz = 0; lz < 16 && updateBudget > 0; lz++) {
                            for (int lx = 0; lx < 16 && updateBudget > 0; lx++) {
                                Material type = chunkSnapshot.getBlockType(lx, y, lz);
                                if (!visibilityEngine.shouldMaskMaterialType(type)) {
                                    continue;
                                }

                                int worldX = (chunkX << 4) + lx;
                                int worldZ = (chunkZ << 4) + lz;
                                if (!visibilityEngine.isWithinPlayerServerView(player, world, worldX, y, worldZ)) {
                                    continue;
                                }

                                int topY = columnTopY[(lz << 4) | lx];
                                if (y >= topY) {
                                    continue;
                                }

                                int localIndex = (ly << 8) | (lz << 4) | lx;
                                boolean inView = revealed.get(localIndex);
                                MaskedBlockKey key = new MaskedBlockKey(world.getUID(), worldX, y, worldZ);

                                if (inView) {
                                    if (masked.remove(key)) {
                                        updates.put(new Location(world, worldX, y, worldZ), world.getBlockAt(worldX, y, worldZ).getBlockData());
                                        metrics.recordRevealed();
                                        updateBudget--;
                                    }
                                    continue;
                                }

                                if (masked.add(key)) {
                                    updates.put(new Location(world, worldX, y, worldZ), visibilityEngine.maskMaterial(world, y).createBlockData());
                                    metrics.recordMasked();
                                    updateBudget--;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!updates.isEmpty()) {
            player.sendMultiBlockChange(updates);
        }
    }

    private record MaskedBlockKey(UUID worldId, int x, int y, int z) {
    }

    private record PlayerPose(UUID worldId, int blockX, int blockY, int blockZ, int yawBucket, int pitchBucket) {
        private static PlayerPose capture(Player player) {
            Location location = player.getLocation();
            int yawBucket = Math.floorMod((int) Math.floor((location.getYaw() + 180.0f) / 8.0f), 45);
            int pitchBucket = (int) Math.floor((location.getPitch() + 90.0f) / 8.0f);
            return new PlayerPose(player.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), yawBucket, pitchBucket);
        }

        private boolean sameBucketsAndBlock(PlayerPose other) {
            return worldId.equals(other.worldId)
                    && blockX == other.blockX
                    && blockY == other.blockY
                    && blockZ == other.blockZ
                    && yawBucket == other.yawBucket
                    && pitchBucket == other.pitchBucket;
        }
    }

    private static final class PackedPositionBuffer {
        private int[] elements;
        private int size;

        private PackedPositionBuffer(int initialCapacity) {
            this.elements = new int[Math.max(16, initialCapacity)];
        }

        private void add(int lx, int ly, int lz) {
            if (size >= elements.length) {
                int[] expanded = new int[elements.length << 1];
                System.arraycopy(elements, 0, expanded, 0, elements.length);
                elements = expanded;
            }
            elements[size++] = (ly << 8) | (lz << 4) | lx;
        }

        private int get(int idx) {
            return elements[idx];
        }

        private int size() {
            return size;
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private void clear() {
            size = 0;
        }
    }
}
