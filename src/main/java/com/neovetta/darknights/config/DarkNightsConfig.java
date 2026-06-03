package com.neovetta.darknights.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.neovetta.darknights.DarkNights;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Path;

public class DarkNightsConfig {

    public boolean enableBloodMoon = true;
    public float bloodMoonChance = 0.05f;

    public boolean enableLycanthropy = true;
    public float infectionChance = 0.15f;
    public float wolfMeatInfectionChance = 0.80f;
    public boolean transformOnBloodMoon = true;

    public int maxFamiliarCap = 8;
    public boolean enableFactionCombat = true;

    public boolean enableZombiePlague = true;
    public float rottenFleshInfectionChance = 0.05f;
    public float infectedBrainInfectionChance = 0.50f;
    public float zombieInfectionChance = 0.10f;
    public int maxHordeCap = 4;

    public boolean enableVampire = true;
    public float vampireFangInfectionChance = 0.30f;
    public float vampireAttackInfectionChance = 0.10f;

    public boolean enableRunicAttunement = true;

    public boolean enableWildHunt = true;
    public float wildHuntChance = 0.03f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static DarkNightsConfig load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("darknights.json");
        File configFile = configPath.toFile();
        if (!configFile.exists()) {
            DarkNightsConfig defaults = new DarkNightsConfig();
            defaults.save(configFile);
            return defaults;
        }
        try (Reader reader = new FileReader(configFile)) {
            DarkNightsConfig cfg = GSON.fromJson(reader, DarkNightsConfig.class);
            return cfg != null ? cfg : new DarkNightsConfig();
        } catch (IOException e) {
            DarkNights.LOGGER.error("Failed to load config, using defaults", e);
            return new DarkNightsConfig();
        }
    }

    private void save(File file) {
        try (Writer writer = new FileWriter(file)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            DarkNights.LOGGER.error("Failed to save default config", e);
        }
    }
}
