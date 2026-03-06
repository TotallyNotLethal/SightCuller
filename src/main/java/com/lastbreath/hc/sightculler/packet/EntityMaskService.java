package com.lastbreath.hc.sightculler.packet;

import com.lastbreath.hc.sightculler.SightCullerPlugin;
import com.lastbreath.hc.sightculler.visibility.VisibilityEngine;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class EntityMaskService {
    private final SightCullerPlugin plugin;
    private final VisibilityEngine visibilityEngine;
    private BukkitTask task;

    public EntityMaskService(SightCullerPlugin plugin, VisibilityEngine visibilityEngine) {
        this.plugin = plugin;
        this.visibilityEngine = visibilityEngine;
    }

    public void start() {
        stop();
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 5L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            World world = viewer.getWorld();
            if (!visibilityEngine.isWorldEnabled(world)) {
                continue;
            }

            double range = plugin.cullerConfig().maxRevealDistance();
            for (Entity entity : viewer.getNearbyEntities(range, range, range)) {
                if (entity.equals(viewer)) {
                    continue;
                }

                boolean visible = visibilityEngine.isPointVisible(
                        viewer,
                        world,
                        entity.getLocation().getBlockX(),
                        entity.getLocation().getBlockY(),
                        entity.getLocation().getBlockZ()
                );

                if (visible) {
                    viewer.showEntity(plugin, entity);
                } else {
                    viewer.hideEntity(plugin, entity);
                }
            }
        }
    }
}
