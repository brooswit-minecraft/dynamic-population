package io.github.brooswitminecraft.dynamicpopulation.combat;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import io.github.brooswitminecraft.dynamicpopulation.entity.Fillager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * Review fix (P2, round 6, smoke-test harness finding #1): {@code scripts/smoke-server.py}'s
 * attacker-persistence regression check previously excused ANY observed damage on its target as long as
 * some non-test Fillager existed ANYWHERE at that moment -- mere correlation (a straggler happened to be
 * present), never actual attribution of a specific hit to a specific attacker. Re-review correctly called
 * this out: it does not prove the damage came from the straggler rather than the marked-persistent attacker
 * under test.
 *
 * <p>This class closes that gap by logging, for every damage instance landed on a population-combat-
 * eligible filler (a {@link Fillager}, or a vanilla Zombie/Skeleton carrying {@link FillerMarkers}'s filler
 * tag), the ATTACKER's own identity -- type, UUID, and scoreboard tags -- to the server log in a single,
 * greppable line. A script driving a real server (no mock/mixin needed) can match the attacker's tags
 * against the exact tag it gave the one entity under test, which is real, specific attribution, not an
 * inference from "something else happened to exist nearby at the same time."
 *
 * <p>This is deliberately scoped to filler-tagged victims only (not every damage event in the game) to keep
 * log volume bounded to what this mod's own population-combat system can plausibly be responsible for.
 */
public final class CombatDamageLogging {
    private CombatDamageLogging() { }

    private static boolean isFillerVictim(Entity entity) {
        if (entity instanceof Fillager) {
            return true;
        }
        if (entity instanceof Zombie zombie) {
            return zombie.getTags().contains(FillerMarkers.ZOMBIE_FILLER_TAG);
        }
        if (entity instanceof Skeleton skeleton) {
            return skeleton.getTags().contains(FillerMarkers.SKELETON_FILLER_TAG);
        }
        return false;
    }

    @SubscribeEvent
    public static void onLivingDamagePost(LivingDamageEvent.Post event) {
        if (!isFillerVictim(event.getEntity())) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        String attackerDescriptor;
        if (attacker == null) {
            attackerDescriptor = "none(source=" + event.getSource().getMsgId() + ")";
        } else {
            String tags = attacker instanceof Mob mob && !mob.getTags().isEmpty()
                ? String.join(",", mob.getTags())
                : (!attacker.getTags().isEmpty() ? String.join(",", attacker.getTags()) : "<no-tags>");
            attackerDescriptor = attacker.getType().toShortString() + " uuid=" + attacker.getUUID() + " tags=" + tags;
        }
        DynamicPopulationMod.LOGGER.info(
            "DPOP_COMBAT_HIT victim_type={} victim_uuid={} victim_tags={} attacker=[{}] damage={}",
            event.getEntity().getType().toShortString(), event.getEntity().getUUID(),
            event.getEntity().getTags().isEmpty() ? "<no-tags>" : String.join(",", event.getEntity().getTags()),
            attackerDescriptor, event.getNewDamage());
    }
}
