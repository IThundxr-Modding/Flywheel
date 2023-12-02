package com.jozufozu.flywheel.lib.material;

import org.jetbrains.annotations.ApiStatus;

import com.jozufozu.flywheel.Flywheel;
import com.jozufozu.flywheel.api.material.MaterialShaders;

import net.minecraft.resources.ResourceLocation;

public final class StandardMaterialShaders {
	public static final MaterialShaders DEFAULT = MaterialShaders.REGISTRY.registerAndGet(new SimpleMaterialShaders(Files.DEFAULT_VERTEX, Files.DEFAULT_FRAGMENT));

	private StandardMaterialShaders() {
	}

	@ApiStatus.Internal
	public static void init() {
	}

	public static final class Files {
		public static final ResourceLocation DEFAULT_VERTEX = Names.DEFAULT.withSuffix(".vert");
		public static final ResourceLocation DEFAULT_FRAGMENT = Names.DEFAULT.withSuffix(".frag");
	}

	public static final class Names {
		public static final ResourceLocation DEFAULT = Flywheel.rl("material/default");
	}
}
