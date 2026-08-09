package dev.engineeredcombustion.content.engine;

/**
 * Everything the world tells the engine at the start of one simulated tick.
 *
 * <p>Exists so that {@link EngineState#tickSimulation} keeps a readable
 * signature as the engine grows: throttle, load and the kinetic speed limit all
 * arrived in the same milestone, and passing six loose parameters would have
 * made every call site a puzzle.
 *
 * <p>Like {@link EngineState} itself this is free of any Minecraft, NeoForge or
 * Create type. Resolving these values <i>is</i> Create's business - the
 * crankshaft block entity does it - but the simulation only ever sees plain
 * numbers and flags.
 *
 * @param structureValid   whether the engine is assembled well enough to turn
 * @param ignitionEnabled  whether the ignition switch is on
 * @param externallyDriven whether Create currently drives the engine from
 *                         somewhere other than the engine itself
 * @param throttle         main throttle opening, {@code [0, 1]}
 * @param loadFactor       kinetic network stress over capacity, {@code [0, 1]};
 *                         0 when there is no network or no capacity yet
 * @param speedLimitRpm    highest speed the engine may reach or publish, already
 *                         reconciled with Create's configured
 *                         {@code maxRotationSpeed}
 */
public record EngineInputs(boolean structureValid, boolean ignitionEnabled, boolean externallyDriven, float throttle,
	float loadFactor, float speedLimitRpm) {

	public EngineInputs {
		throttle = EngineTuning.clamp01(throttle);
		loadFactor = EngineTuning.clamp01(loadFactor);
		speedLimitRpm = Math.min(Math.max(speedLimitRpm, EngineTuning.STALL_RPM), EngineTuning.MAX_RPM);
	}

	/** Speed this throttle setting is asking the engine to hold. */
	public float targetRpm() {
		return Math.min(EngineTuning.targetRpmForThrottle(throttle), speedLimitRpm);
	}
}
