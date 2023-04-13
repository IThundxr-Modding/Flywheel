package com.jozufozu.flywheel.mixin;

import java.util.ArrayList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.google.common.collect.Lists;
import com.jozufozu.flywheel.extension.ClientLevelExtension;
import com.jozufozu.flywheel.impl.visualization.VisualizationHelper;
import com.jozufozu.flywheel.util.FlwUtil;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.LevelEntityGetter;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin implements ClientLevelExtension {
	@Shadow
	protected abstract LevelEntityGetter<Entity> getEntities();

	@Override
	public Iterable<Entity> flywheel$getAllLoadedEntities() {
		return getEntities().getAll();
	}

	@Inject(method = "entitiesForRendering()Ljava/lang/Iterable;", at = @At("RETURN"), cancellable = true)
	private void flywheel$filterEntities(CallbackInfoReturnable<Iterable<Entity>> cir) {
		if (!FlwUtil.canUseVisualization((ClientLevel) (Object) this)) {
			return;
		}

		Iterable<Entity> entities = cir.getReturnValue();
		ArrayList<Entity> filtered = Lists.newArrayList(entities);

		filtered.removeIf(VisualizationHelper::shouldSkipRender);

		cir.setReturnValue(filtered);
	}
}
