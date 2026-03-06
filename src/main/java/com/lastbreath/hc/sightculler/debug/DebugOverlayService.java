package com.lastbreath.hc.sightculler.debug;

import com.lastbreath.hc.sightculler.config.SightCullerConfig;
import com.lastbreath.hc.sightculler.cache.PlayerVisibilityCache;
import com.lastbreath.hc.sightculler.movement.MovementTracker;
import com.lastbreath.hc.sightculler.visibility.VisibilityEngine;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class DebugOverlayService {
    private final JavaPlugin plugin;
    private volatile SightCullerConfig config;
    private final VisibilityEngine visibility;
    private final PlayerVisibilityCache cache;
    private final MovementTracker movement;
    private volatile boolean debugForced;
    private BukkitTask task;

    public DebugOverlayService(JavaPlugin plugin, SightCullerConfig config, VisibilityEngine visibility, PlayerVisibilityCache cache, MovementTracker movement) {
        this.plugin = plugin;
        this.config = config;
        this.visibility = visibility;
        this.cache = cache;
        this.movement = movement;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!config.debug() && !debugForced) return;
            for (Player player : Bukkit.getOnlinePlayers()) {
                var state = movement.get(player);
                player.sendActionBar("§7SC debug §f" + state.yawBucket() + "/" + state.pitchBucket() + " cache=" + cache.size());
            }
        }, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void setDebugForced(boolean debugForced) {
        this.debugForced = debugForced;
    }

    public boolean isDebugEnabled() {
        return debugForced || config.debug();
    }

    public String inspect(Player player) {
        return visibility.inspect(player) + ", cacheSize=" + cache.size();
    }

    public void reload(SightCullerConfig config) {
        this.config = config;
    }
}
