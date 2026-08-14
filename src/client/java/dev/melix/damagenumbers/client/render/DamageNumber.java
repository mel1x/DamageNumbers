package dev.melix.damagenumbers.client.render;

import dev.melix.damagenumbers.client.config.DamageNumbersConfig;
import net.minecraft.world.phys.Vec3;

record DamageNumber(
        String text,
        Vec3 position,
        long createdAtNanos,
        DamageNumbersConfig.Snapshot style,
        float scaleMultiplier
) {
    boolean isExpired(long nowNanos) {
        return nowNanos - createdAtNanos >= lifetimeNanos();
    }

    float ageSeconds(long nowNanos) {
        return Math.max(0.0F, (nowNanos - createdAtNanos) / 1_000_000_000.0F);
    }

    float remainingSeconds(long nowNanos) {
        return Math.max(0.0F, (lifetimeNanos() - (nowNanos - createdAtNanos)) / 1_000_000_000.0F);
    }

    private long lifetimeNanos() {
        long millis = style.fadeOutTimeMillis();
        return millis > Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : millis * 1_000_000L;
    }
}
