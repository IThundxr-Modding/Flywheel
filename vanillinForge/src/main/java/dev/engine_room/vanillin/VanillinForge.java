package dev.engine_room.vanillin;

import dev.engine_room.vanillin.visuals.VanillaVisuals;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(Vanillin.ID)
public class VanillinForge {
	public VanillinForge() {
		IEventBus forgeEventBus = MinecraftForge.EVENT_BUS;
		IEventBus modEventBus = FMLJavaModLoadingContext.get()
				.getModEventBus();
		DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> clientInit(forgeEventBus, modEventBus));
	}

	private static void clientInit(IEventBus forgeEventBus, IEventBus modEventBus) {
		VanillaVisuals.init();
	}
}
