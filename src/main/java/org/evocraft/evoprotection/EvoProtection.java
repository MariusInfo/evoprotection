package org.evocraft.evoprotection;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import org.evocraft.evoprotection.commands.ClaimCommand;
import org.evocraft.evoprotection.events.ProtectionEvents;
import org.evocraft.evoprotection.item.ModItems;
import org.evocraft.evoprotection.manager.ClaimEnvironmentManager;
import org.evocraft.evoprotection.manager.ClaimManager;
import org.evocraft.evoprotection.network.PacketHandler;
import org.evocraft.evoprotection.manager.ProtectionConfig;
import org.evocraft.evoprotection.manager.LanguageManager;
import org.evocraft.evoprotection.manager.ProtectionRoomManager;

@Mod(EvoProtection.MODID)
public class EvoProtection {
    public static final String MODID = "evoprotection";

    public EvoProtection() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModItems.register(modEventBus);
        modEventBus.addListener((FMLCommonSetupEvent event) -> setup(event));

        MinecraftForge.EVENT_BUS.register(this);

        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> {
            ClaimCommand.register(event.getDispatcher());
        });

        MinecraftForge.EVENT_BUS.register(ProtectionEvents.class);
    }

    private void setup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // MOVED HERE: Ensures configuration and language files
            // are loaded immediately, making them available for the client GUI too.
            ProtectionConfig.load();
            LanguageManager.load();

            PacketHandler.register();
        });
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        ClaimManager.initialize();
        ProtectionRoomManager.initialize();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        ClaimEnvironmentManager.get().clearRuntime(event.getServer());
        if (ClaimManager.get() != null) ClaimManager.get().save();
        if (ProtectionRoomManager.get() != null) ProtectionRoomManager.get().save();
    }
}
