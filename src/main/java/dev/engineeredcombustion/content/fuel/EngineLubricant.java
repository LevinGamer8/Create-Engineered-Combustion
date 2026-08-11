package dev.engineeredcombustion.content.fuel;

import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;

import net.neoforged.neoforge.fluids.FluidStack;

/**
 * What counts as engine oil.
 *
 * <p>Built exactly like {@link EngineFuel}: membership is a tag, so nothing here
 * hardcodes a fluid instance and a pack or another mod can make its own
 * lubricant acceptable without any code. See {@link ECFluidTags}.
 */
public final class EngineLubricant {

	/** Fluids the oil sump will accept as lubricant. */
	public static final TagKey<Fluid> ENGINE_OIL = ECFluidTags.ENGINE_OIL;

	private EngineLubricant() {
	}

	public static boolean isValidOil(Fluid fluid) {
		return fluid.defaultFluidState()
			.is(ENGINE_OIL);
	}

	public static boolean isValidOil(FluidStack stack) {
		return !stack.isEmpty() && isValidOil(stack.getFluid());
	}
}
