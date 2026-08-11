package dev.engineeredcombustion.content.engine;

/**
 * Everything the world tells the engine at the start of one simulated tick.
 *
 * <p>Exists so that {@link EngineState#tickSimulation} keeps a readable
 * signature as the engine grows: throttle, load and the kinetic speed limit all
 * arrived in one milestone, the cylinder count and the per-cylinder ignition
 * state in the next, and passing eight loose parameters would have made every
 * call site a puzzle.
 *
 * <p>Like {@link EngineState} itself this is free of any Minecraft, NeoForge or
 * Create type. Resolving these values <i>is</i> Create's business - the
 * crankshaft block entity does it - but the simulation only ever sees plain
 * numbers and flags.
 *
 * @param structureValid     whether the engine is assembled well enough to turn:
 *                           every cylinder present with a Piston Assembly in it,
 *                           and exactly one Flywheel
 * @param ignitionEnabled    whether the ignition switch is on
 * @param cylinderCount      how many cylinders this engine has, 1 to
 *                           {@link EngineTuning#MAX_CYLINDERS}
 * @param sparkPlugMask      bit {@code i} set when cylinder {@code i} has a Spark
 *                           Plug in its head. Separate from
 *                           {@code structureValid} on purpose: a plug is what
 *                           makes a cylinder able to light a charge and has
 *                           nothing to do with whether the engine can be turned,
 *                           so an engine may legitimately run on some of its
 *                           cylinders
 * @param throttle           main throttle opening, {@code [0, 1]}
 * @param loadFactor         kinetic network stress over capacity, {@code [0, 1]};
 *                           0 when there is no network or no capacity yet
 * @param speedLimitRpm      highest speed the engine may reach or publish,
 *                           already reconciled with Create's configured
 *                           {@code maxRotationSpeed}
 */
public record EngineInputs(boolean structureValid, boolean ignitionEnabled, int cylinderCount, int sparkPlugMask,
	float throttle, float loadFactor, float speedLimitRpm) {

	public EngineInputs {
		cylinderCount = Math.min(Math.max(cylinderCount, 1), EngineTuning.MAX_CYLINDERS);
		sparkPlugMask &= (1 << cylinderCount) - 1;
		throttle = EngineTuning.clamp01(throttle);
		loadFactor = EngineTuning.clamp01(loadFactor);
		speedLimitRpm = Math.min(Math.max(speedLimitRpm, EngineTuning.STALL_RPM), EngineTuning.MAX_RPM);
	}

	/**
	 * Convenience for a single-cylinder engine, which is what every call site
	 * outside the assembly resolver used to be.
	 */
	public EngineInputs(boolean structureValid, boolean ignitionEnabled, boolean sparkPlugInstalled, float throttle,
		float loadFactor, float speedLimitRpm) {
		this(structureValid, ignitionEnabled, 1, sparkPlugInstalled ? 1 : 0, throttle, loadFactor, speedLimitRpm);
	}

	/** Whether cylinder {@code index} can produce a spark. */
	public boolean hasSparkPlug(int index) {
		return (sparkPlugMask & (1 << index)) != 0;
	}

	/** Whether every cylinder of this engine has a plug fitted. */
	public boolean allSparkPlugsInstalled() {
		return sparkPlugMask == (1 << cylinderCount) - 1;
	}

	/** Speed this throttle setting is asking the engine to hold. */
	public float targetRpm() {
		return Math.min(EngineTuning.targetRpmForThrottle(throttle), speedLimitRpm);
	}
}
