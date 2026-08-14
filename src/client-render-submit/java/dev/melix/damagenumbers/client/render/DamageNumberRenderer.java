package dev.melix.damagenumbers.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.ColorPaint;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.Snapshot;
import dev.melix.damagenumbers.client.render.AppearanceAnimator.Transform;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.List;

final class DamageNumberRenderer {
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final double FACE_DEPTH_OFFSET = 0.001D;
    private static final int BLUR_DIRECTIONS = 8;

    void render(List<DamageNumber> numbers, long nowNanos, PoseStack matrices, Camera camera, Object context) {
        SubmitNodeCollector collector = (SubmitNodeCollector) context;
        for (DamageNumber number : numbers) {
            renderPass(number, nowNanos, matrices, camera, collector, false);
        }
        for (DamageNumber number : numbers) {
            renderPass(number, nowNanos, matrices, camera, collector, true);
        }
    }

    private void renderPass(DamageNumber number, long nowNanos, PoseStack matrices, Camera camera,
                            SubmitNodeCollector collector, boolean face) {
        float ageSeconds = number.ageSeconds(nowNanos);
        float alpha = AppearanceAnimator.fadeOutAlpha(number.remainingSeconds(nowNanos));
        Snapshot style = number.style();
        Vec3 cameraPosition = camera.position();
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
        matrices.translate(position.x - cameraPosition.x, position.y - cameraPosition.y,
                position.z - cameraPosition.z);
        matrices.mulPose(camera.rotation());
        float worldScale = style.scale() * number.scaleMultiplier();
        matrices.scale(worldScale, -worldScale, worldScale);

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
                    font, matrices, collector);
        } else {
            renderUnderlayGlyphs(glyphs, transforms, startX, y, style, alpha, font, matrices, collector);
        }
        matrices.popPose();
    }

    private static Glyph[] glyphs(String text, Snapshot style, Font font) {
        int count = text.codePointCount(0, text.length());
        Glyph[] glyphs = new Glyph[count];
        int offset = 0;
        for (int index = 0; index < count; index++) {
            int codePoint = text.codePointAt(offset);
            Component component = FontStyleResolver.component(new String(Character.toChars(codePoint)),
                    style.font(), style.customFontId());
            glyphs[index] = new Glyph(component, font.width(component));
            offset += Character.charCount(codePoint);
        }
        return glyphs;
    }

    private static void renderFaceGlyphs(Glyph[] glyphs, Transform[] transforms, float startX, float y,
                                         float totalWidth, Snapshot style, float baseAlpha, Font font,
                                         PoseStack matrices, SubmitNodeCollector collector) {
        float x = startX;
        for (int index = 0; index < glyphs.length; index++) {
            Glyph glyph = glyphs[index];
            Transform transform = transforms[index];
            if (transform.blurred()) {
                renderBlurGhosts(glyph, transform, x, y, startX, totalWidth, style,
                        baseAlpha * transform.blurAlpha(), font, matrices, collector);
            }
            submitGradientGlyph(glyph, x, y, startX, y, totalWidth, style,
                    baseAlpha * transform.alpha(), transform, font, matrices, collector);
            x += glyph.width();
        }
    }

    /** Fakes a blur by ringing the glyph with faint offset copies that tighten as it resolves. */
    private static void renderBlurGhosts(Glyph glyph, Transform transform, float x, float y, float startX,
                                         float totalWidth, Snapshot style, float alpha, Font font,
                                         PoseStack matrices, SubmitNodeCollector collector) {
        if (alpha <= 0.001F) {
            return;
        }
        for (int direction = 0; direction < BLUR_DIRECTIONS; direction++) {
            double angle = Math.PI * 2.0D * direction / BLUR_DIRECTIONS;
            matrices.pushPose();
            matrices.translate((float) Math.cos(angle) * transform.blurRadius(),
                    (float) Math.sin(angle) * transform.blurRadius(), 0.0F);
            submitGradientGlyph(glyph, x, y, startX, y, totalWidth, style, alpha, transform,
                    font, matrices, collector);
            matrices.popPose();
        }
    }

    private static void renderUnderlayGlyphs(Glyph[] glyphs, Transform[] transforms, float startX, float y,
                                             Snapshot style, float baseAlpha, Font font, PoseStack matrices,
                                             SubmitNodeCollector collector) {
        int underlay = style.border().colorAt(0.5F);
        float borderWidth = style.borderWidth();
        int rings = Math.max(1, Math.min(8, (int) Math.ceil(borderWidth)));
        int directions = Math.min(32, 8 + (rings - 1) * 4);
        float x = startX;
        for (int index = 0; index < glyphs.length; index++) {
            Glyph glyph = glyphs[index];
            Transform transform = transforms[index];
            float alpha = baseAlpha * transform.alpha();
            if (borderWidth > 0.0F) {
                for (int ring = 1; ring <= rings; ring++) {
                    float radius = borderWidth * ring / rings;
                    for (int direction = 0; direction < directions; direction++) {
                        double angle = Math.PI * 2.0D * direction / directions;
                        // Offsetting outside the glyph transform keeps the ring a rigid copy.
                        matrices.pushPose();
                        matrices.translate((float) Math.cos(angle) * radius,
                                (float) Math.sin(angle) * radius, 0.0F);
                        submitSolidGlyph(glyph, x, y, underlay, alpha, transform, font, matrices,
                                collector);
                        matrices.popPose();
                    }
                }
            }
            submitSolidGlyph(glyph, x, y, underlay, alpha, transform, font, matrices, collector);
            x += glyph.width();
        }
    }

    private static void submitGradientGlyph(Glyph glyph, float x, float y, float left, float top,
                                            float totalWidth, Snapshot style, float alpha,
                                            Transform transform, Font font, PoseStack matrices,
                                            SubmitNodeCollector collector) {
        if (alpha <= 0.01F) {
            return;
        }
        boolean transformed = pushGlyphTransform(matrices, x, y, glyph, font, transform);
        submitGradientText(glyph.component(), x, y, style.fill(), style.gradientAngleDegrees(), alpha,
                left, top, left + totalWidth, top + font.lineHeight, font, matrices, collector);
        if (transformed) {
            matrices.popPose();
        }
    }

    private static void submitSolidGlyph(Glyph glyph, float x, float y, int color, float alpha,
                                         Transform transform, Font font, PoseStack matrices,
                                         SubmitNodeCollector collector) {
        int fadedColor = multiplyAlpha(color, alpha);
        if ((fadedColor >>> 24) < 4) {
            return;
        }
        boolean transformed = pushGlyphTransform(matrices, x, y, glyph, font, transform);
        collector.submitText(matrices, x, y, glyph.component().getVisualOrderText(), false,
                Font.DisplayMode.NORMAL, FULL_BRIGHT, fadedColor, 0, 0);
        if (transformed) {
            matrices.popPose();
        }
    }

    /** Returns whether a pose was pushed, so settled glyphs skip the matrix stack entirely. */
    private static boolean pushGlyphTransform(PoseStack matrices, float x, float y, Glyph glyph, Font font,
                                              Transform transform) {
        if (transform.identity()) {
            return false;
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
        return true;
    }

    private static void submitGradientText(Component component, float x, float y, ColorPaint paint, float angle,
                                           float alpha, float left, float top, float right, float bottom, Font font,
                                           PoseStack matrices, SubmitNodeCollector collector) {
        Font.PreparedText prepared = font.prepareText(component.getVisualOrderText(), x, y,
                0xFFFFFFFF, false, false, 0);
        prepared.visit(new Font.GlyphVisitor() {
            @Override
            public void acceptRenderable(TextRenderable renderable) {
                collector.submitCustomGeometry(matrices, renderable.renderType(Font.DisplayMode.NORMAL),
                        (pose, consumer) -> renderable.render(pose.pose(), GradientMultiBufferSource.wrap(
                                consumer, paint, angle, left, top, right, bottom, alpha), FULL_BRIGHT, false));
            }
        });
    }

    private static int multiplyAlpha(int argb, float alpha) {
        int resultAlpha = Math.max(0, Math.min(255, Math.round((argb >>> 24) * alpha)));
        return resultAlpha << 24 | argb & 0x00FFFFFF;
    }

    private static float proximityAlpha(double cameraDistance) {
        return AppearanceAnimator.smootherStep((float) ((cameraDistance - 0.12D) / 0.28D));
    }

    private record Glyph(Component component, int width) {
    }
}
