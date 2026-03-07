package com.lastbreath.hc.sightculler.metrics;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.atomic.AtomicLong;

public final class MetricsService {
    private final JavaPlugin plugin;
    private final long intervalTicks;
    private final AtomicLong masked = new AtomicLong();
    private final AtomicLong revealed = new AtomicLong();
    private final AtomicLong denied = new AtomicLong();
    private final AtomicLong suppressedTile = new AtomicLong();
    private final AtomicLong packetFallback = new AtomicLong();
    private final AtomicLong initialChunkMaskRuns = new AtomicLong();
    private final AtomicLong initialChunkMaskTotalNanos = new AtomicLong();
    private final AtomicLong initialChunkMaskTotalUpdates = new AtomicLong();
    private BukkitTask task;

    public MetricsService(JavaPlugin plugin, long intervalTicks) {
        this.plugin = plugin;
        this.intervalTicks = intervalTicks;
    }

    public void start() {
        stop();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> plugin.getLogger().info(summary()), intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void recordMasked() { masked.incrementAndGet(); }
    public void recordRevealed() { revealed.incrementAndGet(); }
    public void recordProbeDenied() { denied.incrementAndGet(); }
    public void recordSuppressedTile() { suppressedTile.incrementAndGet(); }
    public void recordPacketFallback() { packetFallback.incrementAndGet(); }

    public void recordInitialChunkMask(long durationNanos, int updatesSent) {
        initialChunkMaskRuns.incrementAndGet();
        initialChunkMaskTotalNanos.addAndGet(Math.max(0L, durationNanos));
        initialChunkMaskTotalUpdates.addAndGet(Math.max(0, updatesSent));
    }

    public String summary() {
        long runs = initialChunkMaskRuns.get();
        long avgMs = runs == 0 ? 0L : (initialChunkMaskTotalNanos.get() / runs) / 1_000_000L;
        long avgUpdates = runs == 0 ? 0L : initialChunkMaskTotalUpdates.get() / runs;
        return "SightCuller metrics: masked=" + masked.get() + ", revealed=" + revealed.get() + ", denied=" + denied.get()
                + ", tileSuppressed=" + suppressedTile.get() + ", packetFallback=" + packetFallback.get()
                + ", initialChunkMaskRuns=" + runs + ", initialChunkMaskAvgMs=" + avgMs + ", initialChunkMaskAvgUpdates=" + avgUpdates;
    }
}
