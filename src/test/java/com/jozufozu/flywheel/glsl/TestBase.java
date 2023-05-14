package com.jozufozu.flywheel.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.jetbrains.annotations.NotNull;

import com.jozufozu.flywheel.Flywheel;
import com.jozufozu.flywheel.glsl.error.ErrorBuilder;

import net.minecraft.resources.ResourceLocation;

public class TestBase {
	public static final ResourceLocation FLW_A = Flywheel.rl("a.glsl");
	public static final ResourceLocation FLW_B = Flywheel.rl("b.glsl");
	public static final ResourceLocation FLW_C = Flywheel.rl("c.glsl");

	public static <T> T assertSingletonList(List<T> list) {
		assertEquals(1, list.size());
		return list.get(0);
	}

	@NotNull
	public static <E extends LoadError> E findAndAssertError(Class<E> clazz, MockShaderSources sources, ResourceLocation loc) {
		var result = sources.find(loc);
		var failure = assertInstanceOf(LoadResult.Failure.class, result);
		return assertInstanceOf(clazz, failure.error());
	}

	@NotNull
	public static ErrorBuilder assertErrorAndGetMessage(MockShaderSources sources, ResourceLocation loc) {
		var result = sources.find(loc);
		var failure = assertInstanceOf(LoadResult.Failure.class, result);
		return failure.error()
				.generateMessage();
	}

	static <E extends LoadError> E assertSimpleNestedErrorsToDepth(Class<E> finalErrType, LoadError err, int depth) {
		var includeError = assertInstanceOf(LoadError.IncludeError.class, err);

		var pair = assertSingletonList(includeError.innerErrors());
		for (int i = 1; i < depth; i++) {
			includeError = assertInstanceOf(LoadError.IncludeError.class, pair.second());
			pair = assertSingletonList(includeError.innerErrors());
		}
		return assertInstanceOf(finalErrType, pair.second());
	}

	@NotNull
	public static SourceFile findAndAssertSuccess(MockShaderSources sources, ResourceLocation loc) {
		var result = sources.find(loc);
		return assertSuccessAndUnwrap(loc, result);
	}

	@NotNull
	public static SourceFile assertSuccessAndUnwrap(ResourceLocation expectedName, LoadResult result) {
		assertInstanceOf(LoadResult.Success.class, result);

		var file = result.unwrap();
		assertNotNull(file);
		assertEquals(expectedName, file.name);
		return file;
	}
}
