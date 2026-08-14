package dev.melix.damagenumbers.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;

public final class WorldRenderHook {
    private static DamageNumberManager manager;

    private WorldRenderHook() {
    }

    public static void register(DamageNumberManager damageNumberManager) {
        manager = damageNumberManager;
    }

    public static void renderFromMixin() {
        Minecraft client = Minecraft.getInstance();
        if (manager != null && client.level != null) {
            manager.render(new PoseStack(), client.gameRenderer.getMainCamera());
        }
    }
}
