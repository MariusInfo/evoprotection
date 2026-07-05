package org.evocraft.evoprotection.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class LanguageManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File DIR = FMLPaths.CONFIGDIR.get().resolve("evoprotection_lang").toFile();

    // Stocam AMBELE dictionare in memorie (ro si en)
    private static final Map<String, Map<String, String>> dicts = new HashMap<>();

    // Limba locala a clientului pentru interfetele grafice
    public static String clientLang = "ro";

    public static void load() {
        if (!DIR.exists()) {
            DIR.mkdirs();
        }
        // Generam sau actualizam ambele fisiere la fiecare pornire
        loadOrGenerate("ro");
        loadOrGenerate("en");
    }

    private static void loadOrGenerate(String lang) {
        File file = new File(DIR, lang + ".json");
        Map<String, String> defaultDict = getDefaults(lang);
        Map<String, String> currentFileDict = new HashMap<>();
        boolean needsUpdate = false;

        // Daca fisierul exista deja, il citim in memorie
        if (file.exists()) {
            try (Reader reader = new FileReader(file)) {
                Type type = new TypeToken<Map<String, String>>(){}.getType();
                currentFileDict = GSON.fromJson(reader, type);
                if (currentFileDict == null) {
                    currentFileDict = new HashMap<>();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Sistemul de AUTO-UPDATE inteligent:
        // Verificam fiecare cheie din setarile default. Daca lipseste din fisierul playerului, o adaugam.
        for (Map.Entry<String, String> entry : defaultDict.entrySet()) {
            if (!currentFileDict.containsKey(entry.getKey())) {
                currentFileDict.put(entry.getKey(), entry.getValue());
                needsUpdate = true;
            }
        }

        // Daca fisierul nu exista SAU au fost adaugate texte noi prin update, salvam fisierul.
        if (!file.exists() || needsUpdate) {
            try (Writer writer = new FileWriter(file)) {
                GSON.toJson(currentFileDict, writer);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Punem in memorie dictionarul final (cu eventualele modificari ale adminului pastrate)
        dicts.put(lang, currentFileDict);
    }

    // GETTER PENTRU SERVER (Cauta in limba specifica a jucatorului)
    public static String get(String lang, String key) {
        Map<String, String> dict = dicts.getOrDefault(lang, dicts.get("ro"));
        if (dict == null) {
            return key;
        }
        return dict.getOrDefault(key, key);
    }

    public static String get(String lang, String key, Object... args) {
        return String.format(get(lang, key), args);
    }

    // GETTER PENTRU CLIENT/GUI (Foloseste limba selectata pe acel PC)
    public static String get(String key) {
        return get(clientLang, key);
    }

    public static String get(String key, Object... args) {
        return get(clientLang, key, args);
    }

    // Aici se tin toate textele modului. Nu vor suprascrie fisierul daca el exista,
    // doar le vor injecta pe cele care lipsesc dupa un update.
    private static Map<String, String> getDefaults(String lang) {
        Map<String, String> dict = new HashMap<>();

        if (lang.equals("en")) {
            dict.put("msg.plot.occupied", "§c[X] This plot is already occupied!");
            dict.put("msg.plot.bought", "§a[V] You have purchased the plot!");
            dict.put("msg.plot.limit", "§c[X] Maximum plot limit reached! Buy an Upgrade from the map (M).");
            dict.put("msg.plot.abandoned", "§a[V] You abandoned the plot!");
            dict.put("msg.claim.deleted", "§a[V] You successfully deleted the protection: §e%s");
            dict.put("msg.road.reserved", "§c[X] This space is reserved for the road!");
            dict.put("msg.plot.not_plot_mode", "§cThe server is not in Plot mode!");
            dict.put("msg.plot.auto_received", "§aYou automatically received a new plot!");
            dict.put("msg.plot.tp_home", "§aYou teleported to your plot!");
            dict.put("msg.plot.no_plot", "§cYou don't have a plot! Use /plot auto");
            dict.put("msg.buy.success", "§a[V] Extra limit purchased! You can now expand.");
            dict.put("msg.buy.no_money", "§c[!] Not enough money available!");
            dict.put("msg.event.bought", "§a[Protection Event] §e%s bought a discounted slot! Stock left: §c%d");
            dict.put("msg.flag.updated", "§8[§aEvoProtections§8] §fFlag §e%s §fon §b%s §fwas updated: %s");
            dict.put("msg.trust.added", "§8[§aEvoProtections§8] §fPlayer §e%s §fwas added as friend for §b%s");
            dict.put("msg.trust.role_set", "\u00A78[\u00A7aEvoProtections\u00A78] \u00A7fPlayer \u00A7e%s \u00A7fnow has role \u00A7e%s \u00A7ffor \u00A7b%s");
            dict.put("msg.trust.offline", "§c[!] Player is offline or does not exist!");
            dict.put("msg.trust.removed", "§8[§aEvoProtections§8] §fA player was removed from protection §b%s");

            // Iconite
            dict.put("gui.button.friends", "§e[+] Friends");
            dict.put("gui.button.settings", "§b[*] Settings");
            dict.put("gui.button.delete", "§c[x] Delete");
            dict.put("gui.button.buy_plot", "§a+ Buy Upgrade");
            dict.put("gui.button.buy_chunk", "§a+ Buy Limit");
            dict.put("gui.button.lang", "§d[ENG]");

            // Map
            dict.put("gui.map.info", "§a§lLand Information:");
            dict.put("gui.map.limit_plot", "Plots Limit:");
            dict.put("gui.map.limit_chunk", "Chunks Limit:");
            dict.put("gui.map.money", "Your Money: §e%s");
            dict.put("gui.map.price", "Slot Upgrade Price: §c%d Lei");
            dict.put("gui.map.click_info", "§7Click on the map to:");
            dict.put("gui.map.click_plot", "§7Buy a new Plot");
            dict.put("gui.map.click_chunk", "§7Buy selected Chunk");
            dict.put("gui.map.click_delete", "§7Or delete owned land");
            dict.put("gui.map.road", "§c[!] That is a public road!");
            dict.put("gui.map.name_req", "§c[!] Put a name in the box!");
            dict.put("gui.map.owned_other", "§c[!] Land belongs to someone else!");
            dict.put("gui.map.title_plot", "§lEVO PLOTS");
            dict.put("gui.map.title_chunk", "§lEVO PROTECTIONS");
            dict.put("gui.map.plot_name", "Plot Name");
            dict.put("gui.map.chunk_name", "Chunk Name");
            dict.put("gui.status.on", "§aON");
            dict.put("gui.status.off", "§cOFF");
            dict.put("gui.button.back", "<< Back to Map");

            // Trust
            dict.put("gui.trust.title", "§lFRIENDS MANAGEMENT");
            dict.put("gui.trust.title_plot", "§lPLOT FRIENDS");
            dict.put("gui.trust.add", "Add Permission:");
            dict.put("gui.trust.list", "Friends List (Can build/destroy):");
            dict.put("gui.trust.empty", "- You haven't added anyone here -");
            dict.put("gui.trust.btn_add", "§aAdd Friend");
            dict.put("gui.trust.no_plot", "§cYou have no plot created!");
            dict.put("gui.trust.no_claim", "§cYou have no protection created!");
            dict.put("gui.trust.selected_plot", "Selected Plot: §e");
            dict.put("gui.trust.selected_claim", "Selected Protection: §e");
            dict.put("gui.trust.role.coowner", "Coowner");
            dict.put("gui.trust.role.admin", "Admin");
            dict.put("gui.trust.role.friend", "Friend");
            dict.put("gui.trust.role.visitor", "Visitor");

            // Flags
            dict.put("gui.flags.title", "§lPROTECTION SETTINGS (FLAGS)");
            dict.put("gui.flags.desc1", "Modifying Flags for: §b");
            dict.put("gui.flags.desc2", "§7These permissions apply to strangers in your area.");
            dict.put("gui.flags.pvp", "PVP: ");
            dict.put("gui.flags.doors", "Doors / Gates: ");
            dict.put("gui.flags.use", "Useful Blocks: ");
            dict.put("gui.flags.interact", "Villager Interact: ");
            dict.put("gui.flags.pickup", "Item Pickup: ");
            dict.put("gui.flags.carry_on", "Allow Carry On: ");
            dict.put("gui.flags.explosions", "TNT Explosions: ");
            dict.put("gui.flags.chests", "Chests / Forges: ");
            dict.put("gui.flags.public_build", "Public Build: ");
            dict.put("gui.flags.hurt_animals", "Hurt Animals: ");
            dict.put("gui.flags.natural_animals", "Natural Animal Spawn: ");
            dict.put("gui.flags.spawner_animals", "Spawner Animal Spawn: ");
            dict.put("gui.flags.natural_monsters", "Natural Monster Spawn: ");
            dict.put("gui.flags.spawner_monsters", "Spawner Monster Spawn: ");
            dict.put("gui.flags.always_middle_day", "Always Middle Day: ");
            dict.put("gui.flags.always_middle_night", "Always Middle Night: ");
            dict.put("gui.flags.always_shiny", "Always Shiny: ");
            dict.put("gui.flags.always_rain", "Always Rain: ");

            // Delete & Admin
            dict.put("gui.delete.title", "§c§lDELETE A PROTECTION");
            dict.put("gui.delete.empty", "§cYou have no protection to delete!");
            dict.put("gui.admin.title", "§4§lADMIN MAP");
            dict.put("gui.admin.name", "Admin Zone Name");
            dict.put("gui.admin.mode", "§c§lAdministrator Mode");
            dict.put("gui.admin.desc1", "§7Click to protect zones.");
            dict.put("gui.admin.desc2", "§7Overrides any claim.");
            dict.put("msg.lang.no_perm", "§c[!] You need to be an Admin to change the server language!");

        } else {
            // ROMANA - FARA DIACRITICE
            dict.put("msg.plot.occupied", "§c[X] Acest plot este deja ocupat!");
            dict.put("msg.plot.bought", "§a[V] Ai achizitionat plot-ul!");
            dict.put("msg.plot.limit", "§c[X] Ai atins limita maxima de Plot-uri! Cumpara un Upgrade din harta (M).");
            dict.put("msg.plot.abandoned", "§a[V] Ai abandonat plot-ul!");
            dict.put("msg.claim.deleted", "§a[V] Ai sters cu succes protectia: §e%s");
            dict.put("msg.road.reserved", "§c[X] Acest spatiu este rezervat pentru drum!");
            dict.put("msg.plot.not_plot_mode", "§cServerul nu este in modul Plot!");
            dict.put("msg.plot.auto_received", "§aAi primit un plot nou automat!");
            dict.put("msg.plot.tp_home", "§aTe-ai teleportat la plot-ul tau!");
            dict.put("msg.plot.no_plot", "§cNu ai niciun plot! Foloseste /plot auto");
            dict.put("msg.buy.success", "§a[V] Ai cumparat limita extra! Acum te poti extinde.");
            dict.put("msg.buy.no_money", "§c[!] Nu ai destui bani disponibili!");
            dict.put("msg.event.bought", "§a[Eveniment Protectii] §e%s a cumparat un slot la reducere! Stoc ramas: §c%d");
            dict.put("msg.flag.updated", "§8[§aEvoProtectii§8] §fSetarea §e%s §fpe §b%s §fa fost actualizata: %s");
            dict.put("msg.trust.added", "§8[§aEvoProtectii§8] §fJucatorul §e%s §fa fost adaugat la prieteni pentru §b%s");
            dict.put("msg.trust.role_set", "\u00A78[\u00A7aEvoProtectii\u00A78] \u00A7fJucatorul \u00A7e%s \u00A7fare rolul \u00A7e%s \u00A7fpentru \u00A7b%s");
            dict.put("msg.trust.offline", "§c[!] Jucatorul nu este online sau nu exista!");
            dict.put("msg.trust.removed", "§8[§aEvoProtectii§8] §fUn jucator a fost sters de la protectia §b%s");

            // Iconite inlocuite pentru romana
            dict.put("gui.button.friends", "§e[+] Prieteni");
            dict.put("gui.button.settings", "§b[*] Setari");
            dict.put("gui.button.delete", "§c[x] Sterge");
            dict.put("gui.button.buy_plot", "§a+ Cumpara Upgrade");
            dict.put("gui.button.buy_chunk", "§a+ Cumpara Limita");
            dict.put("gui.button.lang", "§d[ROU]");

            // Map
            dict.put("gui.map.info", "§a§lInformatii Teren:");
            dict.put("gui.map.limit_plot", "Limita Ploturi:");
            dict.put("gui.map.limit_chunk", "Limita Chunk-uri:");
            dict.put("gui.map.money", "Banii tai: §e%s Lei");
            dict.put("gui.map.price", "Pret Upgrade Slot: §c%d Lei");
            dict.put("gui.map.click_info", "§7Click pe harta pentru a:");
            dict.put("gui.map.click_plot", "§7Cumpara un Plot nou");
            dict.put("gui.map.click_chunk", "§7Cumpara Chunk-ul selectat");
            dict.put("gui.map.click_delete", "§7Sau sterge un teren detinut");
            dict.put("gui.map.road", "§c[!] Acela este un drum public!");
            dict.put("gui.map.name_req", "§c[!] Pune un nume in casuta pentru teren!");
            dict.put("gui.map.owned_other", "§c[!] Terenul apartine altcuiva!");
            dict.put("gui.map.title_plot", "§lEVO PLOTS");
            dict.put("gui.map.title_chunk", "§lEVO PROTECTII");
            dict.put("gui.map.plot_name", "Nume Plot");
            dict.put("gui.map.chunk_name", "Nume Chunk");
            dict.put("gui.status.on", "§aPORNIT");
            dict.put("gui.status.off", "§cOPRIT");
            dict.put("gui.button.back", "<< Inapoi la Harta");

            // Trust
            dict.put("gui.trust.title", "§lMANAGEMENT PRIETENI");
            dict.put("gui.trust.title_plot", "§lPRIETENI PLOT");
            dict.put("gui.trust.add", "Adauga Permisiune:");
            dict.put("gui.trust.list", "Lista Prieteni (Pot distruge/construi):");
            dict.put("gui.trust.empty", "- Nu ai adaugat pe nimeni aici -");
            dict.put("gui.trust.btn_add", "§aAdauga Prieten");
            dict.put("gui.trust.no_plot", "§cNu ai niciun plot creat!");
            dict.put("gui.trust.no_claim", "§cNu ai nicio protectie creata!");
            dict.put("gui.trust.selected_plot", "Plot Selectat: §e");
            dict.put("gui.trust.selected_claim", "Protectie Selectata: §e");
            dict.put("gui.trust.role.coowner", "Coowner");
            dict.put("gui.trust.role.admin", "Admin");
            dict.put("gui.trust.role.friend", "Prieten");
            dict.put("gui.trust.role.visitor", "Vizitator");

            // Flags
            dict.put("gui.flags.title", "§lSETARI PROTECTIE (FLAGS)");
            dict.put("gui.flags.desc1", "Modifici Flag-urile pentru: §b");
            dict.put("gui.flags.desc2", "§7Aceste permisiuni se aplica strainilor din zona ta.");
            dict.put("gui.flags.pvp", "PVP: ");
            dict.put("gui.flags.doors", "Usi / Porti: ");
            dict.put("gui.flags.use", "Blocuri Utile: ");
            dict.put("gui.flags.interact", "Interactiune Sateni: ");
            dict.put("gui.flags.pickup", "Colectare Iteme: ");
            dict.put("gui.flags.carry_on", "Permite Carry On: ");
            dict.put("gui.flags.explosions", "Explozii TNT: ");
            dict.put("gui.flags.chests", "Cufere / Forje: ");
            dict.put("gui.flags.public_build", "Construire Publica: ");
            dict.put("gui.flags.hurt_animals", "Ucidere Animale: ");
            dict.put("gui.flags.natural_animals", "Spawn Animale Natural: ");
            dict.put("gui.flags.spawner_animals", "Spawn Animale Spawner: ");
            dict.put("gui.flags.natural_monsters", "Spawn Monstri Natural: ");
            dict.put("gui.flags.spawner_monsters", "Spawn Monstri Spawner: ");
            dict.put("gui.flags.always_middle_day", "Mereu Miezul Zilei: ");
            dict.put("gui.flags.always_middle_night", "Mereu Miezul Noptii: ");
            dict.put("gui.flags.always_shiny", "Mereu Senin: ");
            dict.put("gui.flags.always_rain", "Mereu Ploaie: ");

            // Delete & Admin
            dict.put("gui.delete.title", "§c§lSTERGE O PROTECTIE");
            dict.put("gui.delete.empty", "§cNu ai nicio protectie de sters!");
            dict.put("gui.admin.title", "§4§lHARTA ADMIN");
            dict.put("gui.admin.name", "Nume Zona Admin");
            dict.put("gui.admin.mode", "§c§lMod Administrator");
            dict.put("gui.admin.desc1", "§7Click pentru a proteja zone.");
            dict.put("gui.admin.desc2", "§7Suprascrie orice claim.");
            dict.put("msg.lang.no_perm", "§c[!] Trebuie sa fii Admin pentru a schimba limba serverului!");
        }

        return dict;
    }
}
