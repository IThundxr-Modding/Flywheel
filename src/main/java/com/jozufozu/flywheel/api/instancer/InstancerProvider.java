package com.jozufozu.flywheel.api.instancer;

import com.jozufozu.flywheel.api.event.RenderStage;
import com.jozufozu.flywheel.api.model.Model;
import com.jozufozu.flywheel.api.struct.StructType;

public interface InstancerProvider {
	/**
	 * Get an instancer for the given struct type, model, and render stage. Calling this method twice with the same arguments will return the same instancer.
	 *
	 * @return An instancer for the given struct type, model, and render stage.
	 */
	<P extends InstancePart> Instancer<P> instancer(StructType<P> type, Model model, RenderStage stage);
}
