package dev.engine_room.flywheel.impl;

import org.jetbrains.annotations.UnknownNullability;

import dev.engine_room.flywheel.lib.internal.FlwLibXplat;
import dev.engine_room.flywheel.lib.model.SimpleModel;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder;
import dev.engine_room.flywheel.lib.model.baked.BlockModelBuilder;
import dev.engine_room.flywheel.lib.model.baked.ModelBuilderImpl;
import dev.engine_room.flywheel.lib.model.baked.MultiBlockModelBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;

public class FlwLibXplatImpl implements FlwLibXplat {
	@Override
	@UnknownNullability
	public BakedModel getBakedModel(ModelManager modelManager, ResourceLocation location) {
		return modelManager.getModel(location);
	}

	@Override
	public BlockRenderDispatcher createVanillaBlockRenderDispatcher() {
		return Minecraft.getInstance().getBlockRenderer();
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
