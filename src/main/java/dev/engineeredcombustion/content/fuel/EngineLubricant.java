package dev.engineeredcombustion.content.fuel;

import dev.engineeredcombustion.EngineeredCombustion;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;

import net.neoforged.neoforge.fluids.FluidStack;

/**
 * What counts as engine oil.
 *
 * <p>Built exactly like {@link EngineFuel}: membership is a fluid tag, so a pack
 * or another mod can make its own lubricant acceptable by adding it to
 * {@code engineered_combustion:engine_oil}, and no simulation or block code has
 * to know. Nothing here hardcodes a fluid instance.
 */
public final class EngineLubricant {

	/** Fluids the oil sump will accept as lubricant. */
	public static final TagKey<Fluid> ENGINE_OIL =
		TagKey.create(Registries.FLUID, EngineeredCombustion.asResource("engine_oil"));

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
