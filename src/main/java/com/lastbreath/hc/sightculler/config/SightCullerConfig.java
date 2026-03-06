package com.lastbreath.hc.sightculler.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.stream.Collectors;

public record SightCullerConfig(
        boolean enabled,
        boolean debug,
        Map<String, Boolean> worldEnabled,
        double horizontalFovDegrees,
        double verticalFovDegrees,
        double maxRevealDistance,
        double movementRecomputeThreshold,
        double lookRecomputeThresholdDegrees,
        long recentVisibilityRetentionMs,
        int hideSurfaceDepthBelowSurfaceOnly,
        Set<Material> hiddenMaterials,
        String naturalMaskMode,
        boolean lineOfSightEnforcement,
        boolean alertOnBlockProbe,
        boolean asyncPrecompute,
        int sectionSnapshotCacheSize,
        int playerVisibilityCacheSize,
        long metricsIntervalTicks
) {
    public static SightCullerConfig from(FileConfiguration cfg) {
        Set<Material> hidden = cfg.getStringList("hidden-materials").stream()
                .map(String::toUpperCase)
                .map(name -> {
                    try {
                        return Material.valueOf(name);
                    } catch (IllegalArgumentException ex) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Material.class)));

        Map<String, Boolean> worldEnabled = new HashMap<>();
        if (cfg.isConfigurationSection("per-world-enabled")) {
            for (String world : Objects.requireNonNull(cfg.getConfigurationSection("per-world-enabled")).getKeys(false)) {
                worldEnabled.put(world, cfg.getBoolean("per-world-enabled." + world, true));
            }
        }

        return new SightCullerConfig(
                cfg.getBoolean("enabled", true),
                cfg.getBoolean("debug", false),
                worldEnabled,
                cfg.getDouble("horizontal-fov-degrees", 100.0),
                cfg.getDouble("vertical-fov-degrees", 70.0),
                cfg.getDouble("max-reveal-distance", 56.0),
                cfg.getDouble("movement-recompute-threshold", 1.25),
                cfg.getDouble("look-recompute-threshold-degrees", 8.0),
                cfg.getLong("recent-visibility-retention-ms", 400L),
                cfg.getInt("hide-surface-depth-below-surface-only", 8),
                hidden,
                cfg.getString("natural-mask-mode", "NATURAL_STONE"),
                cfg.getBoolean("line-of-sight-enforcement", true),
                cfg.getBoolean("alert-on-block-probe", true),
                cfg.getBoolean("async-precompute", true),
                cfg.getInt("cache.section-snapshot-size", 4096),
                cfg.getInt("cache.player-visibility-size", 32768),
                cfg.getLong("metrics-interval-ticks", 200L)
        );
    }

    public boolean worldEnabled(String worldName) {
        return worldEnabled.getOrDefault(worldName, true);
    }
}
