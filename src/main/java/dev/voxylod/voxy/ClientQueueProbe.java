package dev.voxylod.voxy;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class ClientQueueProbe {
    private static Method minecraftInstance;
    private static Field levelRenderer;
    private static Method getRenderSystem;
    private static Field renderGeneration;
    private static Method renderTaskCount;

    private ClientQueueProbe() {
    }

    static synchronized void resolve() {
        if (renderTaskCount != null) {
            return;
        }
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                loader = ClientQueueProbe.class.getClassLoader();
            }
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Class<?> renderSystemBridge = Class.forName("me.cortex.voxy.client.core.IGetVoxyRenderSystem", false, loader);
            Class<?> renderSystem = Class.forName("me.cortex.voxy.client.core.VoxyRenderSystem", false, loader);
            Class<?> renderGeneration = Class.forName(
                    "me.cortex.voxy.client.core.rendering.building.RenderGenerationService", false, loader);
            minecraftInstance = minecraft.getMethod("getInstance");
            levelRenderer = minecraft.getField("levelRenderer");
            getRenderSystem = renderSystemBridge.getMethod("voxy$getRenderSystem");
            ClientQueueProbe.renderGeneration = renderSystem.getDeclaredField("renderGen");
            ClientQueueProbe.renderGeneration.setAccessible(true);
            renderTaskCount = renderGeneration.getMethod("getTaskCount");
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Voxy renderer queue API is unavailable", exception);
        }
    }

    static int renderTasks() {
        resolve();
        try {
            Object minecraft = minecraftInstance.invoke(null);
            Object levelRenderer = ClientQueueProbe.levelRenderer.get(minecraft);
            if (levelRenderer == null) {
                return -1;
            }
            Object renderSystem = getRenderSystem.invoke(levelRenderer);
            if (renderSystem == null) {
                return -1;
            }
            Object renderGeneration = ClientQueueProbe.renderGeneration.get(renderSystem);
            return renderGeneration == null ? -1 : (int) renderTaskCount.invoke(renderGeneration);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to inspect Voxy renderer queue", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Unable to inspect Voxy renderer queue", exception.getCause());
        }
    }
}
