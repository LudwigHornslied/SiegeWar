package com.gmail.goosius.siegewar.playeractions;

import com.gmail.goosius.siegewar.Messaging;
import com.gmail.goosius.siegewar.SiegeController;
import com.gmail.goosius.siegewar.SiegeWar;
import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.gmail.goosius.siegewar.enums.SiegeStatus;
import com.gmail.goosius.siegewar.enums.SiegeWarPermissionNodes;
import com.gmail.goosius.siegewar.hud.SiegeHUDManager;
import com.gmail.goosius.siegewar.objects.Siege;
import com.gmail.goosius.siegewar.settings.SiegeWarSettings;
import com.gmail.goosius.siegewar.utils.CosmeticUtil;
import com.gmail.goosius.siegewar.utils.SiegeWarBlockUtil;
import com.gmail.goosius.siegewar.utils.SiegeWarDistanceUtil;
import com.gmail.goosius.siegewar.utils.SiegeWarScoringUtil;
import com.gmail.goosius.siegewar.utils.SiegeWarAllegianceUtil;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.permissions.TownyPermissionSource;
import com.gmail.goosius.siegewar.settings.Translation;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Banner;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * This class intercepts 'player death' events coming from the TownyPlayerListener class.
 *
 * This class evaluates the death, and determines if the player is involved in any nearby sieges.
 * If so, the opposing team gains battle points, and the player keeps inventory.
 *
 * @author Goosius
 */
public class PlayerDeath {

	/**
	 * Evaluates a siege death event.
	 * <p>
	 * If the dead player is officially involved in a nearby siege,
	 * - The opposing team gains battle points
	 * - Their inventory items degrade a little (e.g. 20%)
	 * <p>
	 * The allegiance of the killer is not considered,
	 * in order to allows for a wider range of siege-kill-tactics.
	 * Examples:
	 * - Players without towns can contribute to battle points
	 * - Players from non-nation towns can contribute to battle points
	 * - Players from secretly-allied nations can contribute to battle points
	 * - Devices (cannons, traps, bombs etc.) can be used to gain battle points
	 *
	 * @param deadPlayer The player who died
	 * @param playerDeathEvent The player death event
	 */
	public static void evaluateSiegePlayerDeath(Player deadPlayer, PlayerDeathEvent playerDeathEvent) {
		try {
			if (!SiegeWarSettings.getWarSiegeWorlds().contains(playerDeathEvent.getEntity().getWorld().getName()))
				return;

			TownyPermissionSource tps = TownyUniverse.getInstance().getPermissionSource();
			Resident deadResident = TownyUniverse.getInstance().getResident(deadPlayer.getUniqueId());

			if (deadResident == null || !deadResident.hasTown())
				return;

			/*
			 * Do an early permission test to avoid hitting the sieges list if
			 * it could never return a proper SiegeSide.
			 */
			if (!tps.testPermission(deadPlayer, SiegeWarPermissionNodes.SIEGEWAR_TOWN_SIEGE_BATTLE_POINTS.getNode())
				&& !tps.testPermission(deadPlayer, SiegeWarPermissionNodes.SIEGEWAR_NATION_SIEGE_BATTLE_POINTS.getNode()))
				return;

			Town deadResidentTown = deadResident.getTown();

			//Declare local variables
			Siege confirmedCandidateSiege = null;
			SiegeSide confirmedCandidateSiegePlayerSide = SiegeSide.NOBODY;
			double confirmedCandidateDistanceToPlayer = 0;
			double candidateSiegeDistanceToPlayer;
			SiegeSide candidateSiegePlayerSide;

			//Find nearest eligible siege
			for (Siege candidateSiege : SiegeController.getSieges()) {

				//Skip if siege is not active
				if (!candidateSiege.getStatus().isActive())
					continue;

				//Skip if player is not is siege-zone
				if(!SiegeWarDistanceUtil.isInSiegeZone(deadPlayer, candidateSiege))
					continue;

				//Is player eligible ?
				candidateSiegePlayerSide = SiegeWarAllegianceUtil.calculateCandidateSiegePlayerSide(deadPlayer, deadResidentTown, candidateSiege);

				if(candidateSiegePlayerSide == SiegeSide.NOBODY)
					continue;

				//Confirm candidate siege if it is 1st viable one OR closer than confirmed candidate
				candidateSiegeDistanceToPlayer = deadPlayer.getLocation().distance(candidateSiege.getFlagLocation());
				if (confirmedCandidateSiege == null || candidateSiegeDistanceToPlayer < confirmedCandidateDistanceToPlayer) {
					confirmedCandidateSiege = candidateSiege;
					confirmedCandidateSiegePlayerSide = candidateSiegePlayerSide;
					confirmedCandidateDistanceToPlayer = candidateSiegeDistanceToPlayer;
				}
			}

			//If player is confirmed as close to one or more sieges in which they are eligible to be involved, 
			// apply siege point penalty for the nearest one, and keep inventory
			if (confirmedCandidateSiege != null) {

				//Award penalty points w/ notification if siege is in progress
				if(confirmedCandidateSiege.getStatus() == SiegeStatus.IN_PROGRESS) {
					if (SiegeWarSettings.getWarSiegeDeathSpawnFireworkEnabled()) {
						if (isBannerMissing(confirmedCandidateSiege.getFlagLocation()))
							replaceMissingBanner(confirmedCandidateSiege.getFlagLocation());
						Color bannerColor = ((Banner) confirmedCandidateSiege.getFlagLocation().getBlock().getState()).getBaseColor().getColor();
						CosmeticUtil.spawnFirework(deadPlayer.getLocation().add(0, 2, 0), Color.RED, bannerColor, true);
					}

					boolean residentIsAttacker = confirmedCandidateSiegePlayerSide == SiegeSide.ATTACKERS;
					SiegeWarScoringUtil.awardPenaltyPoints(
						residentIsAttacker,
						deadPlayer,
						confirmedCandidateSiege);
				}

				//Keep and degrade inventory
				degradeInventory(playerDeathEvent);
				keepInventory(playerDeathEvent);
				//Keep level
				keepLevel(playerDeathEvent);

				SiegeHUDManager.updateHUDs();

				if(confirmedCandidateSiege.getBannerControlSessions().containsKey(deadPlayer)) { //If the player that died had an ongoing session, remove it.
					confirmedCandidateSiege.removeBannerControlSession(confirmedCandidateSiege.getBannerControlSessions().get(deadPlayer));
					String errorMessage = SiegeWarSettings.isTrapWarfareMitigationEnabled() ? Translation.of("msg_siege_war_banner_control_session_failure_with_altitude") : Translation.of("msg_siege_war_banner_control_session_failure");
					Messaging.sendMsg(deadPlayer, errorMessage);
				}
			}
		} catch (Exception e) {
			try {
				SiegeWar.severe("Error evaluating siege death for player " + deadPlayer.getName());
			} catch (Exception e2) {
				SiegeWar.severe("Error evaluating siege death (could not read player name)");
			}
			e.printStackTrace();
		}
	}

	private static void degradeInventory(PlayerDeathEvent playerDeathEvent) {
		Damageable damageable;
		double maxDurability;
		int currentDurability, damageToInflict, newDurability, durabilityWarning;
		boolean closeToBreaking = false;
		if (SiegeWarSettings.getWarSiegeDeathPenaltyDegradeInventoryEnabled()) {
			for (ItemStack itemStack : playerDeathEvent.getEntity().getInventory().getContents()) {
				if (itemStack != null && itemStack.getType().getMaxDurability() != 0 && !itemStack.getItemMeta().isUnbreakable()) {
					damageable = ((Damageable) itemStack.getItemMeta());
					maxDurability = itemStack.getType().getMaxDurability();
					currentDurability = damageable.getDamage();
					damageToInflict = (int)(maxDurability / 100 * SiegeWarSettings.getWarSiegeDeathPenaltyDegradeInventoryPercentage());
					newDurability = currentDurability + damageToInflict;
					if (newDurability >= maxDurability) {
						damageable.setDamage(Math.max((int)maxDurability-25, currentDurability));
						closeToBreaking = true;
					}
					else {
						damageable.setDamage(newDurability);
						durabilityWarning = damageToInflict * 2 + currentDurability;
						if (durabilityWarning >= maxDurability)
							closeToBreaking = true;
					}
					itemStack.setItemMeta((ItemMeta)damageable);
				}
			}
			if (closeToBreaking) //One or more items are close to breaking, send warning.
				Messaging.sendMsg(playerDeathEvent.getEntity(), Translation.of("msg_inventory_degrade_warning"));
		}
	}

	private static void keepInventory(PlayerDeathEvent playerDeathEvent) {
		if(SiegeWarSettings.getWarSiegeDeathPenaltyKeepInventoryEnabled() && !playerDeathEvent.getKeepInventory()) {
			playerDeathEvent.setKeepInventory(true);
			playerDeathEvent.getDrops().clear();
		}
	}

	private static void keepLevel(PlayerDeathEvent playerDeathEvent) {
		if(SiegeWarSettings.getWarSiegeDeathPenaltyKeepLevelEnabled() && !playerDeathEvent.getKeepLevel()) {
			playerDeathEvent.setKeepLevel(true);
			playerDeathEvent.setDroppedExp(0);
		}
	}

	private static boolean isBannerMissing(Location location) {
		return !Tag.BANNERS.isTagged(location.getBlock().getType());
	}

	private static void replaceMissingBanner(Location location) {
		if (SiegeWarBlockUtil.isSupportBlockUnstable(location.getBlock()))
			location.getBlock().getRelative(BlockFace.DOWN).setType(Material.STONE);
		
		location.getBlock().setType(Material.BLACK_BANNER);
	}
}
