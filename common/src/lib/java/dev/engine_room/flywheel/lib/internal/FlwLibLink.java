package dev.engine_room.flywheel.lib.internal;

import java.util.Deque;
import java.util.Map;

import org.slf4j.Logger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.engine_room.flywheel.api.internal.DependencyInjection;
import dev.engine_room.flywheel.lib.transform.PoseTransformStack;
import net.minecraft.client.model.geom.ModelPart;

public interface FlwLibLink {
	FlwLibLink INSTANCE = DependencyInjection.load(FlwLibLink.class, "dev.engine_room.flywheel.impl.FlwLibLinkImpl");

	Logger getLogger();

	PoseTransformStack getPoseTransformStackOf(PoseStack stack);

	Map<String, ModelPart> getModelPartChildren(ModelPart part);

	void compileModelPart(ModelPart part, PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay, int color);

	Deque<PoseStack.Pose> getPoseStack(PoseStack stack);

	boolean isIrisLoaded();

	boolean isOptifineInstalled();

	boolean isShaderPackInUse();

	boolean isRenderingShadowPass();
}
