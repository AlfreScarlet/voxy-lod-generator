package dev.voxylod;

import com.mojang.logging.LogUtils;
import dev.voxylod.client.VoxyDebugOverlay;
import dev.voxylod.command.VoxyLodCommand;
import dev.voxylod.generation.GenerationManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(value = VoxyLodMod.MOD_ID, dist = Dist.CLIENT)
public final class VoxyLodMod {
    public static final String MOD_ID = "voxylod";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final GenerationManager generationManager;

    public VoxyLodMod(IEventBus modBus) {
        GeneratorSettings settings = GeneratorSettings.load();
        this.generationManager = new GenerationManager(settings);
        VoxyLodCommand command = new VoxyLodCommand(this.generationManager);
        VoxyDebugOverlay debugOverlay = new VoxyDebugOverlay(this.generationManager);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> command.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> this.generationManager.serverStarted(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> this.generationManager.tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> this.generationManager.serverStopping(event.getServer()));
        NeoForge.EVENT_BUS.addListener(CustomizeGuiOverlayEvent.DebugText.class, debugOverlay::onDebugText);
    }
}
