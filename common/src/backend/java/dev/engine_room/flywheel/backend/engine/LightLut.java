package dev.engine_room.flywheel.backend.engine;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.core.SectionPos;

public final class LightLut {
	private final Layer<Layer<IntLayer>> indices = new Layer<>();

	private LightLut() {
	}

	private void add(long position, int index) {
		final var x = SectionPos.x(position);
		final var y = SectionPos.y(position);
		final var z = SectionPos.z(position);

		indices.computeIfAbsent(x, Layer::new)
				.computeIfAbsent(y, IntLayer::new)
				.set(z, index + 1);
	}

	private IntArrayList toLut() {
		final var out = new IntArrayList();
		indices.fillLut(out, (yIndices, lut) -> yIndices.fillLut(lut, IntLayer::fillLut));
		return out;
	}

	// Massive kudos to RogueLogix for figuring out this LUT scheme.
	// TODO: switch to y x z or x z y ordering
	// DATA LAYOUT
	// [0] : base chunk X, X index count, followed by linear indices of y blocks
	// [yBlockIndex] : baseChunk Y, Y index count, followed by linear indices of z blocks for this x
	// [zBlockIndex] : baseChunk Z, Z index count, followed by linear indices of lighting chunks
	// this data layout allows a single buffer to represent the lighting volume, without requiring the entire 3d lookup volume to be allocated
	public static IntArrayList buildLut(Long2IntMap sectionIndicesMaps) {
		if (sectionIndicesMaps.isEmpty()) {
			return new IntArrayList();
		}

		var out = new LightLut();

		sectionIndicesMaps.forEach(out::add);

		return out.toLut();
	}

	private static final class Layer<T> {
		private boolean hasBase = false;
		private int base = 0;
		private Object[] nextLayer = new Object[0];

		public void fillLut(IntArrayList lut, BiConsumer<T, IntArrayList> inner) {
			lut.add(base);
			lut.add(nextLayer.length);

			int innerIndexBase = lut.size();

			// Reserve space for the inner indices...
			lut.size(innerIndexBase + nextLayer.length);

			for (int i = 0; i < nextLayer.length; i++) {
				final var innerIndices = (T) nextLayer[i];
				if (innerIndices == null) {
					continue;
				}

				int layerPosition = lut.size();

				// ...so we can write in their actual positions later.
				lut.set(innerIndexBase + i, layerPosition);

				// Append the next layer to the lut.
				inner.accept(innerIndices, lut);
			}
		}

		public T computeIfAbsent(int i, Supplier<T> ifAbsent) {
			if (!hasBase) {
				// We don't want to default to base 0, so we'll use the first value we get.
				base = i;
				hasBase = true;
			}

			if (i < base) {
				rebase(i);
			}

			final var offset = i - base;

			if (offset >= nextLayer.length) {
				resize(offset + 1);
			}

			var out = nextLayer[offset];

			if (out == null) {
				out = ifAbsent.get();
				nextLayer[offset] = out;
			}
			return (T) out;
		}

		private void resize(int length) {
			final var newIndices = new Object[length];
			System.arraycopy(nextLayer, 0, newIndices, 0, nextLayer.length);
			nextLayer = newIndices;
		}

		private void rebase(int newBase) {
			final var growth = base - newBase;

			final var newIndices = new Object[nextLayer.length + growth];
			// Shift the existing elements to the end of the new array to maintain their offset with the new base.
			System.arraycopy(nextLayer, 0, newIndices, growth, nextLayer.length);

			nextLayer = newIndices;
			base = newBase;
		}
	}

	private static final class IntLayer {
		private boolean hasBase = false;
		private int base = 0;
		private int[] indices = new int[0];

		public void fillLut(IntArrayList lut) {
			lut.add(base);
			lut.add(indices.length);

			for (int index : indices) {
				lut.add(index);
			}
		}

		public void set(int i, int index) {
			if (!hasBase) {
				base = i;
				hasBase = true;
			}

			if (i < base) {
				rebase(i);
			}

			final var offset = i - base;

			if (offset >= indices.length) {
				resize(offset + 1);
			}

			indices[offset] = index;
		}

		private void resize(int length) {
			final var newIndices = new int[length];
			System.arraycopy(indices, 0, newIndices, 0, indices.length);
			indices = newIndices;
		}

		private void rebase(int newBase) {
			final var growth = base - newBase;

			final var newIndices = new int[indices.length + growth];
			System.arraycopy(indices, 0, newIndices, growth, indices.length);

			indices = newIndices;
			base = newBase;
		}
	}
}
