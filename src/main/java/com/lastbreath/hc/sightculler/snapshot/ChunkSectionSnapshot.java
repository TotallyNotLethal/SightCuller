package com.lastbreath.hc.sightculler.snapshot;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

import java.util.BitSet;

public final class ChunkSectionSnapshot {
    private final String world;
    private final int chunkX;
    private final int chunkZ;
    private final int sectionY;
    private final Material[] blocks;
    private final BitSet exposureMediumCells;

    public ChunkSectionSnapshot(String world, int chunkX, int chunkZ, int sectionY, Material[] blocks, BitSet exposureMediumCells) {
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.sectionY = sectionY;
        this.blocks = blocks;
        this.exposureMediumCells = exposureMediumCells;
    }

    public static ChunkSectionSnapshot fromChunkSnapshot(String world, int chunkX, int chunkZ, int sectionY, ChunkSnapshot snapshot) {
        Material[] blocks = new Material[4096];
        BitSet exposureMediumCells = new BitSet(4096);
        int baseY = sectionY << 4;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int idx = index(x, y, z);
                    Material type = snapshot.getBlockType(x, baseY + y, z);
                    blocks[idx] = type;
                    if (isExposureMedium(type)) {
                        exposureMediumCells.set(idx);
                    }
                }
            }
        }
        return new ChunkSectionSnapshot(world, chunkX, chunkZ, sectionY, blocks, exposureMediumCells);
    }

    public String world() { return world; }
    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public int sectionY() { return sectionY; }

    public Material blockAt(int x, int y, int z) {
        return blocks[index(x, y, z)];
    }

    public boolean isAir(int x, int y, int z) {
        return exposureMediumCells.get(index(x, y, z));
    }

    private static boolean isExposureMedium(Material material) {
        if (material == Material.CAVE_AIR) {
            return false;
        }

        return material == Material.AIR || material == Material.WATER || material.isTransparent();
    }

    public static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }
}
