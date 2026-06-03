package com.neovetta.darknights;

import com.neovetta.darknights.command.DarkNightsCommand;
import com.neovetta.darknights.config.DarkNightsConfig;
import com.neovetta.darknights.handler.BloodMoonHandler;
import com.neovetta.darknights.handler.WerewolfHandler;
import com.neovetta.darknights.handler.FamiliarHandler;
import com.neovetta.darknights.handler.ZombieHandler;
import com.neovetta.darknights.handler.RuneHandler;
import com.neovetta.darknights.handler.WildHuntHandler;
import com.neovetta.darknights.handler.VampireHandler;
import com.neovetta.darknights.handler.ZombieHordeHandler;
import com.neovetta.darknights.item.DarkNightsItems;
import com.neovetta.darknights.network.DarkNightsPackets;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DarkNights implements ModInitializer {

    public static final String MOD_ID = "darknights";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static DarkNightsConfig CONFIG;

    @Override
    public void onInitialize() {
        CONFIG = DarkNightsConfig.load();
        DarkNightsItems.register();
        DarkNightsPackets.registerServerbound();
        DarkNightsCommand.register();
        BloodMoonHandler.register();
        WerewolfHandler.register();
        FamiliarHandler.register();
        ZombieHandler.register();
        ZombieHordeHandler.register();
        VampireHandler.register();
        RuneHandler.register();
        WildHuntHandler.register();
        LOGGER.info("Dark Nights initialized");
    }
}
