package dev.engine_room.flywheel.impl;

import java.lang.reflect.Field;

import org.jetbrains.annotations.UnknownNullability;

import dev.engine_room.flywheel.lib.internal.FlwLibXplat;
import dev.engine_room.flywheel.lib.model.SimpleModel;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder;
import dev.engine_room.flywheel.lib.model.baked.BlockModelBuilder;
import dev.engine_room.flywheel.lib.model.baked.ModelBuilderImpl;
import dev.engine_room.flywheel.lib.model.baked.MultiBlockModelBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.util.ObfuscationReflectionHelper;

public class FlwLibXplatImpl implements FlwLibXplat {
	@Override
	@UnknownNullability
	public BakedModel getBakedModel(ModelManager modelManager, ResourceLocation location) {
		return modelManager.getModel(ModelResourceLocation.standalone(location));
	}

	@Override
	public BlockRenderDispatcher createVanillaBlockRenderDispatcher() {
		BlockRenderDispatcher defaultDispatcher = Minecraft.getInstance().getBlockRenderer();
		BlockRenderDispatcher dispatcher = new BlockRenderDispatcher(null, null, null);
		try {
			for (Field field : BlockRenderDispatcher.class.getDeclaredFields()) {
				field.setAccessible(true);
				field.set(dispatcher, field.get(defaultDispatcher));
			}
			ObfuscationReflectionHelper.setPrivateValue(BlockRenderDispatcher.class, dispatcher, new ModelBlockRenderer(Minecraft.getInstance().getBlockColors()), "modelRenderer");
		} catch (Exception e) {
			FlwImpl.LOGGER.error("Failed to initialize vanilla BlockRenderDispatcher!", e);
			return defaultDispatcher;
		}
		return dispatcher;
	}

	@Override
	public SimpleModel buildBakedModelBuilder(BakedModelBuilder builder) {
		return ModelBuilderImpl.buildBakedModelBuilder(builder);
	}

	@Override
	public SimpleModel buildBlockModelBuilder(BlockModelBuilder builder) {
		return ModelBuilderImpl.buildBlockModelBuilder(builder);
	}

	@Override
	public SimpleModel buildMultiBlockModelBuilder(MultiBlockModelBuilder builder) {
		return ModelBuilderImpl.buildMultiBlockModelBuilder(builder);
	}
}
