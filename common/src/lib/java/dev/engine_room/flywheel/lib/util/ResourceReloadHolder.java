package dev.engine_room.flywheel.lib.util;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

public final class ResourceReloadHolder<T> implements Supplier<T> {
	private static final Set<ResourceReloadHolder<?>> ALL = Collections.newSetFromMap(new WeakHashMap<>());
	private final Supplier<T> factory;
	@Nullable
	private volatile T obj;

	public ResourceReloadHolder(Supplier<T> factory) {
		this.factory = factory;

		synchronized (ALL) {
			ALL.add(this);
		}
	}

	@Override
	public final T get() {
		T obj = this.obj;

		if (obj == null) {
			synchronized (this) {
				obj = this.obj;
				if (obj == null) {
					this.obj = obj = factory.get();
				}
			}
		}

		return obj;
	}

	public final void clear() {
		T obj = this.obj;

		if (obj != null) {
			synchronized (this) {
				obj = this.obj;
				if (obj != null) {
					this.obj = null;
				}
			}
		}
	}

	@ApiStatus.Internal
	public static void onEndClientResourceReload() {
		for (ResourceReloadHolder<?> holder : ALL) {
			holder.clear();
		}
	}
}
