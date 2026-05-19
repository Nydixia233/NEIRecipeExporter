package io.gtnh.neidump;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import io.gtnh.neidump.command.CommandDumpNEIRecipes;
import io.gtnh.neidump.proxy.CommonProxy;

@Mod(
        modid = NEIRecipeDumpMod.MOD_ID,
        name = NEIRecipeDumpMod.MOD_NAME,
        version = NEIRecipeDumpMod.VERSION,
        acceptedMinecraftVersions = "[1.7.10]"
)
public class NEIRecipeDumpMod {
    public static final String MOD_ID = "neirecipeexporter";
    public static final String MOD_NAME = "NEI Recipe Exporter";
    public static final String VERSION = "1.0.0";

    @SidedProxy(
            clientSide = "io.gtnh.neidump.proxy.ClientProxy",
            serverSide = "io.gtnh.neidump.proxy.CommonProxy"
    )
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.onInit();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandDumpNEIRecipes());
    }
}
