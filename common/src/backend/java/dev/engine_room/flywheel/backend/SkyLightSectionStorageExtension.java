package dev.engine_room.flywheel.backend;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.chunk.DataLayer;

public interface SkyLightSectionStorageExtension {
	@Nullable DataLayer flywheel$skyDataLayer(long section);
}
