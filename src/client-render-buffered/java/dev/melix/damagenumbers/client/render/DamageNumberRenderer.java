package dev.melix.damagenumbers.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.ColorPaint;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.Snapshot;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.SplashAnimation;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.List;

final class DamageNumberRenderer {
    private static final int FULL_BRIGHT = 0x00F000F0;
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
        float progress = number.progress(nowNanos);
        float ageSeconds = number.ageSeconds(nowNanos);
        float alpha = fadeAlpha(ageSeconds, number.remainingSeconds(nowNanos));
        Snapshot style = number.style();
        AnimationTransform animation = animation(style.splashAnimation(), progress, ageSeconds);
        Vec3 cameraPosition = cameraPosition(camera);
        Vec3 position = number.position().add(0.0D, animation.rise(), 0.0D);
        Vec3 towardCamera = cameraPosition.subtract(position);
        double cameraDistance = towardCamera.length();
        alpha *= proximityAlpha(cameraDistance);
        if (alpha <= 0.001F) {
            return;
        }
        if (cameraDistance > 1.0E-5D) {
            position = position.add(towardCamera.scale(0.012D / cameraDistance));
        }

        matrices.pushPose();
        matrices.translate(
                position.x - cameraPosition.x,
                position.y - cameraPosition.y,
                position.z - cameraPosition.z
        );
        matrices.mulPose(camera.rotation());
        float worldScale = style.scale() * number.scaleMultiplier() * animation.scale();
        matrices.scale(LEGACY_X_FLIP ? -worldScale : worldScale, -worldScale, worldScale);

        Font font = Minecraft.getInstance().font;
        Glyph[] glyphs = glyphs(number.text(), style, font);
        float totalWidth = 0.0F;
        for (Glyph glyph : glyphs) {
            totalWidth += glyph.width();
        }

        float startX = -totalWidth / 2.0F;
        float y = -font.lineHeight / 2.0F;
        if (face) {
            MultiBufferSource gradientConsumers = GradientMultiBufferSource.wrap(
                    consumers,
                    style.fill(),
                    style.gradientAngleDegrees(),
                    startX,
                    y,
                    startX + totalWidth,
                    y + font.lineHeight,
                    alpha
            );
            renderFill(glyphs, startX, y, totalWidth, ColorPaint.solid(0xFFFFFFFF), 1.0F,
                    font, matrices, gradientConsumers);
        } else {
            ColorPaint underlay = ColorPaint.solid(style.border().colorAt(0.5F));
            float underlayAlpha = progress <= 0.62F ? alpha : alpha * alpha;
            if (style.borderWidth() > 0.0F) {
                renderBorder(glyphs, startX, y, totalWidth, underlay, style.borderWidth(), underlayAlpha,
                        font, matrices, consumers);
            }
            renderFill(glyphs, startX, y, totalWidth, underlay, underlayAlpha, font, matrices, consumers);
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
                float dx = (float) Math.cos(angle) * radius;
                float dy = (float) Math.sin(angle) * radius;
                renderGlyphRun(glyphs, startX + dx, y + dy, totalWidth, paint, alpha,
                        font, matrices, consumers);
            }
        }
    }

    private static void renderFill(
            Glyph[] glyphs,
            float startX,
            float y,
            float totalWidth,
            ColorPaint paint,
            float alpha,
            Font font,
            PoseStack matrices,
            MultiBufferSource consumers
    ) {
        renderGlyphRun(glyphs, startX, y, totalWidth, paint, alpha, font, matrices, consumers);
    }

    private static void renderGlyphRun(
            Glyph[] glyphs,
            float startX,
            float y,
            float totalWidth,
            ColorPaint paint,
            float alpha,
            Font font,
            PoseStack matrices,
            MultiBufferSource consumers
    ) {
        float x = startX;
        float covered = 0.0F;
        for (Glyph glyph : glyphs) {
            float center = covered + glyph.width() * 0.5F;
            float gradientProgress = totalWidth <= 0.0F ? 0.0F : center / totalWidth;
            int color = multiplyAlpha(paint.colorAt(gradientProgress), alpha);
            // Alpha 0-3 means opaque RGB.
            if ((color >>> 24) >= 4) {
                font.drawInBatch(glyph.component(), x, y, color, false, matrices.last().pose(), consumers,
                        Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
            }
            x += glyph.width();
            covered += glyph.width();
        }
    }

    private static int multiplyAlpha(int argb, float alpha) {
        int sourceAlpha = argb >>> 24;
        int resultAlpha = Math.max(0, Math.min(255, Math.round(sourceAlpha * alpha)));
        return resultAlpha << 24 | argb & 0x00FFFFFF;
    }

    private static float fadeAlpha(float ageSeconds, float remainingSeconds) {
        float fadeIn = smootherStep(ageSeconds / 0.14F);
        float fadeOut = smootherStep(remainingSeconds / 0.38F);
        return fadeIn * fadeOut;
    }

    private static AnimationTransform animation(SplashAnimation animation, float progress, float ageSeconds) {
        float intro = Math.min(1.0F, ageSeconds / 0.22F);
        float smoothIntro = smootherStep(intro);
        float smoothProgress = smootherStep(progress);
        return switch (animation) {
            case POP -> {
                float pulse = (float) Math.sin(Math.PI * smoothIntro);
                yield new AnimationTransform(0.72F + 0.28F * smoothIntro + 0.22F * pulse,
                        0.30F * smoothProgress);
            }
            case BOUNCE -> {
                float bounce = (float) Math.abs(Math.sin(smoothIntro * Math.PI * 1.5D)) * (1.0F - smoothIntro);
                yield new AnimationTransform(0.78F + 0.22F * smoothIntro + 0.16F * bounce,
                        0.24F * smoothProgress);
            }
            case RISE -> new AnimationTransform(1.0F, 0.42F * smoothProgress);
            case NONE -> new AnimationTransform(1.0F, 0.0F);
        };
    }

    private static float proximityAlpha(double cameraDistance) {
        return smootherStep((float) ((cameraDistance - 0.12D) / 0.28D));
    }

    private static float smootherStep(float value) {
        float x = Math.max(0.0F, Math.min(1.0F, value));
        return x * x * x * (x * (x * 6.0F - 15.0F) + 10.0F);
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

    private record AnimationTransform(float scale, float rise) {
    }
}
