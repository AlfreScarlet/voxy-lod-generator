package dev.voxylod.client;

import dev.voxylod.generation.GenerationManager;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

public final class VoxyDebugOverlay {
    private final GenerationManager manager;

    public VoxyDebugOverlay(GenerationManager manager) {
        this.manager = manager;
    }

    public void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        java.util.List<String> lines = this.manager.debugLines();
        int insertion = Math.min(2, event.getLeft().size());
        event.getLeft().addAll(insertion, lines);
        event.getLeft().add(insertion + lines.size(), "");
    }
}
