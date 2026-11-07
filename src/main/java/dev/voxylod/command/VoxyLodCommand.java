package dev.voxylod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.voxylod.GeneratorSettings;
import dev.voxylod.generation.CircularCursor;
import dev.voxylod.generation.GenerationManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class VoxyLodCommand {
    private final GenerationManager manager;

    public VoxyLodCommand(GenerationManager manager) {
        this.manager = manager;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("voxylod")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("start")
                        .executes(context -> start(context, false))
                        .then(Commands.argument("originX", IntegerArgumentType.integer())
                                .then(Commands.argument("originZ", IntegerArgumentType.integer())
                                        .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(0, 4096))
                                                .executes(context -> startWithArguments(context, false, false))
                                                .then(Commands.argument("threads", IntegerArgumentType.integer(1, 64))
                                                        .executes(context -> startWithArguments(context, false, true)))))))
                .then(Commands.literal("restart").executes(context -> start(context, true)))
                .then(Commands.literal("startFrom")
                        .then(Commands.argument("index", LongArgumentType.longArg(0))
                                .executes(context -> runGuarded(context, () -> this.manager.startFrom(
                                        level(context), LongArgumentType.getLong(context, "index"))))))
                .then(Commands.literal("stop").executes(context -> success(context, this.manager.stop())))
                .then(Commands.literal("pause").executes(context -> success(context, this.manager.pause())))
                .then(Commands.literal("resume").executes(context -> success(context, this.manager.resume())))
                .then(Commands.literal("status").executes(context -> runGuarded(context, this.manager::status)))
                .then(Commands.literal("origin")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(context -> runGuarded(context, () -> {
                                            requireIdle();
                                            int x = IntegerArgumentType.getInteger(context, "x");
                                            int z = IntegerArgumentType.getInteger(context, "z");
                                            this.manager.settings().originX = x;
                                            this.manager.settings().originZ = z;
                                            this.manager.settings().save();
                                            return "生成原点已设为方块坐标 [" + x + ", " + z + "]";
                                        })))))
                .then(Commands.literal("radius")
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(0, 4096))
                                .executes(context -> runGuarded(context, () -> {
                                    requireIdle();
                                    int radius = IntegerArgumentType.getInteger(context, "chunks");
                                    this.manager.settings().radiusChunks = radius;
                                    this.manager.settings().save();
                                    return "生成半径已设为 " + radius + " 区块（"
                                            + CircularCursor.countChunks(radius) + " 个区块）";
                                }))))
                .then(Commands.literal("threads")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(context -> runGuarded(context, () -> {
                                    requireIdle();
                                    int threads = IntegerArgumentType.getInteger(context, "count");
                                    this.manager.settings().setThreads(threads);
                                    this.manager.settings().save();
                                    return "后台转换线程已设为 " + threads
                                            + "，在途区块上限自动调整为 " + this.manager.settings().pendingChunks;
                                }))))
                .then(Commands.literal("queue")
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(1, 64))
                                .executes(context -> runGuarded(context, () -> {
                                    requireIdle();
                                    int pending = IntegerArgumentType.getInteger(context, "chunks");
                                    this.manager.settings().pendingChunks = pending;
                                    this.manager.settings().save();
                                    return "在途区块硬上限已设为 " + pending;
                                }))));
        dispatcher.register(root);
    }

    private int start(CommandContext<CommandSourceStack> context, boolean restart) {
        return runGuarded(context, () -> this.manager.start(level(context), restart));
    }

    private int startWithArguments(CommandContext<CommandSourceStack> context, boolean restart, boolean threads) {
        return runGuarded(context, () -> {
            requireIdle();
            GeneratorSettings settings = this.manager.settings();
            settings.originX = IntegerArgumentType.getInteger(context, "originX");
            settings.originZ = IntegerArgumentType.getInteger(context, "originZ");
            settings.radiusChunks = IntegerArgumentType.getInteger(context, "radiusChunks");
            if (threads) {
                settings.setThreads(IntegerArgumentType.getInteger(context, "threads"));
            }
            settings.save();
            return this.manager.start(level(context), restart);
        });
    }

    private ServerLevel level(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player != null) {
            return player.serverLevel();
        }
        return context.getSource().getLevel();
    }

    private void requireIdle() {
        this.manager.requireIdle();
    }

    private int runGuarded(CommandContext<CommandSourceStack> context, MessageAction action) {
        try {
            return success(context, action.run());
        } catch (RuntimeException exception) {
            context.getSource().sendFailure(Component.literal(exception.getMessage() == null
                    ? exception.toString() : exception.getMessage()));
            return 0;
        }
    }

    private int success(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    @FunctionalInterface
    private interface MessageAction {
        String run();
    }
}
