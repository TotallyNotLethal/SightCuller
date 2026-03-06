package com.lastbreath.hc.sightculler.visibility;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SectionDirtyTracker implements Listener {
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) { mark(event.getBlock().getLocation()); }
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) { mark(event.getBlock().getLocation()); }
    @EventHandler(ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) { mark(event.getBlock().getLocation()); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        mark(event.getBlock().getLocation());
        event.blockList().forEach(b -> mark(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(b -> mark(b.getLocation()));
    }

    public void mark(Location loc) {
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        int secY = loc.getBlockY() >> 4;
        dirty.add(key(loc.getWorld().getName(), chunkX, chunkZ, secY));
    }

    public void mark(Block block) {
        mark(block.getLocation());
    }

    public boolean consumeDirty(String world, int chunkX, int chunkZ, int sectionY) {
        return dirty.remove(key(world, chunkX, chunkZ, sectionY));
    }

    private static String key(String world, int chunkX, int chunkZ, int sectionY) {
        return world + ':' + chunkX + ':' + chunkZ + ':' + sectionY;
    }
}
