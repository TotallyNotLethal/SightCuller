package com.lastbreath.hc.sightculler.cache;

import com.lastbreath.hc.sightculler.config.SightCullerConfig;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerVisibilityCache {
    private final Map<VisibilityKey, VisibilityValue> cache;
    private int maxEntries;

    public PlayerVisibilityCache(SightCullerConfig config) {
        this.maxEntries = config.playerVisibilityCacheSize();
        this.cache = new LinkedHashMap<>(2048, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<VisibilityKey, VisibilityValue> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public synchronized VisibilityValue get(VisibilityKey key) {
        return cache.get(key);
    }

    public synchronized void put(VisibilityKey key, VisibilityValue value) {
        cache.put(key, value);
    }

    public synchronized void invalidatePlayer(UUID playerId) {
        cache.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    public synchronized void invalidateSection(String world, int chunkX, int chunkZ, int sectionY) {
        cache.keySet().removeIf(key -> key.world().equals(world) && key.chunkX() == chunkX && key.chunkZ() == chunkZ && key.sectionY() == sectionY);
    }

    public synchronized int size() {
        return cache.size();
    }

    public void reload(SightCullerConfig config) {
        this.maxEntries = config.playerVisibilityCacheSize();
    }

    public record VisibilityKey(UUID playerId, String world, int chunkX, int chunkZ, int sectionY, int yawBucket, int pitchBucket) {}

    public record VisibilityValue(BitSet revealedSolidCells, BitSet candidateAirCells, long computedAtMs, String reason) {}
}
