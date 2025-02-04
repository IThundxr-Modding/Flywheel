package dev.engine_room.flywheel.impl;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.jetbrains.annotations.UnknownNullability;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.api.event.EndClientResourceReloadEvent;
import dev.engine_room.flywheel.api.event.ReloadLevelRendererEvent;
import dev.engine_room.flywheel.backend.compile.FlwProgramsReloader;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.impl.compat.EmbeddiumCompat;
import dev.engine_room.flywheel.impl.visualization.VisualizationEventHandler;
import dev.engine_room.flywheel.lib.model.baked.PartialModelEventHandler;
import dev.engine_room.flywheel.lib.util.LevelAttached;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.util.ResourceReloadHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.core.registries.Registries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.CrashReportCallables;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod(value = Flywheel.ID, dist = Dist.CLIENT)
public final class FlywheelNeoForge {
	@UnknownNullability
	private static ArtifactVersion version;

	public FlywheelNeoForge(IEventBus modEventBus, ModContainer modContainer) {
		version = modContainer
				.getModInfo()
				.getVersion();

		IEventBus gameEventBus = NeoForge.EVENT_BUS;

		NeoForgeFlwConfig.INSTANCE.registerSpecs(modContainer);

		registerImplEventListeners(gameEventBus, modEventBus);
		registerLibEventListeners(gameEventBus, modEventBus);
		registerBackendEventListeners(gameEventBus, modEventBus);

		CrashReportCallables.registerCrashCallable("Flywheel Backend", BackendManagerImpl::getBackendString);
		FlwImpl.init();

		EmbeddiumCompat.init();
	}

	private static void registerImplEventListeners(IEventBus gameEventBus, IEventBus modEventBus) {
		gameEventBus.addListener((ReloadLevelRendererEvent e) -> BackendManagerImpl.onReloadLevelRenderer(e.level()));

		gameEventBus.addListener((LevelTickEvent.Post e) -> {
			// Make sure we don't tick on the server somehow.
			if (e.getLevel().isClientSide()) {
				VisualizationEventHandler.onClientTick(Minecraft.getInstance(), e.getLevel());
			}
		});
		gameEventBus.addListener((EntityJoinLevelEvent e) -> VisualizationEventHandler.onEntityJoinLevel(e.getLevel(), e.getEntity()));
		gameEventBus.addListener((EntityLeaveLevelEvent e) -> VisualizationEventHandler.onEntityLeaveLevel(e.getLevel(), e.getEntity()));

		gameEventBus.addListener(FlwCommands::registerClientCommands);

		gameEventBus.addListener((CustomizeGuiOverlayEvent.DebugText e) -> {
			Minecraft minecraft = Minecraft.getInstance();

			if (!minecraft.getDebugOverlay().showDebugScreen()) {
				return;
			}

			FlwDebugInfo.addDebugInfo(minecraft, e.getRight());
		});

		modEventBus.addListener((EndClientResourceReloadEvent e) -> BackendManagerImpl.onEndClientResourceReload(e.error().isPresent()));

		modEventBus.addListener((FMLCommonSetupEvent e) -> {
			ArgumentTypeInfos.registerByClass(BackendArgument.class, BackendArgument.INFO);
			ArgumentTypeInfos.registerByClass(DebugModeArgument.class, DebugModeArgument.INFO);
			ArgumentTypeInfos.registerByClass(LightSmoothnessArgument.class, LightSmoothnessArgument.INFO);
		});
		modEventBus.addListener((RegisterEvent e) -> {
			if (e.getRegistryKey().equals(Registries.COMMAND_ARGUMENT_TYPE)) {
				e.register(Registries.COMMAND_ARGUMENT_TYPE, Flywheel.rl("backend"), () -> BackendArgument.INFO);
				e.register(Registries.COMMAND_ARGUMENT_TYPE, Flywheel.rl("debug_mode"), () -> DebugModeArgument.INFO);
				e.register(Registries.COMMAND_ARGUMENT_TYPE, Flywheel.rl("light_smoothness"), () -> LightSmoothnessArgument.INFO);
			}
		});
	}

	private static void registerLibEventListeners(IEventBus gameEventBus, IEventBus modEventBus) {
		gameEventBus.addListener((LevelEvent.Unload e) -> LevelAttached.invalidateLevel(e.getLevel()));

		modEventBus.addListener((EndClientResourceReloadEvent e) -> RendererReloadCache.onReloadLevelRenderer());
		modEventBus.addListener((EndClientResourceReloadEvent e) -> ResourceReloadHolder.onEndClientResourceReload());

		modEventBus.addListener(PartialModelEventHandler::onRegisterAdditional);
		modEventBus.addListener(PartialModelEventHandler::onBakingCompleted);
	}

	private static void registerBackendEventListeners(IEventBus gameEventBus, IEventBus modEventBus) {
		gameEventBus.addListener((ReloadLevelRendererEvent e) -> Uniforms.onReloadLevelRenderer());

		modEventBus.addListener((RegisterClientReloadListenersEvent e) -> {
			e.registerReloadListener(FlwProgramsReloader.INSTANCE);
		});
	}

	public static ArtifactVersion version() {
		return version;
	}
}
