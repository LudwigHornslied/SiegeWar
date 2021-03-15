package com.gmail.goosius.siegewar.playeractions;

import com.gmail.goosius.siegewar.Messaging;
import com.gmail.goosius.siegewar.SiegeController;
import com.gmail.goosius.siegewar.TownOccupationController;
import com.gmail.goosius.siegewar.metadata.NationMetaDataController;
import com.gmail.goosius.siegewar.objects.Siege;
import com.gmail.goosius.siegewar.utils.SiegeWarNationUtil;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.Coord;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Town;
import com.gmail.goosius.siegewar.settings.Translation;

/**
 * This class is responsible for processing requests to invade towns
 *
 * @author Goosius
 */
public class InvadeTown {

	/**
	 * Process an invade town request
	 *
	 * This method does some final checks and if they pass, the invasion is executed.
	 *
	 * @param nationOfInvadingResident the nation who the invading resident belongs to.
	 * @param townToBeInvaded the town to be invaded
	 * @param siege the siege of the town.
	 * @throws TownyException when the invasion wont be allowed.
	 */
    public static void processInvadeTownRequest(Nation nationOfInvadingResident,
                                                Town townToBeInvaded,
                                                Siege siege) throws TownyException {

    	Nation attackerWinner = siege.getNation();
		
		if (nationOfInvadingResident != attackerWinner)
			throw new TownyException(Translation.of("msg_err_cannot_invade_without_victory"));

        if (siege.isTownInvaded())
            throw new TownyException(String.format(Translation.of("msg_err_town_already_invaded"), townToBeInvaded.getName()));

		if (TownySettings.getNationRequiresProximity() > 0) {
			Coord capitalCoord = attackerWinner.getCapital().getHomeBlock().getCoord();
			Coord townCoord = townToBeInvaded.getHomeBlock().getCoord();
			if (!attackerWinner.getCapital().getHomeBlock().getWorld().getName().equals(townToBeInvaded.getHomeBlock().getWorld().getName())) {
				throw new TownyException(Translation.of("msg_err_nation_homeblock_in_another_world"));
			}
			double distance;
			distance = Math.sqrt(Math.pow(capitalCoord.getX() - townCoord.getX(), 2) + Math.pow(capitalCoord.getZ() - townCoord.getZ(), 2));
			if (distance > TownySettings.getNationRequiresProximity()) {
				throw new TownyException(String.format(Translation.of("msg_err_town_not_close_enough_to_nation"), townToBeInvaded.getName()));
			}
		}

		if (TownySettings.getMaxTownsPerNation() > 0) {
			int effectiveNumTowns = SiegeWarNationUtil.calculateEffectiveNumberOfTownsInNation(attackerWinner);
			if (effectiveNumTowns >= TownySettings.getMaxTownsPerNation()){
				throw new TownyException(String.format(Translation.of("msg_err_nation_over_town_limit"), TownySettings.getMaxTownsPerNation()));
			}
		}

		invadeTown(siege, attackerWinner, townToBeInvaded);
    }

	/**
	 * Invade the town
	 *
	 * @param siege the siege
	 * @param invadingNation the nation doing the invading
	 * @param invadedTown the town being invaded
	 */
    private static void invadeTown(Siege siege, Nation invadingNation, Town invadedTown) {
		Nation nationOfInvadedTown = null;

        if(invadedTown.hasNation()) {
			//Update stats of defeated nation
            nationOfInvadedTown = TownyAPI.getInstance().getTownNationOrNull(invadedTown);
			NationMetaDataController.setTotalTownsLost(nationOfInvadedTown, NationMetaDataController.getTotalTownsLost(nationOfInvadedTown) + 1);
        }

		//Set town to occupied
		TownOccupationController.setTownOccupier(invadedTown, invadingNation);
        //Update siege flags
		siege.setTownInvaded(true);
		//Update stats of victorious nation
		NationMetaDataController.setTotalTownsGained(invadingNation, NationMetaDataController.getTotalTownsGained(invadingNation) + 1);

		//Save to db
        SiegeController.saveSiege(siege);
		invadedTown.save();
		invadingNation.save();
		if(nationOfInvadedTown != null) {
			nationOfInvadedTown.save();
		}
		
		//Messaging
		if(nationOfInvadedTown != null) {
			Messaging.sendGlobalMessage(
				Translation.of("msg_nation_town_invaded",
				invadedTown.getFormattedName(),
				nationOfInvadedTown.getFormattedName(),
				invadingNation.getFormattedName()
			));
		} else {
			Messaging.sendGlobalMessage(
				Translation.of("msg_neutral_town_invaded",
				invadedTown.getFormattedName(),
				invadingNation.getFormattedName()
			));
		}
    }
}
