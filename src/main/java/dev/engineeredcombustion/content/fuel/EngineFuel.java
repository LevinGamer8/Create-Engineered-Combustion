package dev.engineeredcombustion.content.fuel;

import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;

import net.neoforged.neoforge.fluids.FluidStack;

/**
 * What counts as fuel for a gasoline engine.
 *
 * <p>The engine never compares against a specific {@code Fluid} instance - it
 * asks this class, and this class asks a tag. See {@link ECFluidTags} for how
 * that tag is populated and how another mod's gasoline joins it.
 */
public final class EngineFuel {

	/** Fluids this engine will burn as gasoline. */
	public static final TagKey<Fluid> GASOLINE = ECFluidTags.GASOLINE;

	private EngineFuel() {
	}

	public static boolean isValidFuel(Fluid fluid) {
		return fluid.defaultFluidState()
			.is(GASOLINE);
	}

	public static boolean isValidFuel(FluidStack stack) {
		return !stack.isEmpty() && isValidFuel(stack.getFluid());
	}
}
