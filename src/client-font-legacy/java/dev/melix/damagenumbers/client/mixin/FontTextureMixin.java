package dev.melix.damagenumbers.client.mixin;

import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FontTexture.class)
abstract class FontTextureMixin {
    @Shadow @Final private boolean colored;
    @Shadow @Final private GlyphRenderTypes renderTypes;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void damageNumbers$enableSmoothFontSampling(CallbackInfo callback) {
        if (!colored && renderTypes.toString().contains("damage-numbers")) {
            ((FontTexture) (Object) this).setFilter(true, false);
        }
    }
}
