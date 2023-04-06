package com.jozufozu.flywheel.lib.struct;

import com.jozufozu.flywheel.api.instancer.Handle;
import com.jozufozu.flywheel.api.layout.BufferLayout;
import com.jozufozu.flywheel.api.struct.StructType;
import com.jozufozu.flywheel.api.struct.StructWriter;
import com.jozufozu.flywheel.lib.layout.CommonItems;
import com.jozufozu.flywheel.util.RenderMath;
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;
import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;
import com.mojang.math.Vector4f;

import net.minecraft.resources.ResourceLocation;

public class OrientedType implements StructType<OrientedPart> {

	public static final BufferLayout FORMAT = BufferLayout.builder()
			.addItem(CommonItems.LIGHT_COORD, "light")
			.addItem(CommonItems.UNORM_4x8, "color")
			.addItem(CommonItems.VEC3, "position")
			.addItem(CommonItems.VEC3, "pivot")
			.addItem(CommonItems.VEC4, "rotation")
			.build();

	@Override
	public OrientedPart create(Handle handle) {
		return new OrientedPart(handle);
	}

	@Override
	public BufferLayout getLayout() {
		return FORMAT;
	}

	@Override
	public StructWriter<OrientedPart> getWriter() {
		return OrientedWriter.INSTANCE;
	}

	@Override
	public ResourceLocation instanceShader() {
		return StructTypes.Files.ORIENTED;
	}

	@Override
	public VertexTransformer<OrientedPart> getVertexTransformer() {
		return (vertexList, struct, level) -> {
			Vector4f pos = new Vector4f();
			Vector3f normal = new Vector3f();

			Quaternion q = new Quaternion(struct.qX, struct.qY, struct.qZ, struct.qW);

			Matrix4f modelMatrix = new Matrix4f();
			modelMatrix.setIdentity();
			modelMatrix.multiplyWithTranslation(struct.posX + struct.pivotX, struct.posY + struct.pivotY, struct.posZ + struct.pivotZ);
			modelMatrix.multiply(q);
			modelMatrix.multiplyWithTranslation(-struct.pivotX, -struct.pivotY, -struct.pivotZ);

			Matrix3f normalMatrix = new Matrix3f(q);

			float r = RenderMath.uf(struct.r);
			float g = RenderMath.uf(struct.g);
			float b = RenderMath.uf(struct.b);
			float a = RenderMath.uf(struct.a);
			int light = struct.getPackedLight();

			for (int i = 0; i < vertexList.vertexCount(); i++) {
				pos.set(
						vertexList.x(i),
						vertexList.y(i),
						vertexList.z(i),
						1f
				);
				pos.transform(modelMatrix);
				vertexList.x(i, pos.x());
				vertexList.y(i, pos.y());
				vertexList.z(i, pos.z());

				normal.set(
						vertexList.normalX(i),
						vertexList.normalY(i),
						vertexList.normalZ(i)
				);
				normal.transform(normalMatrix);
				normal.normalize();
				vertexList.normalX(i, normal.x());
				vertexList.normalY(i, normal.y());
				vertexList.normalZ(i, normal.z());

				vertexList.r(i, r);
				vertexList.g(i, g);
				vertexList.b(i, b);
				vertexList.a(i, a);
				vertexList.light(i, light);
			}
		};
	}
}
