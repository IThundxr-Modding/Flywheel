package dev.engine_room.flywheel.lib.visual.component;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.ShadowInstance;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.visual.util.InstanceRecycler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A component that uses instances to render an entity's shadow.
 *
 * <p>Use {@link #radius(float)} to set the radius of the shadow, in blocks.
 * <br>
 * Use {@link #strength(float)} to set the strength of the shadow.
 * <br>
 * The shadow will be cast on blocks at most {@code min(radius, 2 * strength)} blocks below the entity.</p>
 */
public final class ShadowComponent implements EntityComponent {
	private static final ResourceLocation SHADOW_TEXTURE = ResourceLocation.withDefaultNamespace("textures/misc/shadow.png");
	private static final Material SHADOW_MATERIAL = SimpleMaterial.builder()
			.texture(SHADOW_TEXTURE)
			.mipmap(false)
			.polygonOffset(true) // vanilla shadows use "view offset" but this seems to work fine
			.transparency(Transparency.TRANSLUCENT)
			.writeMask(WriteMask.COLOR)
			.build();
	private static final Model SHADOW_MODEL = new SingleMeshModel(ShadowMesh.INSTANCE, SHADOW_MATERIAL);

	private final VisualizationContext context;
	private final Entity entity;
	private final Level level;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

	private final InstanceRecycler<ShadowInstance> instances = new InstanceRecycler<>(this::createInstance);

	// Defaults taken from EntityRenderer.
	private float radius = 0;
	private float strength = 1.0F;

	public ShadowComponent(VisualizationContext context, Entity entity) {
		this.context = context;
		this.entity = entity;
		this.level = entity.level();
	}

	private ShadowInstance createInstance() {
		return context.instancerProvider()
				.instancer(InstanceTypes.SHADOW, SHADOW_MODEL)
				.createInstance();
	}

	public float radius() {
		return radius;
	}

	public float strength() {
		return strength;
	}

	/**
	 * Set the radius of the shadow, in blocks, clamped to a maximum of 32.
	 *
	 * <p>Setting this to {@code <= 0} will disable the shadow.</p>
	 *
	 * @param radius The radius of the shadow, in blocks.
	 */
	public ShadowComponent radius(float radius) {
		this.radius = Math.min(radius, 32);
		return this;
	}

	/**
	 * Set the strength of the shadow.
	 *
	 * @param strength The strength of the shadow.
	 */
	public ShadowComponent strength(float strength) {
		this.strength = strength;
		return this;
	}

	/**
	 * Update the shadow instances. You'd typically call this in your visual's
	 * {@link dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual#beginFrame(DynamicVisual.Context) beginFrame} method.
	 *
	 * @param context The frame context.
	 */
	@Override
	public void beginFrame(DynamicVisual.Context context) {
		instances.resetCount();

		boolean shadowsEnabled = Minecraft.getInstance().options.entityShadows()
				.get();
		if (shadowsEnabled && radius > 0 && !entity.isInvisible()) {
			setupInstances(context);
		}

		instances.discardExtra();
	}

	private void setupInstances(DynamicVisual.Context context) {
		double entityX = Mth.lerp(context.partialTick(), entity.xOld, entity.getX());
		double entityY = Mth.lerp(context.partialTick(), entity.yOld, entity.getY());
		double entityZ = Mth.lerp(context.partialTick(), entity.zOld, entity.getZ());
		float castDistance = Math.min(strength * 2, radius);
		int minXPos = Mth.floor(entityX - (double) radius);
		int maxXPos = Mth.floor(entityX + (double) radius);
		int minYPos = Mth.floor(entityY - (double) castDistance);
		int maxYPos = Mth.floor(entityY);
		int minZPos = Mth.floor(entityZ - (double) radius);
		int maxZPos = Mth.floor(entityZ + (double) radius);

		for (int z = minZPos; z <= maxZPos; ++z) {
			for (int x = minXPos; x <= maxXPos; ++x) {
				pos.set(x, 0, z);
				ChunkAccess chunk = level.getChunk(pos);

				for (int y = minYPos; y <= maxYPos; ++y) {
					pos.setY(y);
					float strengthGivenYFalloff = strength - (float) (entityY - pos.getY()) * 0.5F;
					setupInstance(chunk, pos, (float) entityX, (float) entityZ, strengthGivenYFalloff);
				}
			}
		}
	}

	private void setupInstance(ChunkAccess chunk, MutableBlockPos pos, float entityX, float entityZ, float strength) {
		// TODO: cache this?
		var maxLocalRawBrightness = level.getMaxLocalRawBrightness(pos);
		if (maxLocalRawBrightness <= 3) {
			// Too dark to render.
			return;
		}
		float blockBrightness = LightTexture.getBrightness(level.dimensionType(), maxLocalRawBrightness);
		float alpha = strength * 0.5F * blockBrightness;
		if (alpha < 0.0F) {
			// Too far away/too weak to render.
			return;
		}
		if (alpha > 1.0F) {
			alpha = 1.0F;
		}

		// Grab the AABB for the block below the current position.
		pos.setY(pos.getY() - 1);
		var shape = getShapeAt(chunk, pos);
		if (shape == null) {
			// No shape means the block shouldn't receive a shadow.
			return;
		}

		var renderOrigin = context.renderOrigin();
		int x = pos.getX() - renderOrigin.getX();
		int y = pos.getY() - renderOrigin.getY() + 1; // +1 since we moved the pos down.
		int z = pos.getZ() - renderOrigin.getZ();

		double minX = x + shape.min(Axis.X);
		double minY = y + shape.min(Axis.Y);
		double minZ = z + shape.min(Axis.Z);
		double maxX = x + shape.max(Axis.X);
		double maxZ = z + shape.max(Axis.Z);

		var instance = instances.get();
		instance.x = (float) minX;
		instance.y = (float) minY;
		instance.z = (float) minZ;
		instance.entityX = entityX;
		instance.entityZ = entityZ;
		instance.sizeX = (float) (maxX - minX);
		instance.sizeZ = (float) (maxZ - minZ);
		instance.alpha = alpha;
		instance.radius = this.radius;
		instance.setChanged();
	}

	@Nullable
	private VoxelShape getShapeAt(ChunkAccess chunk, BlockPos pos) {
		BlockState state = chunk.getBlockState(pos);
		if (state.getRenderShape() == RenderShape.INVISIBLE) {
			return null;
		}
		if (!state.isCollisionShapeFullBlock(chunk, pos)) {
			return null;
		}
		VoxelShape shape = state.getShape(chunk, pos);
		if (shape.isEmpty()) {
			return null;
		}
		return shape;
	}

	@Override
	public void delete() {
		instances.delete();
	}

	/**
	 * A single quad extending from the origin to (1, 0, 1).
	 * <br>
	 * To be scaled and translated to the correct position and size.
	 */
	private static class ShadowMesh implements QuadMesh {
		private static final Vector4fc BOUNDING_SPHERE = new Vector4f(0.5f, 0, 0.5f, (float) (Math.sqrt(2) * 0.5));
		private static final ShadowMesh INSTANCE = new ShadowMesh();

		private ShadowMesh() {
		}

		@Override
		public int vertexCount() {
			return 4;
		}

		@Override
		public void write(MutableVertexList vertexList) {
			writeVertex(vertexList, 0, 0, 0);
			writeVertex(vertexList, 1, 0, 1);
			writeVertex(vertexList, 2, 1, 1);
			writeVertex(vertexList, 3, 1, 0);
		}

		// Magic numbers taken from:
		// net.minecraft.client.renderer.entity.EntityRenderDispatcher#shadowVertex
		private static void writeVertex(MutableVertexList vertexList, int i, float x, float z) {
			vertexList.x(i, x);
			vertexList.y(i, 0);
			vertexList.z(i, z);
			vertexList.r(i, 1);
			vertexList.g(i, 1);
			vertexList.b(i, 1);
			vertexList.u(i, 0);
			vertexList.v(i, 0);
			vertexList.light(i, LightTexture.FULL_BRIGHT);
			vertexList.overlay(i, OverlayTexture.NO_OVERLAY);
			vertexList.normalX(i, 0);
			vertexList.normalY(i, 1);
			vertexList.normalZ(i, 0);
		}

		@Override
		public Vector4fc boundingSphere() {
			return BOUNDING_SPHERE;
		}
	}
}
