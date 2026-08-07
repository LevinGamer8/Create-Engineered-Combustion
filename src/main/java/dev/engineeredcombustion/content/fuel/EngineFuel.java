package dev.engineeredcombustion.content.fuel;

import dev.engineeredcombustion.EngineeredCombustion;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;

import net.neoforged.neoforge.fluids.FluidStack;

/**
 * What counts as fuel for a gasoline engine.
 *
 * <p>The engine never compares against a specific {@code Fluid} instance - it
 * asks this class. Membership is a fluid tag, so another mod's gasoline (or a
 * pack author's) can be accepted later by adding it to
 * {@code engineered_combustion:gasoline} with a datapack, and no simulation or
 * carburetor code has to change.
 *
 * <p>Deliberately no hardcoded IDs for Create: Diesel Generators or TFMG. Their
 * 1.21.1 fluid identifiers were not available to verify in this environment, and
 * guessing them would create a silent breakage rather than a clean absence.
 * Neither mod is, or becomes, a dependency.
 */
public final class EngineFuel {

	/** Fluids this engine will burn as gasoline. */
	public static final TagKey<Fluid> GASOLINE =
		TagKey.create(Registries.FLUID, EngineeredCombustion.asResource("gasoline"));

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
