package com.gmail.goosius.siegewar.tasks;

import java.io.InputStream;
import java.util.*;

import com.gmail.goosius.siegewar.SiegeController;
import com.gmail.goosius.siegewar.SiegeWar;
import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.gmail.goosius.siegewar.enums.SiegeStatus;
import com.gmail.goosius.siegewar.objects.BattleSession;
import com.gmail.goosius.siegewar.objects.Siege;
import com.gmail.goosius.siegewar.settings.Settings;
import com.gmail.goosius.siegewar.settings.SiegeWarSettings;
import com.gmail.goosius.siegewar.settings.Translation;
import com.gmail.goosius.siegewar.utils.SiegeWarDynmapUtil;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.util.StringMgmt;
import org.apache.commons.lang.WordUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerIcon;
import org.dynmap.markers.MarkerSet;

public class DynmapTask {

    static DynmapAPI dynmapAPI;
    static MarkerAPI markerapi;
    static boolean stop;
    static MarkerSet siegeWarMarkerSet;
    static MarkerSet townyMarkerSet;
    static Map<UUID, Marker> townUUDToSiegeMarkerMap = new HashMap<>();
    final static String PEACEFUL_BANNER_ICON_ID = "fire";
    final static String BATTLE_BANNER_ICON_ID = "siegewar.battle";

    public static void setupDynmapAPI(DynmapAPI _api) {
        dynmapAPI = _api;
        markerapi = dynmapAPI.getMarkerAPI();
        if (markerapi == null) {
        	SiegeWar.severe("Error loading dynmap marker API!");
            return;
        }

        //Create siegewar marker set
        siegeWarMarkerSet = markerapi.getMarkerSet("siegewar.markerset");
        if (siegeWarMarkerSet == null) {
            siegeWarMarkerSet = markerapi.createMarkerSet("siegewar.markerset", "SiegeWar", null, false);
        } else
            siegeWarMarkerSet.setMarkerSetLabel("SiegeWar");

        if (siegeWarMarkerSet == null) {
        	SiegeWar.severe("Error creating dynmap marker set");
            return;
        }

        //Get towny marker set
        townyMarkerSet = markerapi.getMarkerSet("towny.markerset");

        //Create battle banner marker icon
        InputStream png = SiegeWar.getSiegeWar().getResource(Settings.BATTLE_BANNER_FILE_NAME);
        markerapi.createMarkerIcon(BATTLE_BANNER_ICON_ID, "BattleBanner", png);

        startDynmapTask();
        SiegeWar.info("Dynmap support enabled.");
    }

    public static void startDynmapTask() {
        stop = false;
        Bukkit.getScheduler().runTaskTimerAsynchronously(SiegeWar.getSiegeWar(), () -> {
            if (!stop) {
                applyMapHidingToPlayers();
                displaySieges();
            }
        }, 40l, 300l);
    }

    public static void endDynmapTask() {
        stop = true;
    }

    /**
     * Remove markers belonging to sieges that have ended
     * Also change any icons if required (between peaceful icon & battle icon)
     */
    private static void displaySieges() {
        Map<UUID, Marker> townUUDToSiegeMarkerMapCopy = new HashMap<>(townUUDToSiegeMarkerMap);

        {
            //Cleanup current siege markers
            UUID townUUID = null;
            Marker marker = null;
            for (Map.Entry<UUID, Marker> mapEntry : townUUDToSiegeMarkerMapCopy.entrySet()) {
                try {
                    marker = null;
                    townUUID = null;
                    townUUID = mapEntry.getKey();
                    marker = mapEntry.getValue();
                    Siege siege = SiegeController.getSiegeByTownUUID(townUUID);

                    if (siege == null || siege.getStatus() != SiegeStatus.IN_PROGRESS) {
                        //Delete marker if siege is not in progress
                        marker.deleteMarker();
                        townUUDToSiegeMarkerMap.remove(townUUID);

                    } else if (marker.getMarkerIcon().getMarkerIconID().equals(PEACEFUL_BANNER_ICON_ID)) {
                        /*
                         * Change to battle icon if battle is active.
                         */
                        if (BattleSession.getBattleSession().isActive()
                                && (siege.getAttackerBattlePoints() > 0
                                || siege.getDefenderBattlePoints() > 0
                                || siege.getBannerControllingSide() != SiegeSide.NOBODY
                                || siege.getBannerControlSessions().size() > 0)) {
                            marker.setMarkerIcon(markerapi.getMarkerIcon(BATTLE_BANNER_ICON_ID));
                        }

                    } else if (marker.getMarkerIcon().getMarkerIconID().equals(BATTLE_BANNER_ICON_ID)) {
                        /*
                         * Change to peaceful icon if battle is no longer active.
                         */
                        if (!BattleSession.getBattleSession().isActive()
                                || (siege.getAttackerBattlePoints() == 0
                                && siege.getDefenderBattlePoints() == 0
                                && siege.getBannerControllingSide() == SiegeSide.NOBODY
                                && siege.getBannerControlSessions().size() == 0)) {
                            marker.setMarkerIcon(markerapi.getMarkerIcon(PEACEFUL_BANNER_ICON_ID));
                        }
                    }
                } catch (Exception e) {
                    if (marker != null)
                        marker.deleteMarker();
                    townUUDToSiegeMarkerMap.remove(townUUID);
                }
            }
        }

        {
            //Add siege marker if required
            for (Siege siege : SiegeController.getSieges()) {

                String name = Translation.of("dynmap_siege_title", siege.getAttackerNameForDisplay(), siege.getDefenderNameForDisplay());
                try {
                    if (siege.getStatus().isActive()) {
                        //If anyone is in a BC session or on the BC list, it is a fire & swords icon
                        //otherwise just fire
                        MarkerIcon siegeIcon;
                        if (siege.getBannerControllingSide() == SiegeSide.NOBODY
                                && siege.getBannerControlSessions().size() == 0) {
                            siegeIcon = markerapi.getMarkerIcon(PEACEFUL_BANNER_ICON_ID);
                        } else {
                            siegeIcon = markerapi.getMarkerIcon(BATTLE_BANNER_ICON_ID);
                        }
                        List<String> lines = new ArrayList<>();
                        lines.add(Translation.of("dynmap_siege_town", siege.getTown().getName()));
                        lines.add(Translation.of("dynmap_siege_type", siege.getSiegeType().getName()));
                        lines.add(Translation.of("dynmap_siege_balance", siege.getSiegeBalance()));
                        lines.add(Translation.of("dynmap_siege_time_left", siege.getTimeRemaining()));
                        if(TownyEconomyHandler.isActive()) {
                            lines.add(Translation.of("dynmap_siege_war_chest", TownyEconomyHandler.getFormattedBalance(siege.getWarChestAmount())));
                        }
                        lines.add(Translation.of("dynmap_siege_banner_control",
                            WordUtils.capitalizeFully(siege.getBannerControllingSide().name())
                            + (siege.getBannerControllingSide() == SiegeSide.NOBODY ? "" :  " (" + siege.getBannerControllingResidents().size() + ")")));
                        lines.add(Translation.of("dynmap_siege_battle_points", siege.getFormattedAttackerBattlePoints(), siege.getFormattedDefenderBattlePoints()));
                        lines.add(Translation.of("dynmap_siege_battle_time_left", siege.getFormattedBattleTimeRemaining()));

                        String desc = "<b>" + name + "</b><hr>" + StringMgmt.join(lines, "<br>");
                        Location siegeLoc = siege.getFlagLocation();
                        double siegeX = siegeLoc.getX();
                        double siegeZ = siegeLoc.getZ();
                        String siegeMarkerId = siege.getTown().getUUID().toString();
                        Marker siegeMarker = siegeWarMarkerSet.findMarker(siegeMarkerId);
                        if (siegeMarker == null) {
                            siegeWarMarkerSet.createMarker(siegeMarkerId, name, siegeLoc.getWorld().getName(), siegeX, 64,
                                    siegeZ, siegeIcon, false);

                            siegeMarker = siegeWarMarkerSet.findMarker(siegeMarkerId);
                            siegeMarker.setLabel(name);
                            siegeMarker.setDescription(desc);
                        } else {
                            siegeMarker.setLabel(name);
                            siegeMarker.setDescription(desc);
                        }
                        townUUDToSiegeMarkerMap.put(siege.getTown().getUUID(), siegeMarker);
                    }
                } catch (Exception ex) {
                	SiegeWar.severe("Problem adding siege marker for siege: " + name);
                    ex.printStackTrace();
                }
            }
        }
    }

    /**
     * This method hides players who have the map hiding metadata tag.
     * It also un-hides players who do not hav it.
     */
    private static void applyMapHidingToPlayers() {
        if (!SiegeWarSettings.getWarSiegeMapHidingEnabled())
            return;

        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());

        for (Player player : onlinePlayers) {
            if (player.hasMetadata(SiegeWarDynmapUtil.MAP_HIDING_METADATA_ID)) {
                dynmapAPI.assertPlayerInvisibility(player, true, SiegeWar.getSiegeWar());
            } else {
                dynmapAPI.assertPlayerInvisibility(player, false, SiegeWar.getSiegeWar());
            }
        }
    }
}
