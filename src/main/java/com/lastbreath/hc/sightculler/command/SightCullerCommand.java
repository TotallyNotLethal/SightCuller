package com.lastbreath.hc.sightculler.command;

import com.lastbreath.hc.sightculler.SightCullerPlugin;
import com.lastbreath.hc.sightculler.config.SightCullerConfig;
import com.lastbreath.hc.sightculler.debug.DebugOverlayService;
import com.lastbreath.hc.sightculler.metrics.MetricsService;
import com.lastbreath.hc.sightculler.visibility.VisibilityEngine;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class SightCullerCommand implements CommandExecutor, TabCompleter {
    private final SightCullerPlugin plugin;
    private final SightCullerConfig config;
    private final VisibilityEngine visibility;
    private final DebugOverlayService debug;
    private final MetricsService metrics;

    public SightCullerCommand(SightCullerPlugin plugin, SightCullerConfig config, VisibilityEngine visibility, DebugOverlayService debug, MetricsService metrics) {
        this.plugin = plugin;
        this.config = config;
        this.visibility = visibility;
        this.debug = debug;
        this.metrics = metrics;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("/sightculler <reload|debug|inspect|stats|revealtest>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadSightCuller();
                sender.sendMessage("SightCuller config reloaded.");
            }
            case "debug" -> {
                if (args.length < 2) {
                    sender.sendMessage("/sightculler debug on|off");
                    return true;
                }
                debug.setDebugForced(args[1].equalsIgnoreCase("on"));
                sender.sendMessage("Debug now " + (debug.isDebugEnabled() ? "enabled" : "disabled"));
            }
            case "inspect" -> {
                if (sender instanceof Player player) {
                    sender.sendMessage("Inspect: " + debug.inspect(player));
                } else {
                    sender.sendMessage("Only players can inspect their bucket.");
                }
            }
            case "stats" -> sender.sendMessage(metrics.summary());
            case "revealtest" -> {
                if (sender instanceof Player player) {
                    var target = player.getTargetBlockExact(6);
                    if (target == null) {
                        sender.sendMessage("No target block in range.");
                    } else {
                        boolean revealed = visibility.shouldReveal(player, player.getWorld(), target.getX(), target.getY(), target.getZ(), target.getType());
                        sender.sendMessage("RevealTest: " + target.getType() + " -> " + (revealed ? "REVEALED" : "MASKED"));
                    }
                } else {
                    sender.sendMessage("Only players can run revealtest.");
                }
            }
            default -> sender.sendMessage("Unknown subcommand.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "debug", "inspect", "stats", "revealtest");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return List.of("on", "off");
        }
        return List.of();
    }
}
