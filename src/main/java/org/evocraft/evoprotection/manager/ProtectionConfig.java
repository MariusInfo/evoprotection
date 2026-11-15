package org.evocraft.evoprotection.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class ProtectionConfig {
    private static ProtectionConfig INSTANCE;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File FILE = FMLPaths.CONFIGDIR.get().resolve("evo_protection_settings.json").toFile();

    // --- SETĂRILE GLOBALE ---
    public String language = "ro"; // LIMBA ACTUALA: ro sau en

    public static void load() {
        if (FILE.exists()) {
            try (Reader reader = new InputStreamReader(new FileInputStream(FILE), StandardCharsets.UTF_8)) {
                INSTANCE = GSON.fromJson(reader, ProtectionConfig.class);
            } catch (Exception e) { e.printStackTrace(); }
        }
        if (INSTANCE == null) {
            INSTANCE = new ProtectionConfig();
            INSTANCE.validate();
            INSTANCE.save();
        } else {
            INSTANCE.validate();
        }
    }

    private void validate() {
        if (language == null || (!language.equalsIgnoreCase("en") && !language.equalsIgnoreCase("ro"))) {
            language = "ro";
        } else {
            language = language.toLowerCase(java.util.Locale.ROOT);
        }
    }

    public void save() {
        if (FILE.getParentFile() != null && !FILE.getParentFile().exists()) {
            FILE.getParentFile().mkdirs();
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static ProtectionConfig get() {
        if (INSTANCE == null) load();
        return INSTANCE;
    }
}