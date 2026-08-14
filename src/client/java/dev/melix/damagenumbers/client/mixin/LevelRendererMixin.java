package dev.melix.damagenumbers.client.mixin;

import dev.melix.damagenumbers.client.render.WorldRenderHook;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "renderLevel", at = @At("TAIL"), require = 0)
    private void damageNumbers$afterRenderLevel(CallbackInfo callbackInfo) {
        WorldRenderHook.renderFromMixin();
    }
}
