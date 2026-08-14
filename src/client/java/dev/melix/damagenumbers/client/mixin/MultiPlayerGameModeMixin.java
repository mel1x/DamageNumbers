package dev.melix.damagenumbers.client.mixin;

import dev.melix.damagenumbers.client.DamageNumbersClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Inject(method = "attack", at = @At("HEAD"), require = 1)
    private void damageNumbers$onAttack(Player player, Entity target, CallbackInfo callbackInfo) {
        DamageNumbersClient.onClientAttack(player, target);
    }
}
