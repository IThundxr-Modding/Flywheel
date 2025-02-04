package dev.engine_room.flywheel.backend.util;

import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;

// https://github.com/Netflix/hollow/blob/master/hollow/src/main/java/com/netflix/hollow/core/memory/ThreadSafeBitSet.java
// Refactored to remove unused methods, deduplicate some code segments, and add extra functionality with #forEachSetSpan
public class AtomicBitSet {
	// 1024 bits, 128 bytes, 16 longs per segment
	public static final int DEFAULT_LOG2_SEGMENT_SIZE_IN_BITS = 10;

	private static final long WORD_MASK = 0xffffffffffffffffL;

	private final int numLongsPerSegment;
	private final int log2SegmentSize;
	private final int segmentMask;
	private final AtomicReference<AtomicBitSetSegments> segments;

	public AtomicBitSet() {
		this(DEFAULT_LOG2_SEGMENT_SIZE_IN_BITS);
	}

	public AtomicBitSet(int log2SegmentSizeInBits) {
		this(log2SegmentSizeInBits, 0);
	}

	public AtomicBitSet(int log2SegmentSizeInBits, int numBitsToPreallocate) {
		if (log2SegmentSizeInBits < 6) {
			throw new IllegalArgumentException("Cannot specify fewer than 64 bits in each segment!");
		}

		this.log2SegmentSize = log2SegmentSizeInBits;
		this.numLongsPerSegment = (1 << (log2SegmentSizeInBits - 6));
		this.segmentMask = numLongsPerSegment - 1;

		long numBitsPerSegment = numLongsPerSegment * 64L;
		int numSegmentsToPreallocate = numBitsToPreallocate == 0 ? 1 : (int) (((numBitsToPreallocate - 1) / numBitsPerSegment) + 1);

		segments = new AtomicReference<>(new AtomicBitSetSegments(numSegmentsToPreallocate, numLongsPerSegment));
	}

	public void set(int position, boolean value) {
		if (value) {
			set(position);
		} else {
			clear(position);
		}
	}

	public void set(int position) {
		int longPosition = longIndexInSegmentForPosition(position);

		AtomicLongArray segment = getSegmentForPosition(position);

		setOr(segment, longPosition, maskForPosition(position));
	}

	public void clear(int position) {
		int longPosition = longIndexInSegmentForPosition(position);

		AtomicLongArray segment = getSegmentForPosition(position);

		setAnd(segment, longPosition, ~maskForPosition(position));
	}

	public void set(int fromIndex, int toIndex) {
		if (fromIndex == toIndex) {
			return;
		}

		int firstSegmentIndex = segmentIndexForPosition(fromIndex);
		int toSegmentIndex = segmentIndexForPosition(toIndex);

		var segments = expandToFit(toSegmentIndex);

		int fromLongIndex = longIndexInSegmentForPosition(fromIndex);
		int toLongIndex = longIndexInSegmentForPosition(toIndex);

		long fromLongMask = WORD_MASK << fromIndex;
		long toLongMask = WORD_MASK >>> -toIndex;

		var segment = segments.getSegment(firstSegmentIndex);

		if (firstSegmentIndex == toSegmentIndex) {
			// Case 1: One segment

			if (fromLongIndex == toLongIndex) {
				// Case 1A: One Long
				setOr(segment, fromLongIndex, (fromLongMask & toLongMask));
			} else {
				// Case 1B: Multiple Longs
				// Handle first word
				setOr(segment, fromLongIndex, fromLongMask);

				// Handle intermediate words, if any
				for (int i = fromLongIndex + 1; i < toLongIndex; i++) {
					segment.set(i, WORD_MASK);
				}

				// Handle last word
				setOr(segment, toLongIndex, toLongMask);
			}
		} else {
			// Case 2: Multiple Segments
			// Handle first segment

			// Handle first word
			setOr(segment, fromLongIndex, fromLongMask);

			// Handle trailing words, if any
			for (int i = fromLongIndex + 1; i < numLongsPerSegment; i++) {
				segment.set(i, WORD_MASK);
			}

			// Nuke intermediate segments, if any
			for (int i = firstSegmentIndex + 1; i < toSegmentIndex; i++) {
				segment = segments.getSegment(i);

				for (int j = 0; j < segment.length(); j++) {
					segment.set(j, WORD_MASK);
				}
			}

			// Handle last segment
			segment = segments.getSegment(toSegmentIndex);

			// Handle leading words, if any
			for (int i = 0; i < toLongIndex; i++) {
				segment.set(i, WORD_MASK);
			}

			// Handle last word
			setOr(segment, toLongIndex, toLongMask);
		}
	}

	public void clear(int fromIndex, int toIndex) {
		if (fromIndex == toIndex) {
			return;
		}

		var segments = this.segments.get();
		int numSegments = segments.numSegments();

		int firstSegmentIndex = segmentIndexForPosition(fromIndex);

		if (firstSegmentIndex >= numSegments) {
			return;
		}

		int toSegmentIndex = segmentIndexForPosition(toIndex);

		if (toSegmentIndex >= numSegments) {
			toSegmentIndex = numSegments - 1;
		}

		int fromLongIndex = longIndexInSegmentForPosition(fromIndex);
		int toLongIndex = longIndexInSegmentForPosition(toIndex);

		long fromLongMask = WORD_MASK << fromIndex;
		long toLongMask = WORD_MASK >>> -toIndex;

		var segment = segments.getSegment(firstSegmentIndex);

		if (firstSegmentIndex == toSegmentIndex) {
			// Case 1: One segment

			if (fromLongIndex == toLongIndex) {
				// Case 1A: One Long
				setAnd(segment, fromLongIndex, ~(fromLongMask & toLongMask));
			} else {
				// Case 1B: Multiple Longs
				// Handle first word
				setAnd(segment, fromLongIndex, ~fromLongMask);

				// Handle intermediate words, if any
				for (int i = fromLongIndex + 1; i < toLongIndex; i++) {
					segment.set(i, 0);
				}

				// Handle last word
				setAnd(segment, toLongIndex, ~toLongMask);
			}
		} else {
			// Case 2: Multiple Segments
			// Handle first segment

			// Handle first word
			setAnd(segment, fromLongIndex, ~fromLongMask);

			// Handle trailing words, if any
			for (int i = fromLongIndex + 1; i < numLongsPerSegment; i++) {
				segment.set(i, 0);
			}

			// Nuke intermediate segments, if any
			for (int i = firstSegmentIndex + 1; i < toSegmentIndex; i++) {
				segment = segments.getSegment(i);

				for (int j = 0; j < segment.length(); j++) {
					segment.set(j, 0);
				}
			}

			// Handle last segment
			segment = segments.getSegment(toSegmentIndex);

			// Handle leading words, if any
			for (int i = 0; i < toLongIndex; i++) {
				segment.set(i, 0);
			}

			// Handle last word
			setAnd(segment, toLongIndex, ~toLongMask);
		}
	}

	private void setOr(AtomicLongArray segment, int indexInSegment, long mask) {
		// Thread safety: we need to loop until we win the race to set the long value.
		while (true) {
			// determine what the new long value will be after we set the appropriate bit.
			long currentLongValue = segment.get(indexInSegment);
			long newLongValue = currentLongValue | mask;

			// if no other thread has modified the value since we read it, we won the race and we are done.
			if (segment.compareAndSet(indexInSegment, currentLongValue, newLongValue)) {
				break;
			}
		}
	}

	private void setAnd(AtomicLongArray segment, int indexInSegment, long mask) {
		// Thread safety: we need to loop until we win the race to set the long value.
		while (true) {
			// determine what the new long value will be after we set the appropriate bit.
			long currentLongValue = segment.get(indexInSegment);
			long newLongValue = currentLongValue & mask;

			// if no other thread has modified the value since we read it, we won the race and we are done.
			if (segment.compareAndSet(indexInSegment, currentLongValue, newLongValue)) {
				break;
			}
		}
	}

	public boolean get(int position) {
		int segmentPosition = segmentIndexForPosition(position);
		int longPosition = longIndexInSegmentForPosition(position);

		AtomicLongArray segment = segmentForPosition(segmentPosition);

		long mask = maskForPosition(position);

		return ((segment.get(longPosition) & mask) != 0);
	}

	public long maxSetBit() {
		AtomicBitSetSegments segments = this.segments.get();

		int segmentIdx = segments.numSegments() - 1;

		for (; segmentIdx >= 0; segmentIdx--) {
			AtomicLongArray segment = segments.getSegment(segmentIdx);
			for (int longIdx = segment.length() - 1; longIdx >= 0; longIdx--) {
				long l = segment.get(longIdx);
				if (l != 0) {
					return ((long) segmentIdx << log2SegmentSize) + (longIdx * 64L) + (63 - Long.numberOfLeadingZeros(l));
				}
			}
		}

		return -1;
	}

	public int nextSetBit(int fromIndex) {
		if (fromIndex < 0) {
			throw new IndexOutOfBoundsException("fromIndex < 0: " + fromIndex);
		}

		AtomicBitSetSegments segments = this.segments.get();

		int segmentPosition = segmentIndexForPosition(fromIndex);
		if (segmentPosition >= segments.numSegments()) {
			return -1;
		}

		int longPosition = longIndexInSegmentForPosition(fromIndex);
		AtomicLongArray segment = segments.getSegment(segmentPosition);

		long word = segment.get(longPosition) & (0xffffffffffffffffL << bitPosInLongForPosition(fromIndex));

		while (true) {
			if (word != 0) {
				return (segmentPosition << (log2SegmentSize)) + (longPosition << 6) + Long.numberOfTrailingZeros(word);
			}
			if (++longPosition > segmentMask) {
				segmentPosition++;
				if (segmentPosition >= segments.numSegments()) {
					return -1;
				}
				segment = segments.getSegment(segmentPosition);
				longPosition = 0;
			}

			word = segment.get(longPosition);
		}
	}

	public int nextClearBit(int fromIndex) {
		if (fromIndex < 0) {
			throw new IndexOutOfBoundsException("fromIndex < 0: " + fromIndex);
		}

		int segmentPosition = segmentIndexForPosition(fromIndex);

		AtomicBitSetSegments segments = this.segments.get();

		if (segmentPosition >= segments.numSegments()) {
			return fromIndex;
		}

		int longPosition = longIndexInSegmentForPosition(fromIndex);
		AtomicLongArray segment = segments.getSegment(segmentPosition);

		long word = ~segment.get(longPosition) & (0xffffffffffffffffL << bitPosInLongForPosition(fromIndex));

		while (true) {
			if (word != 0) {
				return (segmentPosition << (log2SegmentSize)) + (longPosition << 6) + Long.numberOfTrailingZeros(word);
			}
			if (++longPosition > segmentMask) {
				segmentPosition++;
				if (segmentPosition >= segments.numSegments()) {
					return segments.numSegments() << log2SegmentSize + (longPosition << 6);
				}
				segment = segments.getSegment(segmentPosition);
				longPosition = 0;
			}

			word = ~segment.get(longPosition);
		}
	}


	/**
	 * @return the number of bits which are set in this bit set.
	 */
	public int cardinality() {
		return this.segments.get()
				.cardinality();
	}

	/**
	 * Iterate over each contiguous span of set bits.
	 *
	 * @param consumer The consumer to accept each span.
	 */
	public void forEachSetSpan(BitSpanConsumer consumer) {
		AtomicBitSetSegments segments = this.segments.get();

		if (segments.cardinality() == 0) {
			return;
		}

		int start = -1;
		int end = -1;

		for (int segmentIndex = 0; segmentIndex < segments.numSegments(); segmentIndex++) {
			AtomicLongArray segment = segments.getSegment(segmentIndex);
			for (int longIndex = 0; longIndex < segment.length(); longIndex++) {
				long l = segment.get(longIndex);
				if (l != 0) {
					// The JIT loves this loop. Trying to be clever by starting from Long.numberOfLeadingZeros(l)
					// causes it to be much slower.
					for (int bitIndex = 0; bitIndex < 64; bitIndex++) {
						if ((l & (1L << bitIndex)) != 0) {
							var position = (segmentIndex << log2SegmentSize) + (longIndex << 6) + bitIndex;
							if (start == -1) {
								start = position;
							}
							end = position;
						} else {
							if (start != -1) {
								consumer.accept(start, end);
								start = -1;
								end = -1;
							}
						}
					}
				} else {
					if (start != -1) {
						consumer.accept(start, end);
						start = -1;
						end = -1;
					}
				}
			}
		}

		if (start != -1) {
			consumer.accept(start, end);
		}
	}

	/**
	 * @return the number of bits which are currently specified by this bit set.  This is the maximum value
	 * to which you might need to iterate, if you were to iterate over all bits in this set.
	 */
	public int currentCapacity() {
		return segments.get()
				.numSegments() * (1 << log2SegmentSize);
	}

	public boolean isEmpty() {
		return cardinality() == 0;
	}

	/**
	 * Clear all bits to 0.
	 */
	public void clear() {
		AtomicBitSetSegments segments = this.segments.get();

		for (int i = 0; i < segments.numSegments(); i++) {
			AtomicLongArray segment = segments.getSegment(i);

			for (int j = 0; j < segment.length(); j++) {
				segment.set(j, 0L);
			}
		}
	}

	/**
	 * Which bit in the long the given position resides in.
	 *
	 * @param position The absolute position in the bitset.
	 * @return The bit position in the long.
	 */
	private static int bitPosInLongForPosition(int position) {
		// remainder of div by num bits in long (64)
		return position & 0x3F;
	}

	/**
	 * Which long in the segment the given position resides in.
	 *
	 * @param position The absolute position in the bitset
	 * @return The long position in the segment.
	 */
	private int longIndexInSegmentForPosition(int position) {
		// remainder of div by num bits per segment
		return (position >>> 6) & segmentMask;
	}

	/**
	 * Which segment the given position resides in.
	 *
	 * @param position The absolute position in the bitset
	 * @return The segment index.
	 */
	private int segmentIndexForPosition(int position) {
		// div by num bits per segment
		return position >>> log2SegmentSize;
	}

	private static long maskForPosition(int position) {
		return 1L << bitPosInLongForPosition(position);
	}

	private AtomicLongArray getSegmentForPosition(int position) {
		return segmentForPosition(segmentIndexForPosition(position));
	}

	/**
	 * Get the segment at <code>segmentIndex</code>.  If this segment does not yet exist, create it.
	 *
	 * @param segmentIndex the segment index
	 * @return the segment
	 */
	private AtomicLongArray segmentForPosition(int segmentIndex) {
		return expandToFit(segmentIndex).getSegment(segmentIndex);
	}

	@NotNull
	private AtomicBitSet.AtomicBitSetSegments expandToFit(int segmentIndex) {
		AtomicBitSetSegments visibleSegments = segments.get();

		while (visibleSegments.numSegments() <= segmentIndex) {
			// Thread safety:  newVisibleSegments contains all of the segments from the currently visible segments, plus extra.
			// all of the segments in the currently visible segments are canonical and will not change.
			AtomicBitSetSegments newVisibleSegments = new AtomicBitSetSegments(visibleSegments, segmentIndex + 1, numLongsPerSegment);

			// because we are using a compareAndSet, if this thread "wins the race" and successfully sets this variable, then the segments
			// which are newly defined in newVisibleSegments become canonical.
			if (segments.compareAndSet(visibleSegments, newVisibleSegments)) {
				visibleSegments = newVisibleSegments;
			} else {
				// If we "lose the race" and are growing the AtomicBitset segments larger,
				// then we will gather the new canonical sets from the update which we missed on the next iteration of this loop.
				// Newly defined segments in newVisibleSegments will be discarded, they do not get to become canonical.
				visibleSegments = segments.get();
			}
		}
		return visibleSegments;
	}

	private static class AtomicBitSetSegments {
		private final AtomicLongArray[] segments;

		private AtomicBitSetSegments(int numSegments, int segmentLength) {
			AtomicLongArray[] segments = new AtomicLongArray[numSegments];

			for (int i = 0; i < numSegments; i++) {
				segments[i] = new AtomicLongArray(segmentLength);
			}

			// Thread safety: Because this.segments is final, the preceding operations in this constructor are guaranteed to be visible to any
			// other thread which accesses this.segments.
			this.segments = segments;
		}

		private AtomicBitSetSegments(AtomicBitSetSegments copyFrom, int numSegments, int segmentLength) {
			AtomicLongArray[] segments = new AtomicLongArray[numSegments];

			for (int i = 0; i < numSegments; i++) {
				segments[i] = i < copyFrom.numSegments() ? copyFrom.getSegment(i) : new AtomicLongArray(segmentLength);
			}

			// see above re: thread-safety of this assignment
			this.segments = segments;
		}

		private int cardinality() {
			int numSetBits = 0;

			for (int i = 0; i < numSegments(); i++) {
				AtomicLongArray segment = getSegment(i);
				for (int j = 0; j < segment.length(); j++) {
					numSetBits += Long.bitCount(segment.get(j));
				}
			}
			return numSetBits;
		}

		public int numSegments() {
			return segments.length;
		}

		public AtomicLongArray getSegment(int index) {
			return segments[index];
		}

	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof AtomicBitSet other)) {
			return false;
		}

		if (other.log2SegmentSize != log2SegmentSize) {
			throw new IllegalArgumentException("Segment sizes must be the same");
		}

		AtomicBitSetSegments thisSegments = this.segments.get();
		AtomicBitSetSegments otherSegments = other.segments.get();

		for (int i = 0; i < thisSegments.numSegments(); i++) {
			AtomicLongArray thisArray = thisSegments.getSegment(i);
			AtomicLongArray otherArray = (i < otherSegments.numSegments()) ? otherSegments.getSegment(i) : null;

			for (int j = 0; j < thisArray.length(); j++) {
				long thisLong = thisArray.get(j);
				long otherLong = (otherArray == null) ? 0 : otherArray.get(j);

				if (thisLong != otherLong) {
					return false;
				}
			}
		}

		for (int i = thisSegments.numSegments(); i < otherSegments.numSegments(); i++) {
			AtomicLongArray otherArray = otherSegments.getSegment(i);

			for (int j = 0; j < otherArray.length(); j++) {
				long l = otherArray.get(j);

				if (l != 0) {
					return false;
				}
			}
		}

		return true;
	}

	@Override
	public int hashCode() {
		int result = log2SegmentSize;
		result = 31 * result + Arrays.hashCode(segments.get().segments);
		return result;
	}

	/**
	 * @return a new BitSet with same bits set
	 */
	public BitSet toBitSet() {
		BitSet resultSet = new BitSet();
		int ordinal = this.nextSetBit(0);
		while (ordinal != -1) {
			resultSet.set(ordinal);
			ordinal = this.nextSetBit(ordinal + 1);
		}
		return resultSet;
	}

	@Override
	public String toString() {
		return toBitSet().toString();
	}

	@FunctionalInterface
	public interface BitSpanConsumer {
		/**
		 * Consume a span of bits.
		 *
		 * @param startInclusive The first (inclusive) bit in the span.
		 * @param endInclusive   The last (inclusive) bit in the span.
		 */
		void accept(int startInclusive, int endInclusive);
	}
}
