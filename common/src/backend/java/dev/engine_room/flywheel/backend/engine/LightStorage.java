package dev.engine_room.flywheel.backend.engine;

import java.util.BitSet;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.Effect;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.backend.engine.indirect.StagingBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.math.MoreMath;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.component.HitboxComponent;
import dev.engine_room.flywheel.lib.visual.util.InstanceRecycler;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;

/**
 * A managed arena of light sections for uploading to the GPU.
 *
 * <p>Each section represents an 18x18x18 block volume of light data.
 * The "edges" are taken from the neighboring sections, so that each
 * shader invocation only needs to access a single section of data.
 * Even still, neighboring shader invocations may need to access other sections.
 *
 * <p>Sections are logically stored as a 9x9x9 array of longs,
 * where each long holds a 2x2x2 array of light data.
 * <br>Both the greater array and the longs are packed in x, z, y order.
 *
 * <p>Thus, each section occupies 5832 bytes.
 */
public class LightStorage implements Effect {
	public static boolean DEBUG = false;

	public static final int BLOCKS_PER_SECTION = 18 * 18 * 18;
	public static final int LIGHT_SIZE_BYTES = BLOCKS_PER_SECTION;
	public static final int SOLID_SIZE_BYTES = MoreMath.ceilingDiv(BLOCKS_PER_SECTION, Integer.SIZE) * Integer.BYTES;
	public static final int SECTION_SIZE_BYTES = SOLID_SIZE_BYTES + LIGHT_SIZE_BYTES;
	private static final int DEFAULT_ARENA_CAPACITY_SECTIONS = 64;
	private static final int INVALID_SECTION = -1;

	private final LevelAccessor level;
	private final LightLut lut;
	private final CpuArena arena;
	private final Long2IntMap section2ArenaIndex;

	private final BitSet changed = new BitSet();
	private boolean needsLutRebuild = false;
	private boolean isDebugOn = false;

	private final LongSet updatedSections = new LongOpenHashSet();
	@Nullable
	private LongSet requestedSections;

	public LightStorage(LevelAccessor level) {
		this.level = level;
		lut = new LightLut();
		arena = new CpuArena(SECTION_SIZE_BYTES, DEFAULT_ARENA_CAPACITY_SECTIONS);
		section2ArenaIndex = new Long2IntOpenHashMap();
		section2ArenaIndex.defaultReturnValue(INVALID_SECTION);
	}

	@Override
	public LevelAccessor level() {
		return level;
	}

	@Override
	public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
		return new DebugVisual(ctx, partialTick);
	}

	/**
	 * Set the set of requested sections.
	 * <p> When set, this will be processed in the next frame plan. It may not be set every frame.
	 *
	 * @param sections The set of sections requested by the impl.
	 */
	public void sections(LongSet sections) {
		requestedSections = sections;
	}

	public void onLightUpdate(long section) {
		updatedSections.add(section);
	}

	public <C> Plan<C> createFramePlan() {
		return SimplePlan.of(() -> {
			if (DEBUG != isDebugOn) {
				var visualizationManager = VisualizationManager.get(level);

				// Really should be non-null, but just in case.
				if (visualizationManager != null) {
					if (DEBUG) {
						visualizationManager.effects()
								.queueAdd(this);
					} else {
						visualizationManager.effects()
								.queueRemove(this);
					}
				}
				isDebugOn = DEBUG;
			}

			if (updatedSections.isEmpty() && requestedSections == null) {
				return;
			}

			removeUnusedSections();

			// Start building the set of sections we need to collect this frame.
			LongSet sectionsToCollect;
			if (requestedSections == null) {
				// If none were requested, then we need to collect all sections that received updates.
				sectionsToCollect = new LongOpenHashSet();
			} else {
				// If we did receive a new set of requested sections, we only
				// need to collect the sections that weren't yet tracked.
				sectionsToCollect = new LongOpenHashSet(requestedSections);
				sectionsToCollect.removeAll(section2ArenaIndex.keySet());
			}

			// updatedSections contains all sections that received light updates,
			// but we only care about its intersection with our tracked sections.
			for (long updatedSection : updatedSections) {
				// Since sections contain the border light of their neighbors, we need to collect the neighbors as well.
				for (int x = -1; x <= 1; x++) {
					for (int y = -1; y <= 1; y++) {
						for (int z = -1; z <= 1; z++) {
							long section = SectionPos.offset(updatedSection, x, y, z);
							if (section2ArenaIndex.containsKey(section)) {
								sectionsToCollect.add(section);
							}
						}
					}
				}
			}

			// Now actually do the collection.
			sectionsToCollect.forEach(this::collectSection);

			updatedSections.clear();
			requestedSections = null;
		});
	}

	private void removeUnusedSections() {
		if (requestedSections == null) {
			return;
		}

		var entries = section2ArenaIndex.long2IntEntrySet();
		var it = entries.iterator();
		while (it.hasNext()) {
			var entry = it.next();
			var section = entry.getLongKey();

			if (!requestedSections.contains(section)) {
				arena.free(entry.getIntValue());
				endTrackingSection(section);
				it.remove();
			}
		}
	}

	private void beginTrackingSection(long section, int index) {
		lut.add(section, index);
		needsLutRebuild = true;
	}

	private void endTrackingSection(long section) {
		lut.remove(section);
		needsLutRebuild = true;
	}

	public int capacity() {
		return arena.capacity();
	}

	public void collectSection(long section) {
		var lightEngine = level.getLightEngine();

		var blockLight = lightEngine.getLayerListener(LightLayer.BLOCK);
		var skyLight = lightEngine.getLayerListener(LightLayer.SKY);

		int index = indexForSection(section);

		changed.set(index);

		long ptr = arena.indexToPointer(index);

		// Zero it out first. This is basically free and makes it easier to handle missing sections later.
		MemoryUtil.memSet(ptr, 0, SECTION_SIZE_BYTES);

		collectSolidData(ptr, section);

		collectCenter(blockLight, skyLight, ptr, section);

		for (SectionEdge i : SectionEdge.values()) {
			collectYZPlane(blockLight, skyLight, ptr, SectionPos.offset(section, i.sectionOffset, 0, 0), i);
			collectXZPlane(blockLight, skyLight, ptr, SectionPos.offset(section, 0, i.sectionOffset, 0), i);
			collectXYPlane(blockLight, skyLight, ptr, SectionPos.offset(section, 0, 0, i.sectionOffset), i);

			for (SectionEdge j : SectionEdge.values()) {
				collectXStrip(blockLight, skyLight, ptr, SectionPos.offset(section, 0, i.sectionOffset, j.sectionOffset), i, j);
				collectYStrip(blockLight, skyLight, ptr, SectionPos.offset(section, i.sectionOffset, 0, j.sectionOffset), i, j);
				collectZStrip(blockLight, skyLight, ptr, SectionPos.offset(section, i.sectionOffset, j.sectionOffset, 0), i, j);
			}
		}

		collectCorners(blockLight, skyLight, ptr, section);
	}

	private void collectSolidData(long ptr, long section) {
		var blockPos = new BlockPos.MutableBlockPos();
		int xMin = SectionPos.sectionToBlockCoord(SectionPos.x(section));
		int yMin = SectionPos.sectionToBlockCoord(SectionPos.y(section));
		int zMin = SectionPos.sectionToBlockCoord(SectionPos.z(section));

		var bitSet = new BitSet(BLOCKS_PER_SECTION);
		int index = 0;
		for (int y = -1; y < 17; y++) {
			for (int z = -1; z < 17; z++) {
				for (int x = -1; x < 17; x++) {
					blockPos.set(xMin + x, yMin + y, zMin + z);

					boolean isFullBlock = level.getBlockState(blockPos)
							.isCollisionShapeFullBlock(level, blockPos);

					if (isFullBlock) {
						bitSet.set(index);
					}

					index++;
				}
			}
		}

		var longArray = bitSet.toLongArray();
		for (long l : longArray) {
			MemoryUtil.memPutLong(ptr, l);
			ptr += Long.BYTES;
		}
	}

	private void writeSolid(long ptr, int index, boolean blockValid) {
		if (!blockValid) {
			return;
		}
		int intIndex = index / Integer.SIZE;
		int bitIndex = index % Integer.SIZE;

		long offset = intIndex * Integer.BYTES;

		int bitField = MemoryUtil.memGetInt(ptr + offset);
		bitField |= 1 << bitIndex;

		MemoryUtil.memPutInt(ptr + offset, bitField);
	}

	private void collectXStrip(LayerLightEventListener blockLight, LayerLightEventListener skyLight, long ptr, long section, SectionEdge y, SectionEdge z) {
		var pos = SectionPos.of(section);
		var blockData = blockLight.getDataLayerData(pos);
		var skyData = skyLight.getDataLayerData(pos);
		if (blockData == null || skyData == null) {
			return;
		}
		for (int x = 0; x < 16; x++) {
			write(ptr, x, y.relative, z.relative, blockData.get(x, y.pos, z.pos), skyData.get(x, y.pos, z.pos));
		}
	}

	private void collectYStrip(LayerLightEventListener blockLight, LayerLightEventListener skyLight, long ptr, long section, SectionEdge x, SectionEdge z) {
		var pos = SectionPos.of(section);
		var blockData = blockLight.getDataLayerData(pos);
		var skyData = skyLight.getDataLayerData(pos);
		if (blockData == null || skyData == null) {
			return;
		}
		for (int y = 0; y < 16; y++) {
			write(ptr, x.relative, y, z.relative, blockData.get(x.pos, y, z.pos), skyData.get(x.pos, y, z.pos));
		}
	}

	private void collectZStrip(LayerLightEventListener blockLight, LayerLightEventListener skyLight, long ptr, long section, SectionEdge x, SectionEdge y) {
		var pos = SectionPos.of(section);
		var blockData = blockLight.getDataLayerData(pos);
		var skyData = skyLight.getDataLayerData(pos);
		if (blockData == null || skyData == null) {
			return;
		}
		for (int z = 0; z < 16; z++) {
			write(ptr, x.relative, y.relative, z, blockData.get(x.pos, y.pos, z), skyData.get(x.pos, y.pos, z));
		}
	}

	private void collectYZPlane(LayerLightEventListener blockLight, LayerLightEventListener skyLight, long ptr, long section, SectionEdge x) {
		var pos = SectionPos.of(section);
		var blockData = blockLight.getDataLayerData(pos);
		var skyData = skyLight.getDataLayerData(pos);
		if (blockData == null || skyData == null) {
			return;
		}
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				write(ptr, x.relative, y, z, blockData.get(x.pos, y, z), skyData.get(x.pos, y, z));
			}
		}
	}

	private void collectXZPlane(LayerLightEventListener blockLight, LayerLightEventListener skyLight, long ptr, long section, SectionEdge y) {
		var pos = SectionPos.of(section);
		var blockData = blockLight.getDataLayerData(pos);
		var skyData = skyLight.getDataLayerData(pos);
		if (blockData == null || skyData == null) {
			return;
		}
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				write(ptr, x, y.relative, z, blockData.get(x, y.pos, z), skyData.get(x, y.pos, z));
			}
		}
	}

	private void collectXYPlane(LayerLightEventListener blockLight, LayerLightEventListener skyLight, long ptr, long section, SectionEdge z) {
		var pos = SectionPos.of(section);
		var blockData = blockLight.getDataLayerData(pos);
		var skyData = skyLight.getDataLayerData(pos);
		if (blockData == null || skyData == null) {
			return;
		}
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				write(ptr, x, y, z.relative, blockData.get(x, y, z.pos), skyData.get(x, y, z.pos));
			}
		}
	}

	private void collectCenter(LayerLightEventListener blockLight, LayerLightEventListener skyLight, long ptr, long section) {
		var pos = SectionPos.of(section);
		var blockData = blockLight.getDataLayerData(pos);
		var skyData = skyLight.getDataLayerData(pos);
		if (blockData == null || skyData == null) {
			return;
		}
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					write(ptr, x, y, z, blockData.get(x, y, z), skyData.get(x, y, z));
				}
			}
		}
	}

	private void collectCorners(LayerLightEventListener blockLight, LayerLightEventListener skyLight, long ptr, long section) {
		var blockPos = new BlockPos.MutableBlockPos();
		int xMin = SectionPos.sectionToBlockCoord(SectionPos.x(section));
		int yMin = SectionPos.sectionToBlockCoord(SectionPos.y(section));
		int zMin = SectionPos.sectionToBlockCoord(SectionPos.z(section));

		for (SectionEdge x : SectionEdge.values()) {
			for (SectionEdge y : SectionEdge.values()) {
				for (SectionEdge z : SectionEdge.values()) {
					blockPos.set(x.relative + xMin, y.relative + yMin, z.relative + zMin);
					write(ptr, x.relative, y.relative, z.relative, blockLight.getLightValue(blockPos), skyLight.getLightValue(blockPos));
				}
			}
		}
	}

	/**
	 * Write to the given section.
	 * @param ptr Pointer to the base of a section's data.
	 * @param x X coordinate in the section, from [-1, 16].
	 * @param y Y coordinate in the section, from [-1, 16].
	 * @param z Z coordinate in the section, from [-1, 16].
	 * @param block The block light level, from [0, 15].
	 * @param sky The sky light level, from [0, 15].
	 */
	private void write(long ptr, int x, int y, int z, int block, int sky) {
		int x1 = x + 1;
		int y1 = y + 1;
		int z1 = z + 1;

		int offset = x1 + z1 * 18 + y1 * 18 * 18;

		long packedByte = (block & 0xF) | ((sky & 0xF) << 4);

		MemoryUtil.memPutByte(ptr + SOLID_SIZE_BYTES + offset, (byte) packedByte);
	}

	/**
	 * Get a pointer to the base of the given section.
	 * <p> If the section is not yet reserved, allocate a chunk in the arena.
	 * @param section The section to write to.
	 * @return A raw pointer to the base of the section.
	 */
	private long ptrForSection(long section) {
		return arena.indexToPointer(indexForSection(section));
	}

	private int indexForSection(long section) {
		int out = section2ArenaIndex.get(section);

		// Need to allocate.
		if (out == INVALID_SECTION) {
			out = arena.alloc();
			section2ArenaIndex.put(section, out);
			beginTrackingSection(section, out);
		}
		return out;
	}

	public void delete() {
		arena.delete();
	}

	public boolean checkNeedsLutRebuildAndClear() {
		var out = needsLutRebuild;
		needsLutRebuild = false;
		return out;
	}

	public void uploadChangedSections(StagingBuffer staging, int dstVbo) {
		for (int i = changed.nextSetBit(0); i >= 0; i = changed.nextSetBit(i + 1)) {
			staging.enqueueCopy(arena.indexToPointer(i), SECTION_SIZE_BYTES, dstVbo, i * SECTION_SIZE_BYTES);
		}
		changed.clear();
	}

	public void upload(GlBuffer buffer) {
		if (changed.isEmpty()) {
			return;
		}

		buffer.upload(arena.indexToPointer(0), arena.capacity() * SECTION_SIZE_BYTES);
		changed.clear();
	}

	public IntArrayList createLut() {
		return lut.flatten();
	}

	private enum SectionEdge {
		LOW(15, -1, -1),
		HIGH(0, 16, 1),
		;

		/**
		 * The position in the section to collect.
		 */
		private final int pos;
		/**
		 * The position relative to the main section.
		 */
		private final int relative;
		/**
		 * The offset to the neighboring section.
		 */
		private final int sectionOffset;

		SectionEdge(int pos, int relative, int sectionOffset) {
			this.pos = pos;
			this.relative = relative;
			this.sectionOffset = sectionOffset;
		}
	}

	public class DebugVisual implements EffectVisual<LightStorage>, SimpleDynamicVisual {

		private final InstanceRecycler<TransformedInstance> boxes;

		public DebugVisual(VisualizationContext ctx, float partialTick) {
			boxes = new InstanceRecycler<>(() -> ctx.instancerProvider()
					.instancer(InstanceTypes.TRANSFORMED, HitboxComponent.BOX_MODEL)
					.createInstance());
		}

		@Override
		public void beginFrame(Context ctx) {
			boxes.resetCount();

			setupSectionBoxes();
			setupLutRangeBoxes();

			boxes.discardExtra();
		}

		private void setupSectionBoxes() {
			section2ArenaIndex.keySet()
					.forEach(l -> {
						var x = SectionPos.x(l);
						var y = SectionPos.y(l);
						var z = SectionPos.z(l);

						var instance = boxes.get();

						instance.setIdentityTransform()
								.scale(16)
								.translate(x, y, z)
								.color(255, 255, 0)
								.light(LightTexture.FULL_BRIGHT)
								.setChanged();
					});
		}

		private void setupLutRangeBoxes() {
			var first = lut.indices;

			var base1 = first.base();
			var size1 = first.size();

			for (int y = 0; y < size1; y++) {
				var second = first.getRaw(y);

				if (second == null) {
					continue;
				}

				var base2 = second.base();
				var size2 = second.size();

				for (int x = 0; x < size2; x++) {
					var third = second.getRaw(x);

					if (third == null) {
						continue;
					}

					var base3 = third.base();
					var size3 = third.size();

					for (int z = 0; z < size3; z++) {
						float x1 = base2 * 16;
						float y1 = base1 * 16;
						float z1 = base3 * 16;

						float x2 = (base2 + x) * 16 + 7.5f;
						float y2 = (base1 + y) * 16 + 7.5f;
						float z2 = (base3 + z) * 16 + 7.5f;
						boxes.get()
								.setIdentityTransform()
								.translate(x1, y2, z2)
								.scale(size2 * 16, 1, 1)
								.color(255, 0, 0)
								.light(LightTexture.FULL_BRIGHT)
								.setChanged();

						boxes.get()
								.setIdentityTransform()
								.translate(x2, y1, z2)
								.scale(1, size1 * 16, 1)
								.color(0, 255, 0)
								.light(LightTexture.FULL_BRIGHT)
								.setChanged();

						boxes.get()
								.setIdentityTransform()
								.translate(x2, y2, z1)
								.scale(1, 1, size3 * 16)
								.color(0, 0, 255)
								.light(LightTexture.FULL_BRIGHT)
								.setChanged();

						if (third.getRaw(z) == 0) {
							float x3 = (base2 + x) * 16 + 6f;
							float y3 = (base1 + y) * 16 + 6f;
							float z3 = (base3 + z) * 16 + 6f;

							// Freely representable section that is not filled.
							boxes.get()
									.setIdentityTransform()
									.translate(x3, y3, z3)
									.scale(4)
									.color(0, 255, 255)
									.light(LightTexture.FULL_BRIGHT)
									.setChanged();
						}
					}
				}
			}
		}

		@Override
		public void update(float partialTick) {

		}

		@Override
		public void delete() {
			boxes.delete();
		}
	}
}
