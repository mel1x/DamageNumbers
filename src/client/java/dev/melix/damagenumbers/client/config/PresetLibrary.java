package dev.melix.damagenumbers.client.config;

import dev.melix.damagenumbers.client.config.DamageNumbersConfig.ColorPaint;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.FontChoice;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.Snapshot;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.AppearanceAnimation;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.AppearanceAnimationScope;

import java.util.List;

public final class PresetLibrary {
    private static final long ANIMATION_MILLIS =
            DamageNumbersConfig.DEFAULT_APPEARANCE_ANIMATION_MILLIS;

    private static final List<BuiltInPreset> BUILT_INS = List.of(
            new BuiltInPreset("builtin:default", "damage_numbers.preset.ember",
                    new Snapshot(0.032F, false, 1_250, AppearanceAnimationScope.WHOLE_DAMAGE,
                            AppearanceAnimation.SCALE_IN, ANIMATION_MILLIS, FontChoice.MINECRAFT, null,
                            ColorPaint.gradient(0xFFFFC857, 0xFFE84936), ColorPaint.solid(0xFF160D0A),
                            1.0F, 90.0F, 0.19F, 0.29F)),
            new BuiltInPreset("builtin:glacier", "damage_numbers.preset.glacier",
                    new Snapshot(0.031F, false, 1_050, AppearanceAnimationScope.WHOLE_DAMAGE,
                            AppearanceAnimation.SLIDE_UP, ANIMATION_MILLIS, FontChoice.GEIST, null,
                            ColorPaint.gradient(0xFFCBF5FF, 0xFF4F8FD8), ColorPaint.solid(0xFF08131D),
                            0.75F, 90.0F, 0.15F, 0.23F)),
            new BuiltInPreset("builtin:overdrive", "damage_numbers.preset.overdrive",
                    new Snapshot(0.031F, true, 1_500, AppearanceAnimationScope.PER_DIGIT,
                            AppearanceAnimation.SLAM, ANIMATION_MILLIS, FontChoice.POPPINS, null,
                            ColorPaint.gradient(0xFFFFE36B, 0xFFE84D5B), ColorPaint.solid(0xFF190B0D),
                            1.2F, 90.0F, 0.23F, 0.36F)),
            new BuiltInPreset("builtin:phantom", "damage_numbers.preset.phantom",
                    new Snapshot(0.03F, false, 850, AppearanceAnimationScope.PER_DIGIT,
                            AppearanceAnimation.BLUR_IN, ANIMATION_MILLIS, FontChoice.SANS_SERIF, null,
                            ColorPaint.gradient(0xFFF4F7FF, 0xFF9FB1CA), ColorPaint.solid(0xFF252A32),
                            0.45F, 90.0F, 0.18F, 0.27F)),
            new BuiltInPreset("builtin:rift", "damage_numbers.preset.rift",
                    new Snapshot(0.023F, true, 1_300, AppearanceAnimationScope.PER_DIGIT,
                            AppearanceAnimation.DROP_IN, ANIMATION_MILLIS, FontChoice.STREET, null,
                            ColorPaint.gradient(0xFFFF6A8A, 0xFF65D6C2), ColorPaint.solid(0xFF160E18),
                            1.25F, 90.0F, 0.25F, 0.38F))
    );

    private PresetLibrary() {
    }

    public static List<BuiltInPreset> builtIns() {
        return BUILT_INS;
    }

    public record BuiltInPreset(String id, String translationKey, Snapshot style) {
    }
}
