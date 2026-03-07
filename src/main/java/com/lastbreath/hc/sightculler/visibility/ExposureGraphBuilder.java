package com.lastbreath.hc.sightculler.visibility;

import com.lastbreath.hc.sightculler.snapshot.ChunkSectionSnapshot;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Queue;

public final class ExposureGraphBuilder {
    private static final int[][] NEIGHBORS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    public ExposureGraph compute(ChunkSectionSnapshot section, int[] columnSurfaceY) {
        BitSet exposedAir = new BitSet(4096);
        Queue<Integer> bfs = new ArrayDeque<>();

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    boolean boundary = x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15;
                    if (!boundary) continue;
                    if (section.isAir(x, y, z)) {
                        int idx = ChunkSectionSnapshot.index(x, y, z);
                        if (!exposedAir.get(idx)) {
                            exposedAir.set(idx);
                            bfs.add(idx);
                        }
                    }
                }
            }
        }

        while (!bfs.isEmpty()) {
            int idx = bfs.poll();
            int x = idx & 15;
            int z = (idx >> 4) & 15;
            int y = (idx >> 8) & 15;

            for (int[] n : NEIGHBORS) {
                int nx = x + n[0], ny = y + n[1], nz = z + n[2];
                if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) continue;
                if (!section.isAir(nx, ny, nz)) continue;
                int nIdx = ChunkSectionSnapshot.index(nx, ny, nz);
                if (exposedAir.get(nIdx)) continue;
                exposedAir.set(nIdx);
                bfs.add(nIdx);
            }
        }

        BitSet candidateSolid = new BitSet(4096);
        for (int y = 0; y < 16; y++) {
            int globalY = (section.sectionY() << 4) + y;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int idx = ChunkSectionSnapshot.index(x, y, z);
                    if (section.isAir(x, y, z)) continue;
                    boolean adjacentToExposed = false;
                    for (int[] n : NEIGHBORS) {
                        int nx = x + n[0], ny = y + n[1], nz = z + n[2];
                        if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) continue;
                        if (exposedAir.get(ChunkSectionSnapshot.index(nx, ny, nz))) {
                            adjacentToExposed = true;
                            break;
                        }
                    }
                    int columnTopY = columnSurfaceY[(z << 4) | x];
                    boolean atOrAboveTopLayer = globalY >= columnTopY;
                    if (adjacentToExposed || atOrAboveTopLayer) {
                        candidateSolid.set(idx);
                    }
                }
            }
        }
        return new ExposureGraph(exposedAir, candidateSolid);
    }

    public record ExposureGraph(BitSet exposedAirCells, BitSet candidateSolidCells) {}
}
