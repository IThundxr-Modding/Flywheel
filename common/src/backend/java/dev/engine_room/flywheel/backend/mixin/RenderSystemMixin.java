package dev.engine_room.flywheel.backend.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.backend.engine.uniform.FogUniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;

@Mixin(value = RenderSystem.class, remap = false)
abstract class RenderSystemMixin {
	@Inject(method = "initRenderer(IZ)V", at = @At("RETURN"))
	private static void flywheel$onInitRenderer(CallbackInfo ci) {
		GlCompat.init();
	}

	@Inject(method = "setShaderFogStart(F)V", at = @At("RETURN"))
	private static void flywheel$onSetFogStart(CallbackInfo ci) {
		FogUniforms.update();
	}

	@Inject(method = "setShaderFogEnd(F)V", at = @At("RETURN"))
	private static void flywheel$onSetFogEnd(CallbackInfo ci) {
		FogUniforms.update();
	}

	// Fabric fails to resolve the mixin in prod when the full signature is specified.
	// I suspect it's because this method references a class name in its signature,
	// and that needs to be remapped while the function names in RenderSystem are marked with @DontObfuscate.
	@Inject(method = "setShaderFogShape", at = @At("RETURN"))
	private static void flywheel$onSetFogShape(CallbackInfo ci) {
		FogUniforms.update();
	}

	@Inject(method = "setShaderFogColor(FFFF)V", at = @At("RETURN"))
	private static void flywheel$onSetFogColor(CallbackInfo ci) {
		FogUniforms.update();
	}
}
