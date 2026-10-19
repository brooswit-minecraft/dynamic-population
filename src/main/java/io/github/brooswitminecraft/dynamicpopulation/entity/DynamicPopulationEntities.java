package io.github.brooswitminecraft.dynamicpopulation.entity;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * Entity registration stays server-safe; a client renderer may reuse only nitwit/vanilla visual assets.
 *
 * <p>MINECRAFT-390: adds the three King entity types. Ordinary Zombie/Skeleton fillers reuse vanilla
 * {@code EntityType.ZOMBIE}/{@code EntityType.SKELETON} directly (per ticket scope item 3) and so need no
 * registration here at all -- only the three Kings (new, distinct entity types) and Fillager are this mod's
 * own.
 */
public final class DynamicPopulationEntities {
    private DynamicPopulationEntities() { }

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE,
        DynamicPopulationMod.MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<Fillager>> FILLAGER = ENTITIES.register("fillager",
        () -> Fillager.typeBuilder().build("fillager"));
    public static final DeferredHolder<EntityType<?>, EntityType<VillagerKingEntity>> VILLAGER_KING = ENTITIES.register(
        "villager_king", () -> VillagerKingEntity.typeBuilder().build("villager_king"));
    public static final DeferredHolder<EntityType<?>, EntityType<ZombieKingEntity>> ZOMBIE_KING = ENTITIES.register(
        "zombie_king", () -> ZombieKingEntity.typeBuilder().build("zombie_king"));
    public static final DeferredHolder<EntityType<?>, EntityType<SkeletonKingEntity>> SKELETON_KING = ENTITIES.register(
        "skeleton_king", () -> SkeletonKingEntity.typeBuilder().build("skeleton_king"));

    /** Visual scale (vanilla {@code generic.scale} attribute) all three Kings share -- see each King's own class doc. */
    static final double KING_VISUAL_SCALE = 1.5D;

    public static void register(IEventBus modEventBus) {
        ENTITIES.register(modEventBus);
        modEventBus.addListener(DynamicPopulationEntities::createAttributes);
    }

    // Review note: Fillager now carries a MeleeAttackGoal (MINECRAFT-390's population-combat exception --
    // see Fillager.registerGoals), so it needs a real ATTACK_DAMAGE attribute; Mob.createMobAttributes()
    // alone defaults it to vanilla's near-zero base value, which would make the goal functionally toothless.
    // 2.0 matches a bare-handed player's own base attack damage -- a Fillager has no weapon/equipment
    // concept, so "unarmed human-equivalent" is the closest reasonable baseline; no specific number was
    // mandated by the ticket.
    //
    // Package-visible (not private) and separated into one method per entity type, rather than inlined
    // directly into createAttributes, so each King's attribute set reads as one reviewable, self-contained
    // unit (the review fix below -- P2, restoring SCALE alongside the new ATTACK_DAMAGE -- is exactly the
    // kind of silent regression that's easy to miss in a long inlined chain). A plain JUnit test CANNOT
    // exercise these directly despite that: merely referencing net.minecraft.world.entity.ai.attributes.
    // Attributes (as every one of these methods does) touches BuiltInRegistries, which throws
    // "Not bootstrapped" outside a running game/Bootstrap.bootStrap() call -- the same constraint
    // KingStorageMigrationTest's class javadoc documents for DeferredRegister-touching classes. Verified by
    // code review of this diff plus a clean `./gradlew compileJava`/`test` run, not by a dedicated unit test.
    static AttributeSupplier.Builder fillagerAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.5D)
            .add(Attributes.ATTACK_DAMAGE, 2.0D)
            .add(Attributes.FOLLOW_RANGE, 16.0D);
    }

    // Kings: see each King entity class's own javadoc for the stat rationale. 1.5x-ish bigger/tougher than
    // the faction's ordinary filler/vanilla baseline across the board, deliberately not tied to any
    // ticket-mandated number (none was given).
    static AttributeSupplier.Builder villagerKingAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 60.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.35D)
            .add(Attributes.SCALE, KING_VISUAL_SCALE)
            // MINECRAFT-416: a real ATTACK_DAMAGE attribute -- this King now carries a MeleeAttackGoal (see
            // VillagerKingEntity#registerGoals), so Mob.createMobAttributes()'s near-zero default would make
            // that goal functionally toothless, same reasoning fillagerAttributes()'s own ATTACK_DAMAGE
            // addition above documents. 6.0 is between Fillager's unarmed-equivalent 2.0 and
            // ZombieKingEntity's 7.0; no specific number was mandated by the ticket.
            // Review fix (P2): this line previously REPLACED the pre-existing SCALE attribute above instead
            // of being added alongside it, silently shrinking the Villager King back to unscaled vanilla
            // size -- the ticket requires preserving existing sizes. SCALE is restored.
            .add(Attributes.ATTACK_DAMAGE, 6.0D)
            .add(Attributes.FOLLOW_RANGE, 16.0D);
    }

    static AttributeSupplier.Builder zombieKingAttributes() {
        return Zombie.createAttributes()
            .add(Attributes.MAX_HEALTH, 100.0D)
            .add(Attributes.ATTACK_DAMAGE, 7.0D)
            .add(Attributes.SCALE, KING_VISUAL_SCALE)
            .add(Attributes.MOVEMENT_SPEED, 0.23D);
    }

    static AttributeSupplier.Builder skeletonKingAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 80.0D)
            .add(Attributes.ATTACK_DAMAGE, 4.0D)
            .add(Attributes.SCALE, KING_VISUAL_SCALE)
            .add(Attributes.MOVEMENT_SPEED, 0.25D)
            .add(Attributes.FOLLOW_RANGE, 16.0D);
    }

    private static void createAttributes(EntityAttributeCreationEvent event) {
        event.put(FILLAGER.get(), fillagerAttributes().build());
        event.put(VILLAGER_KING.get(), villagerKingAttributes().build());
        event.put(ZOMBIE_KING.get(), zombieKingAttributes().build());
        event.put(SKELETON_KING.get(), skeletonKingAttributes().build());
    }
}
