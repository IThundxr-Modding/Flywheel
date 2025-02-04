package dev.engine_room.flywheel.backend.engine.indirect;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL42.GL_COMMAND_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.glDispatchCompute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualType;
import dev.engine_room.flywheel.backend.compile.ContextShader;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.backend.engine.MaterialRenderState;
import dev.engine_room.flywheel.backend.engine.MeshPool;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.lib.math.MoreMath;

public class IndirectCullingGroup<I extends Instance> {
	private static final Comparator<IndirectDraw> DRAW_COMPARATOR = Comparator.comparing(IndirectDraw::visualType)
			.thenComparing(IndirectDraw::isEmbedded)
			.thenComparing(IndirectDraw::bias)
			.thenComparing(IndirectDraw::indexOfMeshInModel)
			.thenComparing(IndirectDraw::material, MaterialRenderState.COMPARATOR);

	private final InstanceType<I> instanceType;
	private final long instanceStride;
	private final IndirectBuffers buffers;
	private final List<IndirectInstancer<I>> instancers = new ArrayList<>();
	private final List<IndirectDraw> indirectDraws = new ArrayList<>();
	private final Map<VisualType, List<MultiDraw>> multiDraws = new EnumMap<>(VisualType.class);

	private final IndirectPrograms programs;
	private final GlProgram cullProgram;

	private boolean needsDrawBarrier;
	private boolean needsDrawSort;
	private int instanceCountThisFrame;

	IndirectCullingGroup(InstanceType<I> instanceType, IndirectPrograms programs) {
		this.instanceType = instanceType;
		instanceStride = MoreMath.align4(instanceType.layout()
				.byteSize());
		buffers = new IndirectBuffers(instanceStride);

		this.programs = programs;
		cullProgram = programs.getCullingProgram(instanceType);
	}

	public void flushInstancers() {
		instanceCountThisFrame = 0;
		int modelIndex = 0;
        for (var iterator = instancers.iterator(); iterator.hasNext(); ) {
            var instancer = iterator.next();
			var instanceCount = instancer.instanceCount();

			if (instanceCount == 0) {
				iterator.remove();
				instancer.delete();
				continue;
			}

			instancer.update(modelIndex, instanceCountThisFrame);
			instanceCountThisFrame += instanceCount;

			modelIndex++;
        }

        if (indirectDraws.removeIf(IndirectDraw::deleted)) {
			needsDrawSort = true;
		}
	}

	public void upload(StagingBuffer stagingBuffer) {
		if (nothingToDo()) {
			return;
		}

		buffers.updateCounts(instanceCountThisFrame, instancers.size(), indirectDraws.size());

		// Upload only instances that have changed.
		uploadInstances(stagingBuffer);

		buffers.objectStorage.uploadDescriptors(stagingBuffer);

		// We need to upload the models every frame to reset the instance count.
		uploadModels(stagingBuffer);

		if (needsDrawSort) {
			sortDraws();
			needsDrawSort = false;
		}

		uploadDraws(stagingBuffer);

		needsDrawBarrier = true;
	}

	public void dispatchCull() {
		if (nothingToDo()) {
			return;
		}

		Uniforms.bindAll();
		cullProgram.bind();

		buffers.bindForCull();
		glDispatchCompute(buffers.objectStorage.capacity(), 1, 1);
	}

	public void dispatchApply() {
		if (nothingToDo()) {
			return;
		}

		buffers.bindForApply();
		glDispatchCompute(GlCompat.getComputeGroupCount(indirectDraws.size()), 1, 1);
	}

	private boolean nothingToDo() {
		return indirectDraws.isEmpty() || instanceCountThisFrame == 0;
	}

	private boolean nothingToDo(VisualType visualType) {
		return nothingToDo() || !multiDraws.containsKey(visualType);
	}

	private void sortDraws() {
		multiDraws.clear();
		// sort by visual type, then material
		indirectDraws.sort(DRAW_COMPARATOR);

		for (int start = 0, i = 0; i < indirectDraws.size(); i++) {
			var draw1 = indirectDraws.get(i);

			// if the next draw call has a different VisualType or Material, start a new MultiDraw
			if (i == indirectDraws.size() - 1 || incompatibleDraws(draw1, indirectDraws.get(i + 1))) {
				multiDraws.computeIfAbsent(draw1.visualType(), s -> new ArrayList<>())
						.add(new MultiDraw(draw1.material(), draw1.isEmbedded(), start, i + 1));
				start = i + 1;
			}
		}
	}

	private boolean incompatibleDraws(IndirectDraw draw1, IndirectDraw draw2) {
		if (draw1.visualType() != draw2.visualType()) {
			return true;
		}

		if (draw1.isEmbedded() != draw2.isEmbedded()) {
			return true;
		}
		return !MaterialRenderState.materialEquals(draw1.material(), draw2.material());
	}

	public boolean hasVisualType(VisualType visualType) {
		return multiDraws.containsKey(visualType);
	}

	public void add(IndirectInstancer<I> instancer, InstancerKey<I> key, MeshPool meshPool) {
		instancer.mapping = buffers.objectStorage.createMapping();
		instancer.update(instancers.size(), -1);

		instancers.add(instancer);

        List<Model.ConfiguredMesh> meshes = key.model()
				.meshes();
        for (int i = 0; i < meshes.size(); i++) {
            var entry = meshes.get(i);

            MeshPool.PooledMesh mesh = meshPool.alloc(entry.mesh());
            var draw = new IndirectDraw(instancer, entry.material(), mesh, key.visualType(), key.bias(), i);
            indirectDraws.add(draw);
            instancer.addDraw(draw);
        }

		needsDrawSort = true;
	}

	public void submit(VisualType visualType) {
		if (nothingToDo(visualType)) {
			return;
		}

		buffers.bindForDraw();

		drawBarrier();

		GlProgram lastProgram = null;

		for (var multiDraw : multiDraws.get(visualType)) {
			var drawProgram = programs.getIndirectProgram(instanceType, multiDraw.embedded ? ContextShader.EMBEDDED : ContextShader.DEFAULT, multiDraw.material);
			if (drawProgram != lastProgram) {
				lastProgram = drawProgram;

				// Don't need to do this unless the program changes.
				drawProgram.bind();
			}

			MaterialRenderState.setup(multiDraw.material);

			multiDraw.submit(drawProgram);
		}
	}

	public void bindForCrumbling(Material material) {
		var program = programs.getIndirectProgram(instanceType, ContextShader.CRUMBLING, material);

		program.bind();

		buffers.bindForCrumbling();

		drawBarrier();

		program.setUInt("_flw_baseDraw", 0);
	}

	private void drawBarrier() {
		if (needsDrawBarrier) {
			// In theory all command buffer writes will be protected by
			// the shader storage barrier bit, but better safe than sorry.
			glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
			needsDrawBarrier = false;
		}
	}

	private void uploadInstances(StagingBuffer stagingBuffer) {
		for (var instancer : instancers) {
			instancer.uploadInstances(stagingBuffer, buffers.objectStorage.objectBuffer.handle());
		}
	}

	private void uploadModels(StagingBuffer stagingBuffer) {
		var totalSize = instancers.size() * IndirectBuffers.MODEL_STRIDE;
		var handle = buffers.model.handle();

		stagingBuffer.enqueueCopy(totalSize, handle, 0, this::writeModels);
	}

	private void uploadDraws(StagingBuffer stagingBuffer) {
		var totalSize = indirectDraws.size() * IndirectBuffers.DRAW_COMMAND_STRIDE;
		var handle = buffers.draw.handle();

		stagingBuffer.enqueueCopy(totalSize, handle, 0, this::writeCommands);
	}

	private void writeModels(long writePtr) {
		for (var model : instancers) {
			model.writeModel(writePtr);
			writePtr += IndirectBuffers.MODEL_STRIDE;
		}
	}

	private void writeCommands(long writePtr) {
		for (var draw : indirectDraws) {
			draw.write(writePtr);
			writePtr += IndirectBuffers.DRAW_COMMAND_STRIDE;
		}
	}

	public void delete() {
		buffers.delete();
	}

	public boolean checkEmptyAndDelete() {
		var out = indirectDraws.isEmpty();

		if (out) {
			delete();
		}

		return out;
	}

	private record MultiDraw(Material material, boolean embedded, int start, int end) {
		private void submit(GlProgram drawProgram) {
			GlCompat.safeMultiDrawElementsIndirect(drawProgram, GL_TRIANGLES, GL_UNSIGNED_INT, this.start, this.end, IndirectBuffers.DRAW_COMMAND_STRIDE);
		}
	}
}
