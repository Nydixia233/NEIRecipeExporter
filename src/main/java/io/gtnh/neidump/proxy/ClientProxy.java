package io.gtnh.neidump.proxy;

public class ClientProxy extends CommonProxy {
    @Override
    public void onInit() {
        // Command registration is handled by FMLServerStartingEvent in the main mod
        // class, avoiding the need for ClientCommandHandler (which GTNH transformers
        // may null-out).
    }
}
