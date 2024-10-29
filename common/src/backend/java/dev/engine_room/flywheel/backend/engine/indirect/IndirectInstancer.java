package dev.engine_room.flywheel.backend.engine.indirect;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceWriter;
import dev.engine_room.flywheel.backend.engine.AbstractInstancer;
import dev.engine_room.flywheel.backend.engine.InstanceHandleImpl;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.backend.util.AtomicBitSet;
import dev.engine_room.flywheel.lib.math.MoreMath;

public class IndirectInstancer<I extends Instance> extends AbstractInstancer<I> {
	private final AtomicReference<InstancePage[]> pages;

	private final long instanceStride;
	private final InstanceWriter<I> writer;
	private final List<IndirectDraw> associatedDraws = new ArrayList<>();
	private final Vector4fc boundingSphere;

	private final AtomicBitSet changedPages = new AtomicBitSet();
	private final AtomicBitSet fullPages = new AtomicBitSet();
	private final Class<I> instanceClass;

	public ObjectStorage.@UnknownNullability Mapping mapping;

	private int modelIndex = -1;
	private int baseInstance = -1;

	public IndirectInstancer(InstancerKey<I> key, Recreate<I> recreate) {
		super(key, recreate);
		instanceStride = MoreMath.align4(type.layout()
				.byteSize());
		writer = this.type.writer();
		boundingSphere = key.model().boundingSphere();

		instanceClass = (Class<I>) type.create(new InstanceHandleImpl<I>(null))
				.getClass();
		pages = new AtomicReference<>((InstancePage[]) Array.newInstance(InstancePage.class, 0));
	}

	public final class InstancePage implements InstanceHandleImpl.State<I> {
		private final int pageNo;
		private final I[] instances;
		private final InstanceHandleImpl<I>[] handles;
		/**
		 * A bitset describing which indices in the instances/handles arrays contain live instances.
		 */
		private final AtomicInteger valid;

		InstancePage(Class<I> clazz, int pageNo) {
			this.pageNo = pageNo;
			this.instances = (I[]) Array.newInstance(clazz, ObjectStorage.PAGE_SIZE);
			this.handles = (InstanceHandleImpl<I>[]) new InstanceHandleImpl[ObjectStorage.PAGE_SIZE];
			this.valid = new AtomicInteger(0);
		}

		public boolean add(I instance, InstanceHandleImpl<I> handle) {
			// Thread safety: we loop until we either win the race and add the given instance, or we
			// run out of space because other threads trying to add at the same time.
			while (true) {
				int currentValue = valid.get();
				if (currentValue == 0xFFFFFFFF) {
					// The page is full, must search elsewhere
					return false;
				}

				// determine what the new long value will be after we set the appropriate bit.
				int index = Integer.numberOfTrailingZeros(~currentValue);

				int newValue = currentValue | (1 << index);

				// if no other thread has modified the value since we read it, we won the race and we are done.
				if (valid.compareAndSet(currentValue, newValue)) {
					instances[index] = instance;
					handles[index] = handle;
					handle.state = this;
					handle.index = (pageNo << ObjectStorage.LOG_2_PAGE_SIZE) + index;

					changedPages.set(pageNo);
					if (newValue == 0xFFFFFFFF) {
						fullPages.set(pageNo);
					}
					return true;
				}
			}
		}

		@Override
		public InstanceHandleImpl.State<I> setChanged(int index) {
			changedPages.set(pageNo);
			return this;
		}

		@Override
		public InstanceHandleImpl.State<I> setDeleted(int index) {
			int localIndex = index % ObjectStorage.PAGE_SIZE;

			instances[localIndex] = null;
			handles[localIndex] = null;

			while (true) {
				int currentValue = valid.get();
				int newValue = currentValue & ~(1 << localIndex);

				if (valid.compareAndSet(currentValue, newValue)) {
					fullPages.clear(pageNo);
					return InstanceHandleImpl.Deleted.instance();
				}
			}
		}

		@Override
		public InstanceHandleImpl.State<I> setVisible(InstanceHandleImpl<I> handle, int index, boolean visible) {
			if (visible) {
				return this;
			}

			int localIndex = index % ObjectStorage.PAGE_SIZE;

			return new InstanceHandleImpl.Hidden<>(recreate, instances[localIndex]);
		}
	}

	public void addDraw(IndirectDraw draw) {
		associatedDraws.add(draw);
	}

	public List<IndirectDraw> draws() {
		return associatedDraws;
	}

	public void update(int modelIndex, int baseInstance) {
		this.baseInstance = baseInstance;

		if (this.modelIndex == modelIndex && changedPages.isEmpty()) {
			return;
		}
		this.modelIndex = modelIndex;
		var pages = this.pages.get();
		mapping.updateCount(pages.length);

		for (int i = 0; i < pages.length; i++) {
			mapping.updatePage(i, modelIndex, pages[i].valid.get());
		}
	}

	public void writeModel(long ptr) {
		MemoryUtil.memPutInt(ptr, 0); // instanceCount - to be incremented by the cull shader
		MemoryUtil.memPutInt(ptr + 4, baseInstance); // baseInstance
		MemoryUtil.memPutInt(ptr + 8, environment.matrixIndex()); // matrixIndex
		MemoryUtil.memPutFloat(ptr + 12, boundingSphere.x()); // boundingSphere
		MemoryUtil.memPutFloat(ptr + 16, boundingSphere.y());
		MemoryUtil.memPutFloat(ptr + 20, boundingSphere.z());
		MemoryUtil.memPutFloat(ptr + 24, boundingSphere.w());
	}

	public void uploadInstances(StagingBuffer stagingBuffer, int instanceVbo) {
		if (changedPages.isEmpty()) {
			return;
		}

		var pages = this.pages.get();
		for (int page = changedPages.nextSetBit(0); page >= 0 && page < pages.length; page = changedPages.nextSetBit(page + 1)) {
			var instances = pages[page].instances;

			long baseByte = mapping.page2ByteOffset(page);
			long size = ObjectStorage.PAGE_SIZE * instanceStride;

			// Because writes are broken into pages, we end up with significantly more calls into
			// StagingBuffer#enqueueCopy and the allocations for the writer got out of hand. Here
			// we've inlined the enqueueCopy call and do not allocate the write lambda at all.
			// Doing so cut upload times in half.

			// Try to write directly into the staging buffer if there is enough contiguous space.
			long direct = stagingBuffer.reserveForCopy(size, instanceVbo, baseByte);

			if (direct != MemoryUtil.NULL) {
				for (I instance : instances) {
					if (instance != null) {
						writer.write(direct, instance);
					}
					direct += instanceStride;
				}
				continue;
			}

			// Otherwise, write to a scratch buffer and enqueue a copy.
			var block = stagingBuffer.getScratch(size);
			var ptr = block.ptr();
			for (I instance : instances) {
				if (instance != null) {
					writer.write(ptr, instance);
				}
				ptr += instanceStride;
			}
			stagingBuffer.enqueueCopy(block.ptr(), size, instanceVbo, baseByte);
		}

		changedPages.clear();
	}

	public void removeDeletedInstances() {

	}

	@Override
	public void delete() {
		for (IndirectDraw draw : draws()) {
			draw.delete();
		}

		mapping.delete();
	}

	public int modelIndex() {
		return modelIndex;
	}

	public int baseInstance() {
		return baseInstance;
	}

	public int local2GlobalInstanceIndex(int instanceIndex) {
		return mapping.objectIndex2GlobalIndex(instanceIndex);
	}

	@Override
	public I createInstance() {
		var handle = new InstanceHandleImpl<I>(null);
		I instance = type.create(handle);

		addInner(instance, handle);

		return instance;
	}

	public InstanceHandleImpl.State<I> revealInstance(InstanceHandleImpl<I> handle, I instance) {
		addInner(instance, handle);
		return handle.state;
	}

	@Override
	public void stealInstance(@Nullable I instance) {
		if (instance == null) {
			return;
		}

		var instanceHandle = instance.handle();

		if (!(instanceHandle instanceof InstanceHandleImpl<?>)) {
			// UB: do nothing
			return;
		}

		// Should InstanceType have an isInstance method?
		@SuppressWarnings("unchecked") var handle = (InstanceHandleImpl<I>) instanceHandle;

		// Not allowed to steal deleted instances.
		if (handle.state instanceof InstanceHandleImpl.Deleted) {
			return;
		}
		// No need to steal if the instance will recreate to us.
		if (handle.state instanceof InstanceHandleImpl.Hidden<I> hidden && recreate.equals(hidden.recreate())) {
			return;
		}

		// FIXME: in theory there could be a race condition here if the instance
		//  is somehow being stolen by 2 different instancers between threads.
		//  That seems kinda impossible so I'm fine leaving it as is for now.

		// Add the instance to this instancer.
		if (handle.state instanceof InstancePage other) {
			// TODO: shortcut here if we already own the instance

			// Remove the instance from its old instancer.
			// This won't have any unwanted effect when the old instancer
			// is filtering deleted instances later, so is safe.
			other.setDeleted(handle.index);

			// Only lock now that we'll be mutating our state.
			addInner(instance, handle);
		} else if (handle.state instanceof InstanceHandleImpl.Hidden<I>) {
			handle.state = new InstanceHandleImpl.Hidden<>(recreate, instance);
		}
	}

	private void addInner(I instance, InstanceHandleImpl<I> handle) {
		// Outer loop:
		// - try to find an empty space
		// - or grow the page array if we can't
		// - add the instance to the new page, or try again
		while (true) {
			var pages = this.pages.get();

			// First, try to find a page with space.
			for (int i = fullPages.nextClearBit(0); i < pages.length; i = fullPages.nextClearBit(i + 1)) {
				// It may have been filled in while we were searching, but hopefully not.
				if (pages[i].add(instance, handle)) {
					return;
				}
			}

			// If we're here, all other pages are full
			// If we hit this on the second iteration of the outer loop then `pages` is once again full.
			var desiredLength = pages.length + 1;

			// Inner loop: grow the page array. This is very similar to the logic in AtomicBitSet.
			while (pages.length < desiredLength) {
				// Thread safety: segments contains all pages from the currently visible pages, plus extra.
				// all pages in the currently visible pages are canonical and will not change.
				// Can't just `new InstancePage[]` because it has a generic parameter.
				InstancePage[] newPages = (InstancePage[]) Array.newInstance(InstancePage.class, desiredLength);

				System.arraycopy(pages, 0, newPages, 0, pages.length);
				newPages[pages.length] = new InstancePage(instanceClass, pages.length);

				// because we are using a compareAndSet, if this thread "wins the race" and successfully sets this variable, then the new page becomes canonical.
				if (this.pages.compareAndSet(pages, newPages)) {
					pages = newPages;
				} else {
					// If we "lose the race" and are growing the AtomicBitset segments larger,
					// then we will gather the new canonical pages from the update which we missed on the next iteration of this loop.
					// The new page will be discarded and never seen again.
					pages = this.pages.get();
				}
			}

			// Shortcut: try to add the instance to the last page.
			// Technically we could just let the outer loop go again, but that
			// involves a good bit of work just to likely get back here.
			if (pages[pages.length - 1].add(instance, handle)) {
				return;
			}
			// It may be the case that many other instances were added in the same instant.
			// We can still lose this race, though it is very unlikely.
		}
	}

	public int instanceCount() {
		// Not exactly accurate but it's an upper bound.
		// TODO: maybe this could be tracked with an AtomicInteger?
		return pages.get().length << ObjectStorage.LOG_2_PAGE_SIZE;
	}

	/**
	 * Clear all instances without freeing resources.
	 */
	public void clear() {

	}
}
