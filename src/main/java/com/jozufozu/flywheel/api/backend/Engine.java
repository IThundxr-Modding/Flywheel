package com.jozufozu.flywheel.api.backend;

import com.jozufozu.flywheel.api.event.RenderContext;
import com.jozufozu.flywheel.api.event.RenderStage;
import com.jozufozu.flywheel.api.instance.InstancerProvider;
import com.jozufozu.flywheel.api.task.Plan;
import com.jozufozu.flywheel.api.task.TaskExecutor;

import net.minecraft.client.Camera;
import net.minecraft.core.Vec3i;

public interface Engine extends InstancerProvider {
	Plan<RenderContext> createFramePlan();

	void renderStage(TaskExecutor executor, RenderContext context, RenderStage stage);

	/**
	 * Maintain the render origin to be within a certain distance from the camera in all directions,
	 * preventing floating point precision issues at high coordinates.
	 *
	 * @return {@code true} if the render origin changed, {@code false} otherwise.
	 */
	boolean updateRenderOrigin(Camera camera);

	Vec3i renderOrigin();

	// TODO: "delete" implies that the object cannot be used afterwards, but all current implementations
	// support the "invalidate" contract as well, meaning they can be reused after this call. Rename?
	void delete();
}
