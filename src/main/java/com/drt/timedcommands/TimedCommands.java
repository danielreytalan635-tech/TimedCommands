package com.drt.timedcommands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameModeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.server.permissions.Permissions;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class TimedCommands implements ModInitializer {
    public static final String MOD_ID = "timedcommands";

    private static final Map<UUID, TimedGamemode> ACTIVE = new HashMap<>();
    private static final SimpleCommandExceptionType INVALID_DURATION =
            new SimpleCommandExceptionType(Component.literal("Invalid duration. Use values like 5s, 7s, 2m, or 100t."));

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    Commands.literal("timed")
                            .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                            .then(Commands.argument("duration", StringArgumentType.word())
                                    .then(Commands.literal("gamemode")
                                            .then(Commands.argument("mode", GameModeArgument.gameMode())
                                                    .then(Commands.argument("targets", EntityArgument.players())
                                                            .executes(TimedCommands::executeTimedGamemode)
                                                    )
                                            )
                                    )
                            )
            );
        });

        ServerTickEvents.END_SERVER_TICK.register(TimedCommands::tick);
    }

    private static int executeTimedGamemode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        long durationTicks = parseDuration(StringArgumentType.getString(context, "duration"));
        GameType targetMode = GameModeArgument.getGameMode(context, "mode");
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");

        int changed = 0;

        for (ServerPlayer player : targets) {
            TimedGamemode existing = ACTIVE.get(player.getUUID());
            GameType originalMode = existing == null ? player.gameMode.getGameModeForPlayer() : existing.originalMode;

            ACTIVE.put(player.getUUID(), new TimedGamemode(
                    originalMode,
                    targetMode,
                    durationTicks
            ));

            player.setGameMode(targetMode);
            changed++;
        }

        final int changedCount = changed;
        String targetText = changedCount == 1 ? "player" : "players";
        long seconds = Math.max(1, Math.round(durationTicks / 20.0));

        context.getSource().sendSuccess(
                () -> Component.literal("Timed gamemode: " + targetMode.getSerializedName()
                        + " for " + changedCount + " " + targetText + " (" + seconds + "s)."),
                true
        );

        return changed;
    }

    private static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, TimedGamemode>> iterator = ACTIVE.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, TimedGamemode> entry = iterator.next();
            TimedGamemode timer = entry.getValue();

            timer.remainingTicks--;
            if (timer.remainingTicks > 0) {
                continue;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                // Do not overwrite a game mode the player or another command changed manually.
                if (player.gameMode.getGameModeForPlayer() == timer.temporaryMode) {
                    player.setGameMode(timer.originalMode);
                    player.sendSystemMessage(Component.literal(
                            "Your timed gamemode ended. Restored " + timer.originalMode.getSerializedName() + "."
                    ));
                }
            }

            iterator.remove();
        }
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

    private static final class TimedGamemode {
        private final GameType originalMode;
        private final GameType temporaryMode;
        private long remainingTicks;

        private TimedGamemode(GameType originalMode, GameType temporaryMode, long remainingTicks) {
            this.originalMode = originalMode;
            this.temporaryMode = temporaryMode;
            this.remainingTicks = remainingTicks;
        }
    }
}
