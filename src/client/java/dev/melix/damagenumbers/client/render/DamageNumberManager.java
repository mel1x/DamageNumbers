package dev.melix.damagenumbers.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class DamageNumberManager {
    private static final int DAMAGE_CONFIRMATION_WINDOW_TICKS = 15;
    private static final float MINIMUM_DAMAGE = 0.005F;
    private static final int MAX_ACTIVE_NUMBERS = 96;
    private static final double ALL_DAMAGE_TRACKING_RADIUS = 128.0D;
    private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);

    private final DamageNumberRenderer renderer = new DamageNumberRenderer();
    private final List<DamageNumber> activeNumbers = new ArrayList<>();
    private final Map<Integer, TargetWatch> watchedTargets = new HashMap<>();
    private final Map<Integer, ObservedHealth> observedHealth = new HashMap<>();
    private long clientTick;

    public void registerDamageTracking() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    public void onClientAttack(Player player, Entity target) {
        Minecraft client = Minecraft.getInstance();
        if (!DamageNumbersConfig.get().isEnabled()
                || player != client.player || !(target instanceof LivingEntity livingEntity)) {
            return;
        }

        Vec3 hitPosition = client.hitResult instanceof EntityHitResult entityHit
                && entityHit.getEntity() == target
                ? entityHit.getLocation()
                : livingEntity.position().add(0.0D, livingEntity.getBbHeight() * 0.65D, 0.0D);
        Vec3 towardPlayer = player.getEyePosition().subtract(hitPosition);
        if (towardPlayer.lengthSqr() > 1.0E-6D) {
            // Avoid z-fighting.
            hitPosition = hitPosition.add(towardPlayer.normalize().scale(0.035D));
        }
        watch(livingEntity, hitPosition, isLikelyCritical(player));
    }

    public void render(PoseStack matrices, Camera camera) {
        render(matrices, camera, null);
    }

    public void render(PoseStack matrices, Camera camera, Object renderContext) {
        if (activeNumbers.isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        activeNumbers.removeIf(number -> number.isExpired(now));
        if (activeNumbers.isEmpty()) {
            return;
        }

        renderer.render(activeNumbers, now, matrices, camera, renderContext);
    }

    public void showDamage(String presetName, Vec3 position, float value) {
        if (!DamageNumbersConfig.get().isEnabled() || !Float.isFinite(value)) {
            return;
        }
        DamageNumbersConfig.Snapshot style = DamageNumbersConfig.get().resolvePreset(presetName);
        spawn(value, position, style, false);
    }

    private void watch(LivingEntity entity, Vec3 hitPosition, boolean critical) {
        TargetWatch watch = watchedTargets.computeIfAbsent(entity.getId(), ignored ->
                new TargetWatch(entity, effectiveHealth(entity)));
        watch.entity = entity;
        watch.hits.addLast(new PendingHit(hitPosition, clientTick + DAMAGE_CONFIRMATION_WINDOW_TICKS, critical));
    }

    private void tick(Minecraft client) {
        clientTick++;
        if (client.level == null || !DamageNumbersConfig.get().isEnabled()) {
            watchedTargets.clear();
            observedHealth.clear();
            activeNumbers.clear();
            return;
        }

        if (DamageNumbersConfig.get().showAllDamageSources()) {
            trackAllDamage(client);
        } else {
            observedHealth.clear();
        }

        Iterator<TargetWatch> watches = watchedTargets.values().iterator();
        while (watches.hasNext()) {
            TargetWatch watch = watches.next();
            while (!watch.hits.isEmpty() && watch.hits.peekFirst().expiresAtTick < clientTick) {
                watch.hits.removeFirst();
            }
            if (watch.hits.isEmpty()) {
                watches.remove();
                continue;
            }

            float currentHealth = effectiveHealth(watch.entity);
            float damage = watch.lastEffectiveHealth - currentHealth;
            if (damage > MINIMUM_DAMAGE) {
                PendingHit hit = watch.hits.removeFirst();
                watch.lastEffectiveHealth = currentHealth;
                if (damage >= DamageNumbersConfig.get().minimumDamage()) {
                    DamageNumbersConfig.Snapshot style = DamageNumbersConfig.get().styleForDamage(damage);
                    Vec3 position = client.player == null ? hit.position
                            : randomizeAboveImpact(client.player, hit.position, style);
                    spawn(damage, position, style, hit.critical);
                }
            } else if (currentHealth > watch.lastEffectiveHealth) {
                // Reset after healing.
                watch.lastEffectiveHealth = currentHealth;
            }
        }
    }

    private void trackAllDamage(Minecraft client) {
        if (client.player == null) {
            observedHealth.clear();
            return;
        }
        List<LivingEntity> entities = client.level.getEntitiesOfClass(LivingEntity.class,
                client.player.getBoundingBox().inflate(ALL_DAMAGE_TRACKING_RADIUS));
        for (LivingEntity entity : entities) {
            float current = effectiveHealth(entity);
            ObservedHealth previous = observedHealth.put(entity.getId(), new ObservedHealth(current, clientTick));
            if (previous == null || watchedTargets.containsKey(entity.getId())) {
                continue;
            }
            float damage = previous.health - current;
            if (damage > MINIMUM_DAMAGE && damage >= DamageNumbersConfig.get().minimumDamage()) {
                DamageNumbersConfig.Snapshot style = DamageNumbersConfig.get().styleForDamage(damage);
                Vec3 position = entity.position().add(0.0D, entity.getBbHeight() * 0.65D, 0.0D);
                Vec3 towardPlayer = client.player.getEyePosition().subtract(position);
                if (towardPlayer.lengthSqr() > 1.0E-6D) {
                    position = position.add(towardPlayer.normalize().scale(0.035D));
                }
                spawn(damage, randomizeAboveImpact(client.player, position, style), style, false);
            }
        }
        observedHealth.entrySet().removeIf(entry -> clientTick - entry.getValue().lastSeenTick > 2L);
    }

    private void spawn(float damage, Vec3 position, DamageNumbersConfig.Snapshot style, boolean critical) {
        while (activeNumbers.size() >= MAX_ACTIVE_NUMBERS) {
            activeNumbers.remove(0);
        }
        activeNumbers.add(new DamageNumber(
                formatDamage(damage),
                position,
                System.nanoTime(),
                style,
                damageScale(style, damage, critical)
        ));
    }

    private static Vec3 randomizeAboveImpact(Player player, Vec3 impactPosition,
                                              DamageNumbersConfig.Snapshot style) {
        Vec3 viewDirection = impactPosition.subtract(player.getEyePosition());
        if (viewDirection.lengthSqr() <= 1.0E-6D) {
            return impactPosition.add(0.0D, style.minimumSpawnRadius(), 0.0D);
        }
        viewDirection = viewDirection.normalize();

        Vec3 right = viewDirection.cross(WORLD_UP);
        if (right.lengthSqr() <= 1.0E-6D) {
            right = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            right = right.normalize();
        }
        Vec3 screenUp = right.cross(viewDirection).normalize();

        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(0.0D, Math.PI);
        double minimumRadius = style.minimumSpawnRadius();
        double maximumRadius = Math.max(minimumRadius, style.maximumSpawnRadius());
        double radius = maximumRadius <= minimumRadius ? minimumRadius
                : random.nextDouble(minimumRadius, maximumRadius);
        return impactPosition
                .add(right.scale(Math.cos(angle) * radius))
                .add(screenUp.scale(Math.sin(angle) * radius));
    }

    private static boolean isLikelyCritical(Player player) {
        return player.fallDistance > 0.0F && !player.onGround() && !player.isInWater() && !player.isPassenger();
    }

    private static float damageScale(DamageNumbersConfig.Snapshot style, float damage, boolean critical) {
        if (!style.scaleWithDamage()) {
            return 1.0F;
        }
        float multiplier = 1.0F + Math.min(0.75F, (float) Math.log1p(Math.max(0.0F, damage)) * 0.18F);
        return critical ? multiplier * 1.15F : multiplier;
    }

    private static float effectiveHealth(LivingEntity entity) {
        return Math.max(0.0F, entity.getHealth()) + Math.max(0.0F, entity.getAbsorptionAmount());
    }

    private static String formatDamage(float damage) {
        float rounded = Math.round(damage);
        if (Math.abs(damage - rounded) < 0.05F) {
            return Integer.toString(Math.max(1, Math.round(damage)));
        }
        return String.format(Locale.ROOT, "%.1f", damage);
    }

    private static final class TargetWatch {
        private LivingEntity entity;
        private float lastEffectiveHealth;
        private final ArrayDeque<PendingHit> hits = new ArrayDeque<>();

        private TargetWatch(LivingEntity entity, float lastEffectiveHealth) {
            this.entity = entity;
            this.lastEffectiveHealth = lastEffectiveHealth;
        }
    }

    private record PendingHit(Vec3 position, long expiresAtTick, boolean critical) {
    }

    private record ObservedHealth(float health, long lastSeenTick) {
    }
}
