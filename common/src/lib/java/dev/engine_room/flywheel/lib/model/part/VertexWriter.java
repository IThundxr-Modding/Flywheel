package dev.engine_room.flywheel.lib.model.part;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.engine_room.flywheel.lib.math.RenderMath;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.part.ModelPartConverter.TextureMapper;
import dev.engine_room.flywheel.lib.vertex.PosTexNormalVertexView;

class VertexWriter implements VertexConsumer {
	private static final int STRIDE = (int) PosTexNormalVertexView.STRIDE;

	private MemoryBlock data;

	@Nullable
	private TextureMapper textureMapper;
	private final Vector2f uvVec = new Vector2f();

	private int vertexCount;
	private boolean filledPosition;
	private boolean filledTexture;
	private boolean filledNormal;

	public VertexWriter() {
		data = MemoryBlock.malloc(128 * STRIDE);
	}

	public void setTextureMapper(@Nullable TextureMapper mapper) {
		textureMapper = mapper;
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z) {
		if (!filledPosition) {
			long ptr = vertexPtr();
			MemoryUtil.memPutFloat(ptr, x);
			MemoryUtil.memPutFloat(ptr + 4, y);
			MemoryUtil.memPutFloat(ptr + 8, z);
			filledPosition = true;
		}
		return this;
	}

	@Override
	public VertexConsumer setColor(int red, int green, int blue, int alpha) {
		// ignore color
		return this;
	}

	@Override
	public VertexConsumer setUv(float u, float v) {
		if (!filledTexture) {
			if (textureMapper != null) {
				uvVec.set(u, v);
				textureMapper.map(uvVec);
				u = uvVec.x;
				v = uvVec.y;
			}

			long ptr = vertexPtr();
			MemoryUtil.memPutFloat(ptr + 12, u);
			MemoryUtil.memPutFloat(ptr + 16, v);
			filledTexture = true;
		}
		return this;
	}

	@Override
	public VertexConsumer setUv1(int u, int v) {
		// ignore overlay
		return this;
	}

	@Override
	public VertexConsumer setUv2(int u, int v) {
		// ignore light
		return this;
	}

	@Override
	public VertexConsumer setNormal(float x, float y, float z) {
		if (!filledNormal) {
			long ptr = vertexPtr();
			MemoryUtil.memPutByte(ptr + 20, RenderMath.nb(x));
			MemoryUtil.memPutByte(ptr + 21, RenderMath.nb(y));
			MemoryUtil.memPutByte(ptr + 22, RenderMath.nb(z));
			filledNormal = true;
		}
		return this;
	}

	private long vertexPtr() {
		return data.ptr() + vertexCount * STRIDE;
	}

	public MemoryBlock copyDataAndReset() {
		MemoryBlock dataCopy = MemoryBlock.malloc(vertexCount * STRIDE);
		data.copyTo(dataCopy);

		vertexCount = 0;
		filledPosition = false;
		filledTexture = false;
		filledNormal = false;
		textureMapper = null;

		return dataCopy;
	}
}
