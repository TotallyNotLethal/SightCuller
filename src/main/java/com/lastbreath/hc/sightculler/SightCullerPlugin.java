package com.lastbreath.hc.sightculler;

import com.lastbreath.hc.sightculler.cache.PlayerVisibilityCache;
import com.lastbreath.hc.sightculler.command.SightCullerCommand;
import com.lastbreath.hc.sightculler.config.SightCullerConfig;
import com.lastbreath.hc.sightculler.debug.DebugOverlayService;
import com.lastbreath.hc.sightculler.interaction.InteractionGuardListener;
import com.lastbreath.hc.sightculler.mask.MaskPaletteResolver;
import com.lastbreath.hc.sightculler.metrics.MetricsService;
import com.lastbreath.hc.sightculler.movement.MovementTracker;
import com.lastbreath.hc.sightculler.packet.EntityMaskService;
import com.lastbreath.hc.sightculler.packet.PacketMaskService;
import com.lastbreath.hc.sightculler.visibility.ExposureGraphBuilder;
import com.lastbreath.hc.sightculler.visibility.SectionDirtyTracker;
import com.lastbreath.hc.sightculler.visibility.VisibilityEngine;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class SightCullerPlugin extends JavaPlugin {
    private SightCullerConfig cullerConfig;
    private MetricsService metricsService;
    private SectionDirtyTracker sectionDirtyTracker;
    private MovementTracker movementTracker;
    private PlayerVisibilityCache playerVisibilityCache;
    private VisibilityEngine visibilityEngine;
    private PacketMaskService packetMaskService;
    private EntityMaskService entityMaskService;
    private DebugOverlayService debugOverlayService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.cullerConfig = SightCullerConfig.from(getConfig());
        this.metricsService = new MetricsService(this, cullerConfig.metricsIntervalTicks());
        this.sectionDirtyTracker = new SectionDirtyTracker();
        this.movementTracker = new MovementTracker(cullerConfig);
        this.playerVisibilityCache = new PlayerVisibilityCache(cullerConfig);
        this.visibilityEngine = new VisibilityEngine(
                this,
                cullerConfig,
                playerVisibilityCache,
                sectionDirtyTracker,
                movementTracker,
                new ExposureGraphBuilder(),
                new MaskPaletteResolver(cullerConfig)
        );
        this.debugOverlayService = new DebugOverlayService(this, cullerConfig, visibilityEngine, playerVisibilityCache, movementTracker);
        this.packetMaskService = new PacketMaskService(this, visibilityEngine, metricsService, sectionDirtyTracker);
        this.entityMaskService = new EntityMaskService(this, visibilityEngine);

        Bukkit.getPluginManager().registerEvents(new InteractionGuardListener(cullerConfig, visibilityEngine, metricsService), this);
        Bukkit.getPluginManager().registerEvents(movementTracker, this);
        Bukkit.getPluginManager().registerEvents(sectionDirtyTracker, this);

        PluginCommand command = getCommand("sightculler");
        if (command != null) {
            SightCullerCommand executor = new SightCullerCommand(this, cullerConfig, visibilityEngine, debugOverlayService, metricsService);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        packetMaskService.start();
        entityMaskService.start();
        debugOverlayService.start();
        metricsService.start();
        getLogger().info("SightCuller enabled.");
    }

    @Override
    public void onDisable() {
        if (packetMaskService != null) {
            packetMaskService.stop();
        }
        if (entityMaskService != null) {
            entityMaskService.stop();
        }
        if (debugOverlayService != null) {
            debugOverlayService.stop();
        }
        if (metricsService != null) {
            metricsService.stop();
        }
    }

    public void reloadSightCuller() {
        reloadConfig();
        this.cullerConfig = SightCullerConfig.from(getConfig());
        visibilityEngine.reload(cullerConfig);
        movementTracker.reload(cullerConfig);
        playerVisibilityCache.reload(cullerConfig);
        debugOverlayService.reload(cullerConfig);
    }

    public SightCullerConfig cullerConfig() {
        return cullerConfig;
    }
}
