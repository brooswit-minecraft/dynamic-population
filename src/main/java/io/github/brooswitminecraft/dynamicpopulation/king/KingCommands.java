package io.github.brooswitminecraft.dynamicpopulation.king;

import com.mojang.brigadier.context.CommandContext;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Debug commands (op level 2) for the King mechanism (AC4, generalized to all three factions by
 * MINECRAFT-390), nested under the existing {@code /dpop} tree -- registering {@code "dpop"} again here
 * merges into the literal node {@code PopulationCommands} already registered (Brigadier merges same-named
 * literal nodes registered via separate {@code RegisterCommandsEvent} listeners), it does not replace it.
 *
 * <p>This story does not auto-spawn Kings during world generation or via any natural trigger -- that is not
 * specified by this ticket's fixed decisions, for ANY faction. {@code /dpop king settle [faction]} is the
 * only way to found one, mirroring how {@code /dpop set} is the only way to seed population directly (see
 * {@code docs/propagation.md}). Natural founding remains an explicit, unimplemented follow-up
 * (MINECRAFT-390 scope item 7) -- see docs/villager-king.md.
 *
 * <p>{@code settle} with no faction argument keeps settling a VILLAGER King, unchanged from before this
 * story -- every pre-existing script/test that calls bare {@code /dpop king settle} keeps working exactly
 * as before; {@code settle zombie}/{@code settle skeleton} are the new explicit faction choices.
 */
public final class KingCommands {
    private KingCommands() { }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dpop").requires(s -> s.hasPermission(2))
            .then(Commands.literal("king")
                .then(Commands.literal("settle").executes(c -> settle(c, Faction.VILLAGER))
                    .then(Commands.literal("villager").executes(c -> settle(c, Faction.VILLAGER)))
                    .then(Commands.literal("zombie").executes(c -> settle(c, Faction.ZOMBIE)))
                    .then(Commands.literal("skeleton").executes(c -> settle(c, Faction.SKELETON))))
                .then(Commands.literal("list").executes(KingCommands::list))
                .then(Commands.literal("remove").executes(KingCommands::remove))));
    }

    private static int settle(CommandContext<CommandSourceStack> c, Faction faction) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPos.containing(c.getSource().getPosition());
        KingManager.SettleResult result = KingManager.settle(level, pos, faction);
        String text = switch (result) {
            case SETTLED -> "a " + faction + " King settled at " + pos;
            case UNSUITABLE_TERRITORY -> "not suitable territory for a King to settle here";
            case NOT_UNIQUE -> "too close to an existing " + faction + " King -- not unique within the relevant area";
            case INSERTION_FAILED -> "King entity insertion failed -- nothing was settled";
        };
        boolean settled = result == KingManager.SettleResult.SETTLED;
        c.getSource().sendSuccess(() -> Component.literal(text), settled);
        return settled ? 1 : 0;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        var kings = KingManager.kings(level);
        StringBuilder out = new StringBuilder(kings.size() + " King(s)");
        for (KingRecord.Entry entry : kings) {
            out.append("\n  [").append(entry.faction()).append("] ").append(entry.id()).append(" at (")
                .append(entry.x()).append(", ").append(entry.y()).append(", ").append(entry.z()).append(")");
        }
        String text = out.toString();
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return kings.size();
    }

    private static int remove(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPos.containing(c.getSource().getPosition());
        boolean removed = KingManager.removeNearest(level, pos, 64.0);
        String text = removed ? "removed the nearest King" : "no King within 64 blocks";
        c.getSource().sendSuccess(() -> Component.literal(text), removed);
        return removed ? 1 : 0;
    }
}
