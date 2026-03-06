package com.lastbreath.hc.sightculler.interaction;

import com.lastbreath.hc.sightculler.config.SightCullerConfig;
import com.lastbreath.hc.sightculler.metrics.MetricsService;
import com.lastbreath.hc.sightculler.visibility.VisibilityEngine;
import org.bukkit.FluidCollisionMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;

public final class InteractionGuardListener implements Listener {
    private final SightCullerConfig config;
    private final VisibilityEngine visibility;
    private final MetricsService metrics;

    public InteractionGuardListener(SightCullerConfig config, VisibilityEngine visibility, MetricsService metrics) {
        this.config = config;
        this.visibility = visibility;
        this.metrics = metrics;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!config.lineOfSightEnforcement()) return;
        if (rejectIfHidden(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!config.lineOfSightEnforcement()) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        if (rejectIfHidden(event.getPlayer(), clicked)) {
            event.setCancelled(true);
        }
    }

    private boolean rejectIfHidden(Player player, Block block) {
        boolean revealed = visibility.shouldReveal(player, block.getWorld(), block.getX(), block.getY(), block.getZ(), block.getType());
        boolean los = hasDirectLOS(player, block);
        if (revealed && los) {
            return false;
        }
        metrics.recordProbeDenied();
        if (config.alertOnBlockProbe()) {
            player.sendActionBar("§cSightCuller: blocked hidden or non-LOS interaction");
        }
        return true;
    }

    private boolean hasDirectLOS(Player player, Block block) {
        RayTraceResult hit = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(),
                block.getLocation().toCenterLocation().toVector().subtract(player.getEyeLocation().toVector()).normalize(),
                6.0,
                FluidCollisionMode.NEVER,
                true
        );
        return hit != null && hit.getHitBlock() != null && hit.getHitBlock().equals(block);
    }
}
