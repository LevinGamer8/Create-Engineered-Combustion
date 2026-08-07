package dev.engineeredcombustion.registry;

import com.simibubi.create.api.stress.BlockStressValues;

import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlockEntity;

/**
 * Registers this mod's blocks with Create's stress system.
 *
 * <p>Only the Flywheel is registered, because the Flywheel is the single block
 * that acts as this engine's kinetic source (see
 * {@link EngineFlywheelBlockEntity}). The Crankshaft and the Cylinder are not
 * part of any Create kinetic network at all.
 *
 * <p>Capacity is per RPM, so it scales with the engine's actual speed exactly
 * like Create's own generators: an engine idling at 64 RPM supplies 2048 SU, and
 * one coasting down at 20 RPM supplies only 640 SU. That is what keeps the engine
 * a finite power source and lets Create's normal overstress rules apply
 * unchanged.
 */
public class ECStressValues {

	public static void register() {
		BlockStressValues.CAPACITIES.register(ECBlocks.FLYWHEEL.get(),
			() -> EngineTuning.STRESS_CAPACITY_PER_RPM);

		// Informational only; drives Create's "Generated Speed" tooltip. The engine
		// genuinely may generate less than idle while starting or coasting.
		BlockStressValues.RPM.register(ECBlocks.FLYWHEEL.get(),
			new BlockStressValues.GeneratedRpm((int) EngineTuning.IDLE_RPM, true));
	}
}
