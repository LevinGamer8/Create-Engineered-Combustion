package dev.engineeredcombustion.registry;

import com.simibubi.create.api.stress.BlockStressValues;

import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlockEntity;

/**
 * Registers this mod's blocks with Create's stress system.
 *
 * <p>Only the Flywheel is registered, because the Flywheel is the single block
 * that acts as this engine's kinetic source (see
 * {@link EngineFlywheelBlockEntity}).
 *
 * <p>The Crankshaft <i>is</i> on the kinetic network now - that is what gives
 * the engine a working shaft output on both ends of its axis - but it is
 * deliberately absent from this file. It is a relay: no generated speed, no
 * capacity, no impact. Registering capacity for it as well would hand the player
 * a second helping of stress units for the same engine, which is exactly the
 * duplication the two-sided output must not introduce. One engine, one source,
 * one stress budget.
 *
 * <p>Capacity is per RPM, so it scales with the engine's actual speed exactly
 * like Create's own generators - Create multiplies the registered value by the
 * generator's speed in {@code KineticNetwork#getActualCapacityOf}. An engine
 * idling at 64 RPM supplies 2048 SU; one at full throttle, 192 RPM, supplies
 * 6144 SU; one coasting down at 20 RPM supplies only 640 SU.
 *
 * <p>That is also the whole of the throttle's effect on stress, and deliberately
 * so: opening the throttle buys capacity only by actually turning faster, which
 * the engine has to work up to and can lose again under load. Nothing here
 * scales capacity by the throttle setting itself, so there is no way to conjure
 * a bigger power budget out of a lever.
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
