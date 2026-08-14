package dev.melix.damagenumbers.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.ColorPaint;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.Snapshot;
import dev.melix.damagenumbers.client.render.AppearanceAnimator.Transform;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.lang.reflect.Method;
import java.util.List;

final class DamageNumberRenderer {
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final double FACE_DEPTH_OFFSET = 0.001D;
    private static final int BLUR_DIRECTIONS = 8;
    private static final Method CAMERA_POSITION = findCameraPositionMethod();
    private static final boolean LEGACY_X_FLIP = FabricLoader.getInstance()
            .getModContainer("minecraft")
            .map(container -> container.getMetadata().getVersion().getFriendlyString().startsWith("1.20."))
            .orElse(false);

    void render(List<DamageNumber> numbers, long nowNanos, PoseStack matrices, Camera camera, Object context) {
        MultiBufferSource.BufferSource consumers = Minecraft.getInstance().renderBuffers().bufferSource();
        for (DamageNumber number : numbers) {
            renderUnderlay(number, nowNanos, matrices, camera, consumers);
        }
        consumers.endBatch();
        for (DamageNumber number : numbers) {
            renderFace(number, nowNanos, matrices, camera, consumers);
        }
        consumers.endBatch();
    }

    void renderUnderlay(DamageNumber number, long nowNanos, PoseStack matrices, Camera camera,
                        MultiBufferSource consumers) {
        renderPass(number, nowNanos, matrices, camera, consumers, false);
    }

    void renderFace(DamageNumber number, long nowNanos, PoseStack matrices, Camera camera,
                    MultiBufferSource consumers) {
        renderPass(number, nowNanos, matrices, camera, consumers, true);
    }

    private void renderPass(DamageNumber number, long nowNanos, PoseStack matrices, Camera camera,
                            MultiBufferSource consumers, boolean face) {
        float ageSeconds = number.ageSeconds(nowNanos);
        float alpha = AppearanceAnimator.fadeOutAlpha(number.remainingSeconds(nowNanos));
        Snapshot style = number.style();
        Vec3 cameraPosition = cameraPosition(camera);
        Vec3 position = number.position();
        Vec3 towardCamera = cameraPosition.subtract(position);
        double cameraDistance = towardCamera.length();
        alpha *= proximityAlpha(cameraDistance);
        if (alpha <= 0.001F) {
            return;
        }
        if (cameraDistance > 1.0E-5D) {
            double cameraOffset = 0.012D + (face ? FACE_DEPTH_OFFSET : 0.0D);
            position = position.add(towardCamera.scale(cameraOffset / cameraDistance));
        }

        matrices.pushPose();
        matrices.translate(
                position.x - cameraPosition.x,
                position.y - cameraPosition.y,
                position.z - cameraPosition.z
        );
        matrices.mulPose(camera.rotation());
        float worldScale = style.scale() * number.scaleMultiplier();
        matrices.scale(LEGACY_X_FLIP ? -worldScale : worldScale, -worldScale, worldScale);

        Font font = Minecraft.getInstance().font;
        Glyph[] glyphs = glyphs(number.text(), style, font);
        float totalWidth = 0.0F;
        for (Glyph glyph : glyphs) {
            totalWidth += glyph.width();
        }
        // Resolved once per number: the outline draws every glyph up to 256 times.
        Transform[] transforms = AppearanceAnimator.glyphs(style.appearanceAnimation(),
                style.appearanceAnimationScope(), glyphs.length, ageSeconds,
                style.appearanceAnimationMillis());

        float startX = -totalWidth / 2.0F;
        float y = -font.lineHeight / 2.0F;
        if (face) {
            renderFaceGlyphs(glyphs, transforms, startX, y, totalWidth, style, alpha,
                    font, matrices, consumers);
        } else {
            ColorPaint underlay = ColorPaint.solid(style.border().colorAt(0.5F));
            if (style.borderWidth() > 0.0F) {
                renderBorder(glyphs, transforms, startX, y, totalWidth, underlay, style.borderWidth(),
                        alpha, font, matrices, consumers);
            }
            renderGlyphRun(glyphs, transforms, startX, y, totalWidth, underlay, alpha,
                    font, matrices, consumers);
        }
        matrices.popPose();
    }

    private static Glyph[] glyphs(String text, Snapshot style, Font font) {
        int count = text.codePointCount(0, text.length());
        Glyph[] glyphs = new Glyph[count];
        int offset = 0;
        for (int index = 0; index < count; index++) {
            int codePoint = text.codePointAt(offset);
            String value = new String(Character.toChars(codePoint));
            Component component = FontStyleResolver.component(value, style.font(), style.customFontId());
            glyphs[index] = new Glyph(component, font.width(component));
            offset += Character.charCount(codePoint);
        }
        return glyphs;
    }

    private static void renderBorder(
            Glyph[] glyphs,
            Transform[] transforms,
            float startX,
            float y,
            float totalWidth,
            ColorPaint paint,
            float width,
            float alpha,
            Font font,
            PoseStack matrices,
            MultiBufferSource consumers
    ) {
        int rings = Math.max(1, Math.min(8, (int) Math.ceil(width)));
        int directions = Math.min(32, 8 + (rings - 1) * 4);
        for (int ring = 1; ring <= rings; ring++) {
            float radius = width * ring / rings;
            for (int direction = 0; direction < directions; direction++) {
                double angle = Math.PI * 2.0D * direction / directions;
                // Offsetting the whole run keeps the ring a rigid copy of the animated glyphs.
                matrices.pushPose();
                matrices.translate((float) Math.cos(angle) * radius, (float) Math.sin(angle) * radius,
                        0.0F);
                renderGlyphRun(glyphs, transforms, startX, y, totalWidth, paint, alpha,
                        font, matrices, consumers);
                matrices.popPose();
            }
        }
    }

    private static void renderFaceGlyphs(
            Glyph[] glyphs,
            Transform[] transforms,
            float startX,
            float y,
            float totalWidth,
            Snapshot style,
            float baseAlpha,
            Font font,
            PoseStack matrices,
            MultiBufferSource consumers
    ) {
        float x = startX;
        MultiBufferSource fillConsumers = null;
        float fillAlpha = -1.0F;
        for (int index = 0; index < glyphs.length; index++) {
            Glyph glyph = glyphs[index];
            Transform transform = transforms[index];
            if (transform.blurred()) {
                renderBlurGhosts(glyph, transform, x, y, startX, totalWidth, style,
                        baseAlpha * transform.blurAlpha(), font, matrices, consumers);
            }
            float glyphAlpha = baseAlpha * transform.alpha();
            if (glyphAlpha > 0.001F) {
                if (glyphAlpha != fillAlpha) {
                    fillConsumers = gradient(consumers, style, startX, y, totalWidth, font, glyphAlpha);
                    fillAlpha = glyphAlpha;
                }
                renderGlyph(glyph, x, y, 0xFFFFFFFF, transform, font, matrices, fillConsumers);
            }
            x += glyph.width();
        }
    }

    /** Fakes a blur by ringing the glyph with faint offset copies that tighten as it resolves. */
    private static void renderBlurGhosts(Glyph glyph, Transform transform, float x, float y, float startX,
                                         float totalWidth, Snapshot style, float alpha, Font font,
                                         PoseStack matrices, MultiBufferSource consumers) {
        if (alpha <= 0.001F) {
            return;
        }
        MultiBufferSource ghostConsumers = gradient(consumers, style, startX, y, totalWidth, font, alpha);
        for (int direction = 0; direction < BLUR_DIRECTIONS; direction++) {
            double angle = Math.PI * 2.0D * direction / BLUR_DIRECTIONS;
            matrices.pushPose();
            matrices.translate((float) Math.cos(angle) * transform.blurRadius(),
                    (float) Math.sin(angle) * transform.blurRadius(), 0.0F);
            renderGlyph(glyph, x, y, 0xFFFFFFFF, transform, font, matrices, ghostConsumers);
            matrices.popPose();
        }
    }

    private static MultiBufferSource gradient(MultiBufferSource consumers, Snapshot style, float startX,
                                              float top, float totalWidth, Font font, float alpha) {
        return GradientMultiBufferSource.wrap(consumers, style.fill(), style.gradientAngleDegrees(),
                startX, top, startX + totalWidth, top + font.lineHeight, alpha);
    }

    private static void renderGlyphRun(
            Glyph[] glyphs,
            Transform[] transforms,
            float startX,
            float y,
            float totalWidth,
            ColorPaint paint,
            float baseAlpha,
            Font font,
            PoseStack matrices,
            MultiBufferSource consumers
    ) {
        float x = startX;
        float covered = 0.0F;
        for (int index = 0; index < glyphs.length; index++) {
            Glyph glyph = glyphs[index];
            Transform transform = transforms[index];
            float center = covered + glyph.width() * 0.5F;
            float gradientProgress = totalWidth <= 0.0F ? 0.0F : center / totalWidth;
            int color = multiplyAlpha(paint.colorAt(gradientProgress), baseAlpha * transform.alpha());
            renderGlyph(glyph, x, y, color, transform, font, matrices, consumers);
            x += glyph.width();
            covered += glyph.width();
        }
    }

    private static void renderGlyph(Glyph glyph, float x, float y, int color, Transform transform,
                                    Font font, PoseStack matrices, MultiBufferSource consumers) {
        // Alpha 0-3 means opaque RGB in the font renderer, so skip those colors entirely.
        if ((color >>> 24) < 4) {
            return;
        }
        if (transform.identity()) {
            drawGlyph(glyph, x, y, color, font, matrices, consumers);
            return;
        }
        float anchorX = transform.anchorWholeNumber() ? 0.0F : x + glyph.width() * 0.5F;
        float anchorY = y + font.lineHeight * 0.5F;
        matrices.pushPose();
        matrices.translate(anchorX, anchorY + transform.offsetY(), 0.0F);
        if (transform.rotationDegrees() != 0.0F) {
            matrices.mulPose(new Quaternionf()
                    .rotationZ((float) Math.toRadians(transform.rotationDegrees())));
        }
        matrices.scale(transform.scaleX(), transform.scaleY(), 1.0F);
        matrices.translate(-anchorX, -anchorY, 0.0F);
        drawGlyph(glyph, x, y, color, font, matrices, consumers);
        matrices.popPose();
    }

    private static void drawGlyph(Glyph glyph, float x, float y, int color, Font font, PoseStack matrices,
                                  MultiBufferSource consumers) {
        font.drawInBatch(glyph.component(), x, y, color, false, matrices.last().pose(), consumers,
                Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
    }

    private static int multiplyAlpha(int argb, float alpha) {
        int sourceAlpha = argb >>> 24;
        int resultAlpha = Math.max(0, Math.min(255, Math.round(sourceAlpha * alpha)));
        return resultAlpha << 24 | argb & 0x00FFFFFF;
    }

    private static float proximityAlpha(double cameraDistance) {
        return AppearanceAnimator.smootherStep((float) ((cameraDistance - 0.12D) / 0.28D));
    }

    private static Method findCameraPositionMethod() {
        for (String name : new String[]{"position", "getPosition"}) {
            try {
                return Camera.class.getMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new IllegalStateException("Unsupported Minecraft Camera API");
    }

    private static Vec3 cameraPosition(Camera camera) {
        try {
            return (Vec3) CAMERA_POSITION.invoke(camera);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot read camera position", exception);
        }
    }

    private record Glyph(Component component, int width) {
    }
}
