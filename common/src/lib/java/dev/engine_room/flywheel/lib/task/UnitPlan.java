package dev.engine_room.flywheel.lib.task;

import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.task.TaskExecutor;

public final class UnitPlan<C> implements Plan<C> {
	private static final UnitPlan<?> INSTANCE = new UnitPlan<>();

	private UnitPlan() {
	}

	@SuppressWarnings("unchecked")
	public static <C> UnitPlan<C> of() {
		return (UnitPlan<C>) INSTANCE;
	}

	@Override
	public void execute(TaskExecutor taskExecutor, C context, Runnable onCompletion) {
		onCompletion.run();
	}

	@Override
	public Plan<C> then(Plan<C> plan) {
		return plan;
	}

	@Override
	public Plan<C> and(Plan<C> plan) {
		return plan;
	}
}
