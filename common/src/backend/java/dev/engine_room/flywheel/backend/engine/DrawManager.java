package dev.engine_room.flywheel.backend.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jetbrains.annotations.Nullable;

import com.mojang.datafixers.util.Pair;

import dev.engine_room.flywheel.api.RenderContext;
import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visualization.VisualType;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.engine.embed.Environment;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.lib.task.ForEachPlan;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.resources.model.ModelBakery;

public abstract class DrawManager<N extends AbstractInstancer<?>> {
	private static final boolean WARN_EMPTY_MODELS = Boolean.getBoolean("flywheel.warnEmptyModels");

	/**
	 * A map of instancer keys to instancers.
	 * <br>
	 * This map is populated as instancers are requested and contains both initialized and uninitialized instancers.
	 */
	protected final Map<InstancerKey<?>, N> instancers = new ConcurrentHashMap<>();
	/**
	 * A list of instancers that have not yet been initialized.
	 * <br>
	 * All new instancers land here before having resources allocated in {@link #flush}.
	 */
	protected final Queue<UninitializedInstancer<N, ?>> initializationQueue = new ConcurrentLinkedQueue<>();

	public <I extends Instance> AbstractInstancer<I> getInstancer(Environment environment, InstanceType<I> type, Model model, VisualType visualType, int bias) {
		return getInstancer(new InstancerKey<>(environment, type, model, visualType, bias));
	}

	@SuppressWarnings("unchecked")
	public <I extends Instance> AbstractInstancer<I> getInstancer(InstancerKey<I> key) {
		return (AbstractInstancer<I>) instancers.computeIfAbsent(key, this::createAndDeferInit);
	}

	public Plan<RenderContext> createFramePlan() {
		// Go wide on instancers to process deletions in parallel.
		return ForEachPlan.of(() -> new ArrayList<>(instancers.values()), AbstractInstancer::parallelUpdate);
	}

	public void flush(LightStorage lightStorage, EnvironmentStorage environmentStorage) {
		// Thread safety: flush is called from the render thread after all visual updates have been made,
		// so there are no:tm: threads we could be racing with.
		for (var init : initializationQueue) {
			var instancer = init.instancer();
			if (instancer.instanceCount() > 0) {
				initialize(init.key(), instancer);
			} else {
				instancers.remove(init.key());
			}
		}
		initializationQueue.clear();
	}

	public void onRenderOriginChanged() {
		instancers.values()
				.forEach(AbstractInstancer::clear);
	}

	public abstract void render(VisualType visualType);

	public abstract void renderCrumbling(List<Engine.CrumblingBlock> crumblingBlocks);

	protected abstract <I extends Instance> N create(InstancerKey<I> type);

	protected abstract <I extends Instance> void initialize(InstancerKey<I> key, N instancer);

	private N createAndDeferInit(InstancerKey<?> key) {
		var out = create(key);

		// Only queue the instancer for initialization if it has anything to render.
		if (checkAndWarnEmptyModel(key.model())) {
			// Thread safety: this method is called atomically from within computeIfAbsent,
			// so you'd think we don't need extra synchronization to protect the queue, but
			// somehow threads can race here and wind up never initializing an instancer.
			initializationQueue.add(new UninitializedInstancer<>(key, out));
		}
		return out;
	}

	private static boolean checkAndWarnEmptyModel(Model model) {
		if (!model.meshes().isEmpty()) {
			return true;
		}

		if (WARN_EMPTY_MODELS) {
			StringBuilder builder = new StringBuilder();
			builder.append("Creating an instancer for a model with no meshes! Stack trace:");

			StackWalker.getInstance()
					.forEach(f -> builder.append("\n\t")
							.append(f.toString()));

			FlwBackend.LOGGER.warn(builder.toString());
		}

		return false;
	}

	@FunctionalInterface
	protected interface State2Instancer<I extends AbstractInstancer<?>> {
		// I tried using a plain Function<State<?>, I> here, but it exploded with type errors.
		@Nullable I apply(InstanceHandleImpl.State<?> state);
	}

	protected static <I extends AbstractInstancer<?>> Map<GroupKey<?>, Int2ObjectMap<List<Pair<I, InstanceHandleImpl<?>>>>> doCrumblingSort(List<Engine.CrumblingBlock> crumblingBlocks, State2Instancer<I> cast) {
		Map<GroupKey<?>, Int2ObjectMap<List<Pair<I, InstanceHandleImpl<?>>>>> byType = new HashMap<>();
		for (Engine.CrumblingBlock block : crumblingBlocks) {
			int progress = block.progress();

			if (progress < 0 || progress >= ModelBakery.DESTROY_TYPES.size()) {
				continue;
			}

			for (Instance instance : block.instances()) {
				// Filter out instances that weren't created by this engine.
				// If all is well, we probably shouldn't take the `continue`
				// branches but better to do checked casts.
				if (!(instance.handle() instanceof InstanceHandleImpl<?> impl)) {
					continue;
				}

				var instancer = cast.apply(impl.state);

				if (instancer == null) {
					continue;
				}

				byType.computeIfAbsent(new GroupKey<>(instancer.type, instancer.environment), $ -> new Int2ObjectArrayMap<>())
						.computeIfAbsent(progress, $ -> new ArrayList<>())
						.add(Pair.of(instancer, impl));
			}
		}
		return byType;
	}

	public void delete() {
		instancers.clear();
		initializationQueue.clear();
	}

	public abstract void triggerFallback();

	protected record UninitializedInstancer<N, I extends Instance>(InstancerKey<I> key, N instancer) {
	}
}
