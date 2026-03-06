package com.lastbreath.hc.sightculler.movement;

import com.lastbreath.hc.sightculler.config.SightCullerConfig;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MovementTracker implements Listener {
    private static final long JOIN_GRACE_PERIOD_MS = 3_000L;
    private volatile SightCullerConfig config;
    private final Map<UUID, TrackedState> tracked = new ConcurrentHashMap<>();
    private final Map<UUID, Long> joinTimes = new ConcurrentHashMap<>();

    public MovementTracker(SightCullerConfig config) {
        this.config = config;
    }

    public void reload(SightCullerConfig cfg) {
        this.config = cfg;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        tracked.put(playerId, TrackedState.from(event.getPlayer().getLocation()));
        joinTimes.put(playerId, System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        tracked.remove(playerId);
        joinTimes.remove(playerId);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        tracked.compute(event.getPlayer().getUniqueId(), (id, old) -> {
            TrackedState prev = old == null ? TrackedState.from(event.getFrom()) : old;
            return prev.update(event.getPlayer(), event.getTo(), config);
        });
    }

    public TrackedState get(Player player) {
        return tracked.computeIfAbsent(player.getUniqueId(), id -> TrackedState.from(player.getLocation()));
    }

    public boolean isInJoinGracePeriod(Player player) {
        Long joinedAt = joinTimes.get(player.getUniqueId());
        if (joinedAt == null) {
            return false;
        }
        return System.currentTimeMillis() - joinedAt < JOIN_GRACE_PERIOD_MS;
    }

    public record TrackedState(Location location, float yaw, float pitch, int yawBucket, int pitchBucket, boolean recomputeNeeded) {
        static TrackedState from(Location loc) {
            return new TrackedState(loc.clone(), loc.getYaw(), loc.getPitch(), bucketYaw(loc.getYaw()), bucketPitch(loc.getPitch()), true);
        }

        TrackedState update(Player player, Location now, SightCullerConfig cfg) {
            double moved = now.distanceSquared(location);
            double thresholdSq = cfg.movementRecomputeThreshold() * cfg.movementRecomputeThreshold();
            float yawNow = now.getYaw();
            float pitchNow = now.getPitch();
            boolean lookChanged = Math.abs(deltaAngle(yaw, yawNow)) >= cfg.lookRecomputeThresholdDegrees()
                    || Math.abs(pitch - pitchNow) >= cfg.lookRecomputeThresholdDegrees();
            boolean recompute = moved >= thresholdSq || lookChanged;
            return new TrackedState(now.clone(), yawNow, pitchNow, bucketYaw(yawNow), bucketPitch(pitchNow), recompute);
        }

        private static float deltaAngle(float a, float b) {
            float d = (b - a) % 360f;
            if (d > 180f) d -= 360f;
            if (d < -180f) d += 360f;
            return d;
        }

        private static int bucketYaw(float yaw) {
            return Math.floorMod((int) Math.floor((yaw + 180.0f) / 8.0f), 45);
        }

        private static int bucketPitch(float pitch) {
            return (int) Math.floor((Math.max(-90f, Math.min(90f, pitch)) + 90f) / 6.0f);
        }
    }
}
