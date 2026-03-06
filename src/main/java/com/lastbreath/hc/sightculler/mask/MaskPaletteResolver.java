package com.lastbreath.hc.sightculler.mask;

import com.lastbreath.hc.sightculler.config.SightCullerConfig;
import org.bukkit.Material;
import org.bukkit.World;

public final class MaskPaletteResolver {
    private volatile SightCullerConfig config;

    public MaskPaletteResolver(SightCullerConfig config) {
        this.config = config;
    }

    public void reload(SightCullerConfig config) {
        this.config = config;
    }

    public Material maskFor(World.Environment environment, int y) {
        if ("CAVE_AIR".equalsIgnoreCase(config.naturalMaskMode())) {
            return Material.CAVE_AIR;
        }
        return switch (environment) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> y < 0 ? Material.DEEPSLATE : Material.STONE;
        };
    }
}
