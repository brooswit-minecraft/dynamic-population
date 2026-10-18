package io.github.brooswitminecraft.dynamicpopulation.propagation;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.github.brooswitminecraft.dynamicpopulation.entity.Fillager;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Debug commands (op level 2): look at the field, put population in, step it, clear it.
 *
 * <p>MINECRAFT-390: {@code set}/{@code show} gained an optional leading faction literal
 * ({@code villager|zombie|skeleton}) so a test/operator can directly seed any ONE faction's field at a
 * position -- bare {@code /dpop set <ppt>} is unchanged (still Villager), matching the same
 * backward-compatible pattern {@code KingCommands}' own {@code settle [faction]} already uses. This is the
 * same existing debug-seeding mechanism the ticket itself documents as the sole direct-seed test path
 * (`docs/propagation.md`), now faction-aware -- not a new spawning mechanism.
 *
 * <p>MINECRAFT-390 acceptance-evidence follow-up: also adds {@code fillager promote <tag>}, a debug-only
 * command used exclusively by {@code scripts/smoke-server.py} to test-exercise promotion mid-panic without
 * a connected player or a real emerald stack -- see {@link Fillager#forcePromoteForTesting} for why no
 * production gameplay path needs (or should use) this.
 */
public final class PopulationCommands {
    private PopulationCommands() { }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dpop").requires(s -> s.hasPermission(2))
            .then(Commands.literal("show").executes(c -> show(c, Faction.VILLAGER))
                .then(Commands.literal("villager").executes(c -> show(c, Faction.VILLAGER)))
                .then(Commands.literal("zombie").executes(c -> show(c, Faction.ZOMBIE)))
                .then(Commands.literal("skeleton").executes(c -> show(c, Faction.SKELETON))))
            .then(Commands.literal("field").executes(PopulationCommands::summary))
            .then(Commands.literal("set")
                .then(Commands.argument("ppt", IntegerArgumentType.integer(0, Propagation.CAPACITY))
                    .executes(c -> set(c, Faction.VILLAGER, IntegerArgumentType.getInteger(c, "ppt"))))
                .then(Commands.literal("villager").then(Commands.argument("ppt", IntegerArgumentType.integer(0, Propagation.CAPACITY))
                    .executes(c -> set(c, Faction.VILLAGER, IntegerArgumentType.getInteger(c, "ppt")))))
                .then(Commands.literal("zombie").then(Commands.argument("ppt", IntegerArgumentType.integer(0, Propagation.CAPACITY))
                    .executes(c -> set(c, Faction.ZOMBIE, IntegerArgumentType.getInteger(c, "ppt")))))
                .then(Commands.literal("skeleton").then(Commands.argument("ppt", IntegerArgumentType.integer(0, Propagation.CAPACITY))
                    .executes(c -> set(c, Faction.SKELETON, IntegerArgumentType.getInteger(c, "ppt"))))))
            .then(Commands.literal("step").executes(c -> step(c, 1))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 10000)).executes(c -> step(c, IntegerArgumentType.getInteger(c, "count")))))
            .then(Commands.literal("clear").executes(PopulationCommands::clear))
            .then(Commands.literal("fillager")
                .then(Commands.literal("promote")
                    .then(Commands.argument("tag", StringArgumentType.word()).executes(PopulationCommands::promoteTaggedFillager)))));
    }

    private static ServerLevel level(CommandContext<CommandSourceStack> c) {
        return c.getSource().getLevel();
    }

    private static int show(CommandContext<CommandSourceStack> c, Faction faction) throws CommandSyntaxException {
        ServerLevel level = level(c);
        LevelField field = PopulationSimulation.field(level, faction);
        CellKey here = field.cellAt(net.minecraft.core.BlockPos.containing(c.getSource().getPosition()));
        StringBuilder out = new StringBuilder("[" + faction + "] cell " + here + " = " + field.get(here) + " ppt"
            + (PopulationSimulation.simulated(level) ? "" : " (not simulated: Overworld only)"));
        String[] names = {"down", "up", "east", "west", "south", "north"};
        int[][] ds = {{0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int i = 0; i < ds.length; i++) {
            CellKey n = here.offset(ds[i][0], ds[i][1], ds[i][2]);
            out.append("\n  ").append(names[i]).append(": ").append(field.get(n)).append((field.blockedReason(n) == null ? "" : " (" + field.blockedReason(n) + ")"));
        }
        String text = out.toString();
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return field.get(here);
    }

    private static int set(CommandContext<CommandSourceStack> c, Faction faction, int value) {
        ServerLevel level = level(c);
        LevelField field = PopulationSimulation.field(level, faction);
        CellKey here = field.cellAt(net.minecraft.core.BlockPos.containing(c.getSource().getPosition()));
        field.set(here, value);
        if (value > 0) {
            PopulationSimulation.active(level, faction).add(here);
        } else {
            PopulationSimulation.active(level, faction).remove(here);
        }
        c.getSource().sendSuccess(() -> Component.literal("[" + faction + "] set " + here + " to " + value + " ppt"), true);
        return value;
    }

    private static int step(CommandContext<CommandSourceStack> c, int count) {
        int cells = PopulationSimulation.stepNow(level(c), count);
        c.getSource().sendSuccess(() -> Component.literal("stepped " + count + "x; " + cells + " populated cell(s)"), true);
        return cells;
    }

    private static int summary(CommandContext<CommandSourceStack> c) {
        ServerLevel level = level(c);
        LevelField field = PopulationSimulation.field(level);
        long total = 0;
        int cells = 0;
        for (CellKey cell : PopulationSimulation.active(level)) {
            total += field.get(cell);
            cells++;
        }
        long t = total;
        int n = cells;
        c.getSource().sendSuccess(() -> Component.literal(n + " populated cell(s), total " + t + " ppt"), false);
        return n;
    }

    /**
     * MINECRAFT-390 acceptance-evidence test hook: finds the nearest {@link Fillager} carrying {@code tag}
     * and promotes it via {@link Fillager#forcePromoteForTesting} -- the exact same conversion code
     * {@code Fillager#mobInteract} uses on a successful emerald-cost interaction, just without needing a
     * connected player or a real item stack. Debug-only (same op-level-2 gate as every other command in
     * this tree); {@code scripts/smoke-server.py} is the only intended caller.
     */
    private static int promoteTaggedFillager(CommandContext<CommandSourceStack> c) {
        String tag = StringArgumentType.getString(c, "tag");
        ServerLevel level = level(c);
        for (Fillager fillager : level.getEntities(EntityTypeTest.forClass(Fillager.class), e -> e.getTags().contains(tag))) {
            fillager.forcePromoteForTesting();
            c.getSource().sendSuccess(() -> Component.literal("promoted tagged Fillager"), true);
            return 1;
        }
        c.getSource().sendSuccess(() -> Component.literal("no Fillager found with tag " + tag), false);
        return 0;
    }

    private static int clear(CommandContext<CommandSourceStack> c) {
        ServerLevel level = level(c);
        LevelField field = PopulationSimulation.field(level);
        int n = PopulationSimulation.active(level).size();
        for (CellKey cell : new java.util.ArrayList<>(PopulationSimulation.active(level))) {
            field.set(cell, 0);
        }
        PopulationSimulation.active(level).clear();
        c.getSource().sendSuccess(() -> Component.literal("cleared " + n + " cell(s)"), true);
        return n;
    }
}
