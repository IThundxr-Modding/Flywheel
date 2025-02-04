package dev.engine_room.flywheel.impl;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.backend.BackendDebugFlags;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.backend.compile.PipelineCompiler;
import dev.engine_room.flywheel.backend.engine.uniform.DebugMode;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

public final class FlwCommands {
	private FlwCommands() {
	}

	public static void registerClientCommands(RegisterClientCommandsEvent event) {
		LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("flywheel");

		ConfigValue<String> backendValue = NeoForgeFlwConfig.INSTANCE.client.backend;
		command.then(Commands.literal("backend")
				.executes(context -> {
					Backend backend = BackendManager.currentBackend();
					String idStr = Backend.REGISTRY.getIdOrThrow(backend)
							.toString();
					sendMessage(context.getSource(), Component.translatable("command.flywheel.backend.get", idStr));
					return Command.SINGLE_SUCCESS;
				})
				.then(Commands.argument("id", BackendArgument.INSTANCE)
					.executes(context -> {
						Backend requestedBackend = context.getArgument("id", Backend.class);
						String requestedIdStr = Backend.REGISTRY.getIdOrThrow(requestedBackend)
								.toString();
						backendValue.set(requestedIdStr);

						// Reload renderers so we can report the actual backend.
						Minecraft.getInstance().levelRenderer.allChanged();

						Backend actualBackend = BackendManager.currentBackend();
						if (actualBackend != requestedBackend) {
							sendFailure(context.getSource(), Component.translatable("command.flywheel.backend.set.unavailable", requestedIdStr));
						}

						String actualIdStr = Backend.REGISTRY.getIdOrThrow(actualBackend)
								.toString();
						sendMessage(context.getSource(), Component.translatable("command.flywheel.backend.set", actualIdStr));
						return Command.SINGLE_SUCCESS;
					})));

		BooleanValue limitUpdatesValue = NeoForgeFlwConfig.INSTANCE.client.limitUpdates;
		command.then(Commands.literal("limitUpdates")
				.executes(context -> {
					if (limitUpdatesValue.get()) {
						sendMessage(context.getSource(), Component.translatable("command.flywheel.limit_updates.get.on"));
					} else {
						sendMessage(context.getSource(), Component.translatable("command.flywheel.limit_updates.get.off"));
					}
					return Command.SINGLE_SUCCESS;
				})
				.then(Commands.literal("on")
						.executes(context -> {
							limitUpdatesValue.set(true);
							sendMessage(context.getSource(), Component.translatable("command.flywheel.limit_updates.set.on"));
							Minecraft.getInstance().levelRenderer.allChanged();
							return Command.SINGLE_SUCCESS;
						}))
				.then(Commands.literal("off")
						.executes(context -> {
							limitUpdatesValue.set(false);
							sendMessage(context.getSource(), Component.translatable("command.flywheel.limit_updates.set.off"));
							Minecraft.getInstance().levelRenderer.allChanged();
							return Command.SINGLE_SUCCESS;
						})));

		var lightSmoothnessValue = NeoForgeFlwConfig.INSTANCE.client.backendConfig.lightSmoothness;
		command.then(Commands.literal("lightSmoothness")
				.then(Commands.argument("mode", LightSmoothnessArgument.INSTANCE)
						.executes(context -> {
							var oldValue = lightSmoothnessValue.get();
							var newValue = context.getArgument("mode", LightSmoothness.class);

							if (oldValue != newValue) {
								lightSmoothnessValue.set(newValue);
								PipelineCompiler.deleteAll();
							}
							return Command.SINGLE_SUCCESS;
						})));

		command.then(createDebugCommand());

		event.getDispatcher().register(command);
	}

	private static LiteralArgumentBuilder<CommandSourceStack> createDebugCommand() {
		var debug = Commands.literal("debug");

		debug.then(Commands.literal("crumbling")
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
						.then(Commands.argument("stage", IntegerArgumentType.integer(0, 9))
								.executes(context -> {
									Entity executor = context.getSource()
											.getEntity();

									if (executor == null) {
										return 0;
									}

									BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
									int value = IntegerArgumentType.getInteger(context, "stage");

									executor.level()
											.destroyBlockProgress(executor.getId(), pos, value);

									return Command.SINGLE_SUCCESS;
								}))));

		debug.then(Commands.literal("shader")
				.then(Commands.argument("mode", DebugModeArgument.INSTANCE)
						.executes(context -> {
							DebugMode mode = context.getArgument("mode", DebugMode.class);
							FrameUniforms.debugMode(mode);
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(Commands.literal("frustum")
				.then(Commands.literal("capture")
						.executes(context -> {
							FrameUniforms.captureFrustum();
							return Command.SINGLE_SUCCESS;
						}))
				.then(Commands.literal("unpause")
						.executes(context -> {
							FrameUniforms.unpauseFrustum();
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(Commands.literal("lightSections")
				.then(Commands.literal("on")
						.executes(context -> {
							BackendDebugFlags.LIGHT_STORAGE_VIEW = true;
							return Command.SINGLE_SUCCESS;
						}))
				.then(Commands.literal("off")
						.executes(context -> {
							BackendDebugFlags.LIGHT_STORAGE_VIEW = false;
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(Commands.literal("pauseUpdates")
				.then(Commands.literal("on")
						.executes(context -> {
							ImplDebugFlags.PAUSE_UPDATES = true;
							return Command.SINGLE_SUCCESS;
						}))
				.then(Commands.literal("off")
						.executes(context -> {
							ImplDebugFlags.PAUSE_UPDATES = false;
							return Command.SINGLE_SUCCESS;
						})));

		return debug;
	}

	private static void sendMessage(CommandSourceStack source, Component message) {
		source.sendSuccess(() -> message, true);
	}

	private static void sendFailure(CommandSourceStack source, Component message) {
		source.sendFailure(message);
	}
}
