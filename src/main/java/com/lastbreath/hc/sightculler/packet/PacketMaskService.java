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
import com.lastbreath.hc.sightculler.config.SightCullerConfig;
import com.lastbreath.hc.sightculler.metrics.MetricsService;
import com.lastbreath.hc.sightculler.visibility.SectionDirtyTracker;
import com.lastbreath.hc.sightculler.visibility.VisibilityEngine;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Array;

public final class PacketMaskService {
    private final SightCullerPlugin plugin;
    private final SightCullerConfig config;
    private final VisibilityEngine visibilityEngine;
    private final MetricsService metrics;
    private final SectionDirtyTracker dirtyTracker;
    private final ProtocolManager protocolManager;
    private PacketAdapter adapter;

    public PacketMaskService(SightCullerPlugin plugin, SightCullerConfig config, VisibilityEngine visibilityEngine, MetricsService metrics, SectionDirtyTracker dirtyTracker) {
        this.plugin = plugin;
        this.config = config;
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
                    case "MAP_CHUNK" -> markChunkSectionsDirty(event, player);
                    default -> { }
                }
            }
        };
        protocolManager.addPacketListener(adapter);
    }

    public void stop() {
        if (adapter != null) {
            protocolManager.removePacketListener(adapter);
            adapter = null;
        }
    }

    private void handleBlockChange(PacketEvent event, Player player) {
        BlockPosition pos = event.getPacket().getBlockPositionModifier().read(0);
        WrappedBlockData data = event.getPacket().getBlockData().read(0);
        Material type = data.getType();
        World world = player.getWorld();
        if (!visibilityEngine.shouldReveal(player, world, pos.getX(), pos.getY(), pos.getZ(), type)) {
            Material mask = visibilityEngine.maskMaterial(world, pos.getY());
            event.getPacket().getBlockData().write(0, WrappedBlockData.createData(mask));
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

    private void markChunkSectionsDirty(PacketEvent event, Player player) {
        try {
            int chunkX = event.getPacket().getIntegers().read(0);
            int chunkZ = event.getPacket().getIntegers().read(1);
            int minSection = player.getWorld().getMinHeight() >> 4;
            int maxSection = (player.getWorld().getMaxHeight() >> 4);
            for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
                dirtyTracker.mark(player.getWorld().getBlockAt((chunkX << 4), sectionY << 4, (chunkZ << 4)));
            }
        } catch (Exception ignored) {
            metrics.recordPacketFallback();
        }
    }
}
