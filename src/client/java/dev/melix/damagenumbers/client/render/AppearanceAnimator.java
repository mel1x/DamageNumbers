package dev.melix.damagenumbers.client.render;

import dev.melix.damagenumbers.client.config.DamageNumbersConfig.AppearanceAnimation;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.AppearanceAnimationScope;

/**
 * Entrance-animation math shared by the world renderers and the config screen preview, so the
 * preview always shows exactly the motion the mod draws in game.
 *
 * <p>Offsets and blur radii are in glyph space: one unit is one pixel of unscaled font text, which
 * every caller has already scaled into world or screen units.
 */
public final class AppearanceAnimator {
    /** Delay between consecutive digits in {@link AppearanceAnimationScope#PER_DIGIT}, as a
     *  fraction of the configured entrance duration. */
    private static final float STAGGER_RATIO = 0.25F;
    /** Longest the stagger may push the last digit back, so big numbers still land quickly. */
    private static final float STAGGER_BUDGET_RATIO = 0.85F;
    private static final float FADE_OUT_SECONDS = 0.38F;
    /** Fraction of the entrance after which squash-and-stretch impacts play. */
    private static final float IMPACT_START = 0.55F;
    /** Keeps a zero axis scale from collapsing the matrix during {@code FLIP_IN}. */
    private static final float MINIMUM_AXIS_SCALE = 0.02F;
    private static final float WAVE_RADIANS_PER_SECOND = 5.2F;
    private static final float WAVE_DIGIT_PHASE = 0.75F;
    private static final float WAVE_AMPLITUDE = 1.6F;

    /** Shared no-op transform returned once a glyph has settled, so steady numbers never allocate. */
    private static final Transform SETTLED =
            new Transform(1.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, false);

    private AppearanceAnimator() {
    }

    /**
     * Transforms for every glyph of one damage number, resolved once per frame. Callers that draw
     * the same glyph many times (outline rings, blur ghosts) must reuse these rather than resolving
     * per draw.
     */
    public static Transform[] glyphs(AppearanceAnimation animation, AppearanceAnimationScope scope,
                                     int glyphCount, float ageSeconds, long animationMillis) {
        Transform[] transforms = new Transform[Math.max(0, glyphCount)];
        for (int index = 0; index < transforms.length; index++) {
            transforms[index] = glyph(animation, scope, index, glyphCount, ageSeconds, animationMillis);
        }
        return transforms;
    }

    public static Transform glyph(AppearanceAnimation animation, AppearanceAnimationScope scope,
                                  int glyphIndex, int glyphCount, float ageSeconds, long animationMillis) {
        boolean perDigit = scope == AppearanceAnimationScope.PER_DIGIT;
        float introSeconds = animationMillis / 1_000.0F;
        float start = perDigit ? glyphIndex * stagger(glyphCount, introSeconds) : 0.0F;
        // A zero-length entrance means "skip it", not a division by zero.
        float intro = introSeconds <= 0.0F ? 1.0F : clamp01((ageSeconds - start) / introSeconds);
        // WAVE keeps moving for the whole lifetime, so it can never take the settled fast path.
        if (intro >= 1.0F && animation != AppearanceAnimation.WAVE) {
            return SETTLED;
        }
        boolean anchorWhole = !perDigit;
        return switch (animation) {
            case FADE_IN -> new Transform(1.0F, 1.0F, 0.0F, 0.0F,
                    smootherStep(intro), 0.0F, 0.0F, anchorWhole);
            case SCALE_IN -> {
                float scale = 0.55F + 0.45F * easeOutCubic(intro);
                yield new Transform(scale, scale, 0.0F, 0.0F,
                        easeOutCubic(clamp01(intro * 1.6F)), 0.0F, 0.0F, anchorWhole);
            }
            case POP_IN -> {
                // easeOutBack overshoots past 1.0 and settles back, giving the classic hit punch.
                float scale = 0.35F + 0.65F * easeOutBack(intro);
                yield new Transform(scale, scale, 0.0F, 0.0F,
                        clamp01(intro * 2.4F), 0.0F, 0.0F, anchorWhole);
            }
            case BLUR_IN -> {
                float eased = easeOutCubic(intro);
                float scale = 1.10F - 0.10F * eased;
                yield new Transform(scale, scale, 0.0F, 0.0F,
                        eased, 2.6F * (1.0F - eased), 0.10F * (1.0F - eased), anchorWhole);
            }
            case SLIDE_UP -> {
                float eased = easeOutCubic(intro);
                yield new Transform(1.0F, 1.0F, 6.0F * (1.0F - eased), 0.0F,
                        easeOutCubic(clamp01(intro * 1.5F)), 0.0F, 0.0F, anchorWhole);
            }
            case DROP_IN -> {
                float eased = easeOutCubic(intro);
                float impact = impactPulse(intro);
                yield new Transform(1.0F + 0.18F * impact, 1.0F - 0.18F * impact,
                        -7.5F * (1.0F - eased), 0.0F,
                        clamp01(intro * 2.4F), 0.0F, 0.0F, anchorWhole);
            }
            case SLAM -> {
                float eased = easeOutExpo(intro);
                float scale = 1.0F + 1.7F * (1.0F - eased) - 0.08F * impactPulse(intro);
                yield new Transform(scale, scale, 0.0F, 0.0F, clamp01(intro * 3.2F),
                        2.0F * (1.0F - eased), 0.08F * (1.0F - eased), anchorWhole);
            }
            case SPIN_IN -> {
                float eased = easeOutCubic(intro);
                float scale = 0.42F + 0.58F * eased;
                yield new Transform(scale, scale, 0.0F, -170.0F * (1.0F - eased),
                        easeOutCubic(clamp01(intro * 1.6F)), 0.0F, 0.0F, anchorWhole);
            }
            case FLIP_IN -> {
                float scaleX = Math.max(MINIMUM_AXIS_SCALE, easeOutCubic(intro));
                yield new Transform(scaleX, 1.0F, 0.0F, 0.0F,
                        clamp01(intro * 2.2F), 0.0F, 0.0F, anchorWhole);
            }
            case WAVE -> {
                float eased = easeOutCubic(intro);
                float phase = ageSeconds * WAVE_RADIANS_PER_SECOND
                        - (perDigit ? glyphIndex * WAVE_DIGIT_PHASE : 0.0F);
                float bob = (float) Math.sin(phase) * WAVE_AMPLITUDE * eased;
                yield new Transform(1.0F, 1.0F, bob, 0.0F, eased, 0.0F, 0.0F, anchorWhole);
            }
        };
    }

    public static float fadeOutAlpha(float remainingSeconds) {
        return smootherStep(remainingSeconds / FADE_OUT_SECONDS);
    }

    public static float smootherStep(float value) {
        float x = clamp01(value);
        return x * x * x * (x * (x * 6.0F - 15.0F) + 10.0F);
    }

    /** Shrinks the per-digit delay on long numbers so the last digit always lands on budget. */
    private static float stagger(int glyphCount, float introSeconds) {
        if (glyphCount <= 1) {
            return 0.0F;
        }
        return Math.min(introSeconds * STAGGER_RATIO,
                introSeconds * STAGGER_BUDGET_RATIO / (glyphCount - 1));
    }

    /** Rises and falls once over the tail of the entrance, driving squash-and-stretch landings. */
    private static float impactPulse(float intro) {
        if (intro <= IMPACT_START) {
            return 0.0F;
        }
        return (float) Math.sin(Math.PI * (intro - IMPACT_START) / (1.0F - IMPACT_START));
    }

    private static float easeOutCubic(float value) {
        float inverse = 1.0F - clamp01(value);
        return 1.0F - inverse * inverse * inverse;
    }

    private static float easeOutBack(float value) {
        float overshoot = 1.70158F;
        float x = clamp01(value) - 1.0F;
        return 1.0F + (overshoot + 1.0F) * x * x * x + overshoot * x * x;
    }

    private static float easeOutExpo(float value) {
        float x = clamp01(value);
        return x >= 1.0F ? 1.0F : 1.0F - (float) Math.pow(2.0D, -10.0D * x);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    /**
     * One glyph's animated state. Scale and rotation apply around the glyph centre, or around the
     * whole number's centre when {@link #anchorWholeNumber()} is set.
     */
    public record Transform(
            float scaleX,
            float scaleY,
            float offsetY,
            float rotationDegrees,
            float alpha,
            float blurRadius,
            float blurAlpha,
            boolean anchorWholeNumber
    ) {
        /** True when no matrix work is needed, letting outline rings skip push/pop entirely. */
        public boolean identity() {
            return scaleX == 1.0F && scaleY == 1.0F && offsetY == 0.0F && rotationDegrees == 0.0F;
        }

        /** True when the glyph should also be drawn as offset ghost copies to fake a blur. */
        public boolean blurred() {
            return blurRadius > 0.05F && blurAlpha > 0.001F;
        }
    }
}
