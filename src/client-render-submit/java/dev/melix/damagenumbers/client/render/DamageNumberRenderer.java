package dev.melix.damagenumbers.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.ColorPaint;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.Snapshot;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.SplashAnimation;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.List;

final class DamageNumberRenderer {
    private static final int FULL_BRIGHT = 0x00F000F0;

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
        float progress = number.progress(nowNanos);
        float ageSeconds = number.ageSeconds(nowNanos);
        float alpha = fadeAlpha(ageSeconds, number.remainingSeconds(nowNanos));
        Snapshot style = number.style();
        AnimationTransform animation = animation(style.splashAnimation(), progress, ageSeconds);
        Vec3 cameraPosition = camera.position();
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
        matrices.translate(position.x - cameraPosition.x, position.y - cameraPosition.y,
                position.z - cameraPosition.z);
        matrices.mulPose(camera.rotation());
        float worldScale = style.scale() * number.scaleMultiplier() * animation.scale();
        matrices.scale(worldScale, -worldScale, worldScale);

        Font font = Minecraft.getInstance().font;
        Component component = FontStyleResolver.component(number.text(), style.font(), style.customFontId());
        float totalWidth = font.width(component);
        float startX = -totalWidth / 2.0F;
        float y = -font.lineHeight / 2.0F;
        if (face) {
            submitGradientText(component, startX, y, style.fill(), style.gradientAngleDegrees(), alpha,
                    startX, y, startX + totalWidth, y + font.lineHeight, font, matrices, collector);
        } else {
            int underlay = style.border().colorAt(0.5F);
            float underlayAlpha = progress <= 0.62F ? alpha : alpha * alpha;
            if (style.borderWidth() > 0.0F) {
                int rings = Math.max(1, Math.min(8, (int) Math.ceil(style.borderWidth())));
                int directions = Math.min(32, 8 + (rings - 1) * 4);
                for (int ring = 1; ring <= rings; ring++) {
                    float radius = style.borderWidth() * ring / rings;
                    for (int direction = 0; direction < directions; direction++) {
                        double angle = Math.PI * 2.0D * direction / directions;
                        submitSolidText(component, startX + (float) Math.cos(angle) * radius,
                                y + (float) Math.sin(angle) * radius, underlay, underlayAlpha,
                                matrices, collector);
                    }
                }
            }
            submitSolidText(component, startX, y, underlay, underlayAlpha, matrices, collector);
        }
        matrices.popPose();
    }

    private static void submitGradientText(Component component, float x, float y, ColorPaint paint, float angle,
                                           float alpha, float left, float top, float right, float bottom, Font font,
                                           PoseStack matrices, SubmitNodeCollector collector) {
        if (alpha <= 0.01F) {
            return;
        }
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

    private static void submitSolidText(Component component, float x, float y, int color, float alpha,
                                        PoseStack matrices, SubmitNodeCollector collector) {
        int fadedColor = multiplyAlpha(color, alpha);
        if ((fadedColor >>> 24) < 4) {
            return;
        }
        collector.submitText(matrices, x, y, component.getVisualOrderText(), false,
                Font.DisplayMode.NORMAL, FULL_BRIGHT, fadedColor, 0, 0);
    }

    private static int multiplyAlpha(int argb, float alpha) {
        int resultAlpha = Math.max(0, Math.min(255, Math.round((argb >>> 24) * alpha)));
        return resultAlpha << 24 | argb & 0x00FFFFFF;
    }

    private static float fadeAlpha(float ageSeconds, float remainingSeconds) {
        return smootherStep(ageSeconds / 0.14F) * smootherStep(remainingSeconds / 0.38F);
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

    private record AnimationTransform(float scale, float rise) {
    }
}
