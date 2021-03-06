package com.rojel.wesv;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class WesvListener implements Listener {

	private final WorldEditSelectionVisualizer plugin;

	public WesvListener(final WorldEditSelectionVisualizer plugin) {
		this.plugin = plugin;
	}

	@EventHandler
	public void onPlayerJoin(final PlayerJoinEvent event) {
		this.plugin.addPlayer(event.getPlayer());
	}

	@EventHandler
	public void onWorldEditSelectionChange(final WorldEditSelectionChangeEvent event) {
		final Player player = event.getPlayer();
		if (this.plugin.isSelectionShown(player)) {
			this.plugin.showSelection(player);
		}
	}

	@EventHandler
	public void onItemChange(final PlayerItemHeldEvent event) {
		final Player player = event.getPlayer();
		if (this.plugin.getCustomConfig().isCheckForAxeEnabled() && this.plugin.getCustomConfig().isEnabled(player)) {

			this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
				if (this.plugin.isHoldingSelectionItem(player)) {
					this.plugin.showSelection(player);
				} else {
					this.plugin.hideSelection(player);
				}
			});
		}
	}

	@EventHandler
	public void onPlayerQuit(final PlayerQuitEvent event) {
		plugin.removePlayer(event.getPlayer());
	}
}
