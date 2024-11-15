package dev.engine_room.flywheel.backend.mixin.light;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;

@Mixin(LightEngine.class)
public interface LightEngineAccessor<M extends DataLayerStorageMap<M>, S extends LayerLightSectionStorage<M>> {
	@Accessor("storage")
	S flywheel$storage();
}
