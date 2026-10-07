package io.github.brooswitminecraft.dynamicpopulation.propagation;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Debug commands (op level 2): look at the field, put population in, step it, clear it. */
public final class PopulationCommands {
    private PopulationCommands() { }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dpop").requires(s -> s.hasPermission(2))
            .then(Commands.literal("show").executes(PopulationCommands::show))
            .then(Commands.literal("field").executes(PopulationCommands::summary))
            .then(Commands.literal("set").then(Commands.argument("ppt", IntegerArgumentType.integer(0, Propagation.CAPACITY))
                .executes(PopulationCommands::set)))
            .then(Commands.literal("step").executes(c -> step(c, 1))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 10000)).executes(c -> step(c, IntegerArgumentType.getInteger(c, "count")))))
            .then(Commands.literal("clear").executes(PopulationCommands::clear)));
    }

    private static ServerLevel level(CommandContext<CommandSourceStack> c) {
        return c.getSource().getLevel();
    }

    private static int show(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = level(c);
        LevelField field = PopulationSimulation.field(level);
        CellKey here = field.cellAt(net.minecraft.core.BlockPos.containing(c.getSource().getPosition()));
        StringBuilder out = new StringBuilder("cell " + here + " = " + field.get(here) + " ppt"
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

    private static int set(CommandContext<CommandSourceStack> c) {
        ServerLevel level = level(c);
        LevelField field = PopulationSimulation.field(level);
        CellKey here = field.cellAt(net.minecraft.core.BlockPos.containing(c.getSource().getPosition()));
        int value = IntegerArgumentType.getInteger(c, "ppt");
        field.set(here, value);
        if (value > 0) {
            PopulationSimulation.active(level).add(here);
        } else {
            PopulationSimulation.active(level).remove(here);
        }
        c.getSource().sendSuccess(() -> Component.literal("set " + here + " to " + value + " ppt"), true);
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
