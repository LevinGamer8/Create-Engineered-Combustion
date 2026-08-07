package dev.engineeredcombustion.registry;

import com.simibubi.create.api.stress.BlockStressValues;

import dev.engineeredcombustion.content.engine.EngineState;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlockEntity;

/**
 * Registers this mod's blocks with Create's stress system.
 *
 * <p>Only the Flywheel is registered, because the Flywheel is the single block
 * that acts as this engine's kinetic source (see
 * {@link EngineFlywheelBlockEntity}). The Crankshaft and the Cylinder are not
 * part of any Create kinetic network at all.
 */
public class ECStressValues {

	public static void register() {
		// Capacity is expressed per RPM, matching Create's own convention:
		// at the debug speed of 32 RPM this yields 32 * 32 = 1024 SU.
		BlockStressValues.CAPACITIES.register(ECBlocks.FLYWHEEL.get(),
			() -> EngineFlywheelBlockEntity.STRESS_CAPACITY_PER_RPM);

		// Purely informational; drives Create's "Generated Speed" tooltip.
		BlockStressValues.RPM.register(ECBlocks.FLYWHEEL.get(),
			new BlockStressValues.GeneratedRpm((int) EngineState.DEBUG_TARGET_RPM, false));
	}
}
