package dev.engine_room.flywheel.backend.compile;

import java.util.Collection;
import java.util.List;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.InternalVertex;
import dev.engine_room.flywheel.backend.MaterialShaderIndices;
import dev.engine_room.flywheel.backend.Samplers;
import dev.engine_room.flywheel.backend.compile.component.InstanceStructComponent;
import dev.engine_room.flywheel.backend.compile.component.UberShaderComponent;
import dev.engine_room.flywheel.backend.compile.core.CompilationHarness;
import dev.engine_room.flywheel.backend.compile.core.Compile;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.gl.shader.ShaderType;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.backend.glsl.generate.FnSignature;
import dev.engine_room.flywheel.backend.glsl.generate.GlslExpr;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.resources.ResourceLocation;

public final class PipelineCompiler {
	private static final Compile<PipelineProgramKey> PIPELINE = new Compile<>();

	private static UberShaderComponent FOG;
	private static UberShaderComponent CUTOUT;

	private static final ResourceLocation API_IMPL_VERT = Flywheel.rl("internal/api_impl.vert");
	private static final ResourceLocation API_IMPL_FRAG = Flywheel.rl("internal/api_impl.frag");

	static CompilationHarness<PipelineProgramKey> create(ShaderSources sources, Pipeline pipeline, List<SourceComponent> vertexComponents, List<SourceComponent> fragmentComponents, Collection<String> extensions) {
		// We could technically compile every version of light smoothness ahead of time,
		// but that seems unnecessary as I doubt most folks will be changing this option often.
		var lightSmoothness = BackendConfig.INSTANCE.lightSmoothness();
		return PIPELINE.program()
				.link(PIPELINE.shader(GlCompat.MAX_GLSL_VERSION, ShaderType.VERTEX)
						.nameMapper(key -> {
							var instance = ResourceUtil.toDebugFileNameNoExtension(key.instanceType()
									.vertexShader());

							var material = ResourceUtil.toDebugFileNameNoExtension(key.materialShaders()
									.vertexSource());
							var context = key.contextShader()
									.nameLowerCase();
							var debug = key.debugEnabled() ? "_debug" : "";
							return "pipeline/" + pipeline.compilerMarker() + "/" + instance + "/" + material + "_" + context + debug;
						})
						.requireExtensions(extensions)
						.onCompile((key, comp) -> key.contextShader()
								.onCompile(comp))
						.onCompile((key, comp) -> lightSmoothness.onCompile(comp))
						.onCompile((key, comp) -> {
							if (key.debugEnabled()) {
								comp.define("_FLW_DEBUG");
							}
						})
						.withResource(API_IMPL_VERT)
						.withComponent(key -> new InstanceStructComponent(key.instanceType()))
						.withResource(key -> key.instanceType()
								.vertexShader())
						.withResource(key -> key.materialShaders()
								.vertexSource())
						.withComponents(vertexComponents)
						.withResource(InternalVertex.LAYOUT_SHADER)
						.withComponent(key -> pipeline.assembler()
								.assemble(key.instanceType()))
						.withResource(pipeline.vertexMain()))
				.link(PIPELINE.shader(GlCompat.MAX_GLSL_VERSION, ShaderType.FRAGMENT)
						.nameMapper(key -> {
							var context = key.contextShader()
									.nameLowerCase();

							var material = ResourceUtil.toDebugFileNameNoExtension(key.materialShaders()
									.fragmentSource());

							var cutout = ResourceUtil.toDebugFileNameNoExtension(key.cutout()
									.source());

							var light = ResourceUtil.toDebugFileNameNoExtension(key.light()
									.source());
							var debug = key.debugEnabled() ? "_debug" : "";
							return "pipeline/" + pipeline.compilerMarker() + "/frag/" + material + "/" + light + "_" + cutout + "_" + context + debug;
						})
						.requireExtensions(extensions)
						.enableExtension("GL_ARB_conservative_depth")
						.onCompile((key, comp) -> key.contextShader()
								.onCompile(comp))
						.onCompile((key, comp) -> lightSmoothness.onCompile(comp))
						.onCompile((key, comp) -> {
							if (key.debugEnabled()) {
								comp.define("_FLW_DEBUG");
							}
						})
						.onCompile((key, comp) -> {
							if (key.cutout() != CutoutShaders.OFF) {
								comp.define("_FLW_USE_DISCARD");
							}
						})
						.withResource(API_IMPL_FRAG)
						.withResource(key -> key.materialShaders()
								.fragmentSource())
						.withComponents(fragmentComponents)
						.withComponent(key -> FOG)
						.withResource(key -> key.light()
								.source())
						.withResource(key -> key.cutout()
								.source())
						.withResource(pipeline.fragmentMain()))
				.preLink((key, program) -> {
					program.bindAttribLocation("_flw_aPos", 0);
					program.bindAttribLocation("_flw_aColor", 1);
					program.bindAttribLocation("_flw_aTexCoord", 2);
					program.bindAttribLocation("_flw_aOverlay", 3);
					program.bindAttribLocation("_flw_aLight", 4);
					program.bindAttribLocation("_flw_aNormal", 5);
				})
				.postLink((key, program) -> {
					Uniforms.setUniformBlockBindings(program);

					program.bind();

					program.setSamplerBinding("flw_diffuseTex", Samplers.DIFFUSE);
					program.setSamplerBinding("flw_overlayTex", Samplers.OVERLAY);
					program.setSamplerBinding("flw_lightTex", Samplers.LIGHT);
					pipeline.onLink()
							.accept(program);
					key.contextShader()
							.onLink(program);

					GlProgram.unbind();
				})
				.harness(pipeline.compilerMarker(), sources);
	}

	public static void createFogComponent() {
		FOG = UberShaderComponent.builder(Flywheel.rl("fog"))
				.materialSources(MaterialShaderIndices.fogSources()
						.all())
				.adapt(FnSignature.create()
						.returnType("vec4")
						.name("flw_fogFilter")
						.arg("vec4", "color")
						.build(), GlslExpr.variable("color"))
				.switchOn(GlslExpr.variable("_flw_uberFogIndex"))
				.build(FlwPrograms.SOURCES);
	}

	private static void createCutoutComponent() {
		CUTOUT = UberShaderComponent.builder(Flywheel.rl("cutout"))
				.materialSources(MaterialShaderIndices.cutoutSources()
						.all())
				.adapt(FnSignature.create()
						.returnType("bool")
						.name("flw_discardPredicate")
						.arg("vec4", "color")
						.build(), GlslExpr.boolLiteral(false))
				.switchOn(GlslExpr.variable("_flw_uberCutoutIndex"))
				.build(FlwPrograms.SOURCES);
	}
}
