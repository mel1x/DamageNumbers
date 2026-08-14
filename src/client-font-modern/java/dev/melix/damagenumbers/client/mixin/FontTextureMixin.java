package dev.melix.damagenumbers.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(FontTexture.class)
abstract class FontTextureMixin extends AbstractTexture {
    @Shadow @Final private boolean colored;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void damageNumbers$enableSmoothFontSampling(Supplier<String> name, GlyphRenderTypes renderTypes,
                                                         boolean coloredTexture, CallbackInfo callback) {
        if (!colored && name.get().contains("damage-numbers")) {
            sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
        }
    }
}
