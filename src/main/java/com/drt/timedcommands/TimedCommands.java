package com.drt.timedcommands;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameModeArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TimedCommands implements ModInitializer {
    public static final String MOD_ID = "timedcommands";

    private static final Map<String, TimedCommandHandler> HANDLERS = new LinkedHashMap<>();
    private static final Map<UUID, Map<String, ActiveTimer>> ACTIVE = new HashMap<>();

    private static final SimpleCommandExceptionType INVALID_DURATION =
            new SimpleCommandExceptionType(Component.literal(
                    "Invalid duration. Use values like 5s, 7s, 2m, 1h, or 100t."
            ));
    private static final SimpleCommandExceptionType UNSUPPORTED_COMMAND =
            new SimpleCommandExceptionType(Component.literal(
                    "That command is not supported by TimedCommands because no safe rollback handler is registered."
            ));
    private static final SimpleCommandExceptionType COMMAND_NOT_REGISTERED =
            new SimpleCommandExceptionType(Component.literal(
                    "That command is not registered on this server."
            ));
    private static final SimpleCommandExceptionType HANDLER_PERMISSION =
            new SimpleCommandExceptionType(Component.literal(
                    "You do not have permission to use the timed handler for that command."
            ));
    private static final SimpleCommandExceptionType TIMER_CONFLICT =
            new SimpleCommandExceptionType(Component.literal(
                    "That player already has a timer for this command."
            ));

    @Override
    public void onInitialize() {
        registerBuiltInHandlers();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    Commands.literal("timed")
                            .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                            .then(Commands.literal("supported")
                                    .executes(context -> listSupported(context.getSource())))
                            .then(Commands.literal("scan")
                                    .executes(context -> scanCommands(context.getSource())))
                            .then(Commands.literal("list")
                                    .executes(TimedCommands::listTimers))
                            .then(Commands.literal("cancel")
                                    .then(Commands.argument("target", EntityArgument.player())
                                            .executes(TimedCommands::cancelTimers)))
                            .then(Commands.argument("duration", StringArgumentType.word())
                                    .then(Commands.argument("command", StringArgumentType.greedyString())
                                            .executes(TimedCommands::executeTimedCommand)))
            );
        });

        ServerTickEvents.END_SERVER_TICK.register(TimedCommands::tick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            Map<String, ActiveTimer> timers = ACTIVE.get(player.getUUID());
            if (timers == null || timers.isEmpty()) {
                return;
            }

            List<String> finished = new ArrayList<>();
            for (Map.Entry<String, ActiveTimer> entry : timers.entrySet()) {
                ActiveTimer timer = entry.getValue();
                if (timer.remainingTicks <= 0) {
                    if (timer.action.expire(server)) {
                        finished.add(entry.getKey());
                    }
                } else {
                    timer.action.onJoin(player);
                }
            }

            for (String key : finished) {
                timers.remove(key);
            }
            removeEmptyTimerMap(player.getUUID());
        });
    }

    /**
     * Allows another server-side mod to add a safe, reversible timed-command handler.
     * The command root must already be registered in Minecraft's command dispatcher.
     */
    public static void registerTimedHandler(String commandName, TimedCommandHandler handler) {
        if (commandName == null || commandName.isBlank() || handler == null) {
            throw new IllegalArgumentException("Timed command name and handler are required.");
        }
        HANDLERS.put(commandName.toLowerCase(), handler);
    }

    private static void registerBuiltInHandlers() {
        registerTimedHandler("gamemode", TimedCommands::startGamemode);
        registerTimedHandler("op", TimedCommands::startOp);
        registerTimedHandler("deop", TimedCommands::startDeop);
    }

    private static int executeTimedCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        long durationTicks = parseDuration(
                StringArgumentType.getString(context, "duration")
        );
        String commandLine = StringArgumentType.getString(context, "command").trim();

        if (commandLine.isEmpty()) {
            throw UNSUPPORTED_COMMAND.create();
        }

        String commandName = firstWord(commandLine).toLowerCase();
        MinecraftServer server = context.getSource().getServer();

        if (server.getCommands().getDispatcher().getRoot().getChild(commandName) == null) {
            throw COMMAND_NOT_REGISTERED.create();
        }

        TimedCommandHandler handler = HANDLERS.get(commandName);
        if (handler == null) {
            throw UNSUPPORTED_COMMAND.create();
        }

        if (handler.requiresAdmin() &&
                !context.getSource().permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
            throw HANDLER_PERMISSION.create();
        }

        Map<UUID, TimedAction> actions = handler.start(
                server,
                context.getSource(),
                commandLine.substring(commandName.length()).trim(),
                durationTicks
        );

        if (actions.isEmpty()) {
            return 0;
        }

        for (Map.Entry<UUID, TimedAction> entry : actions.entrySet()) {
            Map<String, ActiveTimer> playerTimers =
                    ACTIVE.computeIfAbsent(entry.getKey(), ignored -> new HashMap<>());

            if (playerTimers.containsKey(commandName)) {
                throw TIMER_CONFLICT.create();
            }

            playerTimers.put(commandName, new ActiveTimer(
                    commandName,
                    durationTicks,
                    entry.getValue()
            ));
        }

        long seconds = Math.max(1, Math.round(durationTicks / 20.0));
        int count = actions.size();
        String plural = count == 1 ? "player" : "players";

        context.getSource().sendSuccess(
                () -> Component.literal(
                        "Timed " + commandName + " for " + count + " " + plural +
                                " (" + formatDuration(seconds) + ")."
                ),
                true
        );

        return count;
    }

    private static int listSupported(CommandSourceStack source) {
        List<String> supported = getSupportedCommands(source.getServer());
        if (supported.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "No timed-safe handlers are registered for commands on this server."
            ), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Timed-safe commands (" + supported.size() + "): " + String.join(", ", supported)
        ), false);
        return supported.size();
    }

    private static int scanCommands(CommandSourceStack source) {
        Collection<CommandNode<CommandSourceStack>> nodes =
                source.getServer().getCommands().getDispatcher().getRoot().getChildren();

        int registered = nodes.size();
        int supported = getSupportedCommands(source.getServer()).size();

        source.sendSuccess(() -> Component.literal(
                "Command scan: " + registered + " registered server command roots, " +
                        supported + " currently have TimedCommands rollback handlers."
        ), false);

        return supported;
    }

    private static int listTimers(CommandContext<CommandSourceStack> context) {
        int count = 0;

        for (Map<String, ActiveTimer> timers : ACTIVE.values()) {
            count += timers.size();
        }

        if (count == 0) {
            context.getSource().sendSuccess(
                    () -> Component.literal("No active timed commands."),
                    false
            );
            return 0;
        }

        for (Map<String, ActiveTimer> timers : ACTIVE.values()) {
            for (ActiveTimer timer : timers.values()) {
                context.getSource().sendSuccess(
                        () -> Component.literal(
                                timer.commandName + " -> " + timer.action.description() +
                                        " (" + formatTicks(timer.remainingTicks) + " remaining)"
                        ),
                        false
                );
            }
        }

        return count;
    }

    private static int cancelTimers(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "target");
        Map<String, ActiveTimer> timers = ACTIVE.remove(player.getUUID());

        if (timers == null || timers.isEmpty()) {
            throw new SimpleCommandExceptionType(
                    Component.literal("That player has no active TimedCommands timers.")
            ).create();
        }

        int count = timers.size();
        context.getSource().sendSuccess(
                () -> Component.literal(
                        "Cancelled " + count + " timed command(s) for " + player.getName().getString() + "."
                ),
                true
        );

        return count;
    }

    private static void tick(MinecraftServer server) {
        List<UUID> emptyPlayers = new ArrayList<>();

        for (Map.Entry<UUID, Map<String, ActiveTimer>> playerEntry : ACTIVE.entrySet()) {
            Map<String, ActiveTimer> timers = playerEntry.getValue();
            List<String> finished = new ArrayList<>();

            for (Map.Entry<String, ActiveTimer> timerEntry : timers.entrySet()) {
                ActiveTimer timer = timerEntry.getValue();

                if (timer.remainingTicks > 0) {
                    timer.remainingTicks--;
                }

                if (timer.remainingTicks <= 0 && timer.action.expire(server)) {
                    finished.add(timerEntry.getKey());
                }
            }

            for (String key : finished) {
                timers.remove(key);
            }

            if (timers.isEmpty()) {
                emptyPlayers.add(playerEntry.getKey());
            }
        }

        for (UUID uuid : emptyPlayers) {
            ACTIVE.remove(uuid);
        }
    }

    private static Map<String, ActiveTimer> timersFor(UUID uuid) {
        return ACTIVE.computeIfAbsent(uuid, ignored -> new HashMap<>());
    }

    private static void removeEmptyTimerMap(UUID uuid) {
        Map<String, ActiveTimer> timers = ACTIVE.get(uuid);
        if (timers != null && timers.isEmpty()) {
            ACTIVE.remove(uuid);
        }
    }

    private static List<String> getSupportedCommands(MinecraftServer server) {
        List<String> supported = new ArrayList<>();
        for (String name : HANDLERS.keySet()) {
            if (server.getCommands().getDispatcher().getRoot().getChild(name) != null) {
                supported.add(name);
            }
        }
        supported.sort(Comparator.naturalOrder());
        return supported;
    }

    private static String firstWord(String input) {
        int index = input.indexOf(' ');
        return index < 0 ? input : input.substring(0, index);
    }

    private static long parseDuration(String input) throws CommandSyntaxException {
        String value = input.toLowerCase();
        if (value.isEmpty()) {
            throw INVALID_DURATION.create();
        }

        char suffix = value.charAt(value.length() - 1);
        long amount;

        try {
            if (suffix == 's' || suffix == 'm' || suffix == 'h' || suffix == 't') {
                amount = Long.parseLong(value.substring(0, value.length() - 1));
            } else {
                suffix = 's';
                amount = Long.parseLong(value);
            }
        } catch (NumberFormatException exception) {
            throw INVALID_DURATION.create();
        }

        if (amount <= 0 || amount > 7L * 24L * 60L * 60L) {
            throw INVALID_DURATION.create();
        }

        try {
            return Math.max(1L, switch (suffix) {
                case 't' -> amount;
                case 'm' -> Math.multiplyExact(amount, 20L * 60L);
                case 'h' -> Math.multiplyExact(amount, 20L * 60L * 60L);
                default -> Math.multiplyExact(amount, 20L);
            });
        } catch (ArithmeticException exception) {
            throw INVALID_DURATION.create();
        }
    }

    private static String formatDuration(long seconds) {
        if (seconds % 3600 == 0) {
            return (seconds / 3600) + "h";
        }
        if (seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    private static String formatTicks(long ticks) {
        if (ticks <= 0) {
            return "ending";
        }
        if (ticks >= 20L * 60L * 60L && ticks % (20L * 60L * 60L) == 0) {
            return (ticks / (20L * 60L * 60L)) + "h";
        }
        if (ticks >= 20L * 60L && ticks % (20L * 60L) == 0) {
            return (ticks / (20L * 60L)) + "m";
        }
        if (ticks % 20L == 0) {
            return (ticks / 20L) + "s";
        }
        return ticks + "t";
    }

    private static <T> CommandContext<CommandSourceStack> parseExistingCommand(
            MinecraftServer server,
            CommandSourceStack source,
            String command
    ) throws CommandSyntaxException {
        StringReader reader = new StringReader(command);
        var parse = server.getCommands().getDispatcher().parse(reader, source);
        Commands.validateParseResults(parse);
        return parse.getContext().build(command);
    }

    private static Map<UUID, TimedAction> startGamemode(
            MinecraftServer server,
            CommandSourceStack source,
            String args,
            long durationTicks
    ) throws CommandSyntaxException {
        CommandContext<CommandSourceStack> parsed =
                parseExistingCommand(server, source, "gamemode " + args);

        GameType targetMode = GameModeArgument.getGameMode(parsed, "gamemode");
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(parsed, "target");

        Map<UUID, TimedAction> actions = new LinkedHashMap<>();

        for (ServerPlayer player : targets) {
            if (timersFor(player.getUUID()).containsKey("gamemode")) {
                throw TIMER_CONFLICT.create();
            }

            GameType originalMode = player.gameMode.getGameModeForPlayer();
            player.setGameMode(targetMode);

            actions.put(player.getUUID(), new GamemodeAction(
                    player.getUUID(),
                    originalMode,
                    targetMode
            ));
        }

        return actions;
    }

    private static Map<UUID, TimedAction> startOp(
            MinecraftServer server,
            CommandSourceStack source,
            String args,
            long durationTicks
    ) throws CommandSyntaxException {
        if (!source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
            throw HANDLER_PERMISSION.create();
        }

        CommandContext<CommandSourceStack> parsed =
                parseExistingCommand(server, source, "op " + args);

        Collection<NameAndId> targets =
                GameProfileArgument.getGameProfiles(parsed, "targets");

        Map<UUID, TimedAction> actions = new LinkedHashMap<>();

        for (NameAndId target : targets) {
            if (timersFor(target.id()).containsKey("op")) {
                throw TIMER_CONFLICT.create();
            }

            boolean originalOp = server.getPlayerList().isOp(target);
            server.getPlayerList().op(target);

            actions.put(target.id(), new OpAction(target, true, originalOp));
        }

        return actions;
    }

    private static Map<UUID, TimedAction> startDeop(
            MinecraftServer server,
            CommandSourceStack source,
            String args,
            long durationTicks
    ) throws CommandSyntaxException {
        if (!source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
            throw HANDLER_PERMISSION.create();
        }

        CommandContext<CommandSourceStack> parsed =
                parseExistingCommand(server, source, "deop " + args);

        Collection<NameAndId> targets =
                GameProfileArgument.getGameProfiles(parsed, "targets");

        Map<UUID, TimedAction> actions = new LinkedHashMap<>();

        for (NameAndId target : targets) {
            if (timersFor(target.id()).containsKey("deop")) {
                throw TIMER_CONFLICT.create();
            }

            boolean originalOp = server.getPlayerList().isOp(target);
            server.getPlayerList().deop(target);

            actions.put(target.id(), new OpAction(target, false, originalOp));
        }

        return actions;
    }

    public interface TimedCommandHandler {
        Map<UUID, TimedAction> start(
                MinecraftServer server,
                CommandSourceStack source,
                String arguments,
                long durationTicks
        ) throws CommandSyntaxException;

        default boolean requiresAdmin() {
            return false;
        }
    }

    public interface TimedAction {
        String description();

        boolean expire(MinecraftServer server);

        default void onJoin(ServerPlayer player) {
        }
    }

    private record ActiveTimer(
            String commandName,
            long durationTicks,
            TimedAction action
    ) {
        private long remainingTicks = durationTicks;
    }

    private static final class GamemodeAction implements TimedAction {
        private final UUID playerId;
        private final GameType originalMode;
        private final GameType temporaryMode;

        private GamemodeAction(UUID playerId, GameType originalMode, GameType temporaryMode) {
            this.playerId = playerId;
            this.originalMode = originalMode;
            this.temporaryMode = temporaryMode;
        }

        @Override
        public String description() {
            return temporaryMode.getSerializedName() + " gamemode";
        }

        @Override
        public boolean expire(MinecraftServer server) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                return false;
            }

            if (player.gameMode.getGameModeForPlayer() == temporaryMode) {
                player.setGameMode(originalMode);
                player.sendSystemMessage(Component.literal(
                        "Your timed gamemode ended. Restored " +
                                originalMode.getSerializedName() + "."
                ));
            }

            return true;
        }

        @Override
        public void onJoin(ServerPlayer player) {
            if (player.getUUID().equals(playerId)) {
                player.setGameMode(temporaryMode);
            }
        }
    }

    private static final class OpAction implements TimedAction {
        private final NameAndId target;
        private final boolean temporaryOp;
        private final boolean originalOp;

        private OpAction(NameAndId target, boolean temporaryOp, boolean originalOp) {
            this.target = target;
            this.temporaryOp = temporaryOp;
            this.originalOp = originalOp;
        }

        @Override
        public String description() {
            return temporaryOp ? "temporary OP" : "temporary de-OP";
        }

        @Override
        public boolean expire(MinecraftServer server) {
            boolean currentOp = server.getPlayerList().isOp(target);

            if (currentOp == temporaryOp) {
                if (originalOp) {
                    server.getPlayerList().op(target);
                } else {
                    server.getPlayerList().deop(target);
                }
            }

            return true;
        }
    }
}
