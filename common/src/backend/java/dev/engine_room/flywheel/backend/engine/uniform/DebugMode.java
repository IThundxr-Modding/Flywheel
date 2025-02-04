package dev.engine_room.flywheel.backend.engine.uniform;

import java.util.Locale;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

public enum DebugMode implements StringRepresentable {
	OFF,
	NORMALS,
	INSTANCE_ID,
	LIGHT_LEVEL,
	LIGHT_COLOR,
	OVERLAY,
	DIFFUSE,
	MODEL_ID,
	;

	public static final Codec<DebugMode> CODEC = StringRepresentable.fromEnum(DebugMode::values);

	@Override
	public String getSerializedName() {
		return name().toLowerCase(Locale.ROOT);
	}
}
