package org.evocraft.evoprotection.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;

public class ProtectionConfig {
    private static ProtectionConfig INSTANCE;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File FILE = FMLPaths.CONFIGDIR.get().resolve("evo_protection_settings.json").toFile();

    // --- SETĂRILE GLOBALE ---
    public boolean isPlotMode = true; // ACTIVAT PENTRU CREATIVE!
    public String language = "ro"; // LIMBA ACTUALA: ro sau en

    // MATEMATICA GRID-ULUI
    public int plotSizeChunks = 4; // 4x4 Chunk-uri (64x64 blocuri per plot = 16 chunk-uri)
    public int roadSizeChunks = 1; // 1 Chunk lățime drum (16 blocuri)
    public int spawnRadiusPlots = 0; // 0 = rezervă doar Plot-ul central (0,0), adică fix 16 Chunk-uri pentru Spawn!

    // ÎNĂLȚIMEA DRUMURILOR (Aici e fixul pentru podea)
    public boolean useFixedYLevel = true;
    public int plotYLevel = -61; // Setat EXACT pe nivelul podelei tale din poză!

    // BLOCURILE GENERATE AUTOMAT
    public String borderBlock = "minecraft:smooth_stone_slab"; // Bordura plotului
    public String roadBlock = "minecraft:black_terracotta"; // Asfaltul drumului
    public String lineBlock = "minecraft:white_concrete"; // Linia de pe mijlocul drumului

    public static void load() {
        if (FILE.exists()) {
            try (Reader reader = new FileReader(FILE)) {
                INSTANCE = GSON.fromJson(reader, ProtectionConfig.class);
            } catch (Exception e) { e.printStackTrace(); }
        }
        if (INSTANCE == null) {
            INSTANCE = new ProtectionConfig();
            INSTANCE.save();
        }
    }

    public void save() {
        try (Writer writer = new FileWriter(FILE)) {
            GSON.toJson(this, writer);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static ProtectionConfig get() {
        if (INSTANCE == null) load();
        return INSTANCE;
    }
}