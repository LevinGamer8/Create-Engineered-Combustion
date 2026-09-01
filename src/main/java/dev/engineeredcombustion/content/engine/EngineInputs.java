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
 * @param wear               the physical condition of the parts this engine is
 *                           made of - see {@link EngineWearInputs}. An input like
 *                           any other, because wear belongs to the Crankshaft
 *                           sections and Piston Assemblies rather than to the
 *                           simulation; never null, defaulting to a new engine
 * @param camshaftInstalled  whether the engine has its one Camshaft fitted.
 *                           Separate from {@code structureValid} for exactly the
 *                           reason the Spark Plug mask is: an engine with no
 *                           valvetrain is a complete, valid, turnable machine that
 *                           happens to be missing a part, and calling it broken
 *                           would tell the player the wrong thing. Without it no
 *                           cylinder can draw a charge, so none can burn one
 */
public record EngineInputs(boolean structureValid, boolean ignitionEnabled, int cylinderCount, int sparkPlugMask,
	float throttle, float loadFactor, float speedLimitRpm, EngineWearInputs wear, boolean camshaftInstalled) {

	public EngineInputs {
		cylinderCount = Math.min(Math.max(cylinderCount, 1), EngineTuning.MAX_CYLINDERS);
		sparkPlugMask &= (1 << cylinderCount) - 1;
		throttle = EngineTuning.clamp01(throttle);
		loadFactor = EngineTuning.clamp01(loadFactor);
		speedLimitRpm = Math.min(Math.max(speedLimitRpm, EngineTuning.STALL_RPM), EngineTuning.MAX_RPM);
		// A caller that has nothing to say about condition is describing a new
		// engine, not an unknown one. Fail-open here is the safe direction: it can
		// only ever understate wear, never invent it.
		if (wear == null)
			wear = EngineWearInputs.PRISTINE;
	}

	/**
	 * The same, for a caller that has a Camshaft fitted and nothing to say about
	 * condition.
	 */
	public EngineInputs(boolean structureValid, boolean ignitionEnabled, int cylinderCount, int sparkPlugMask,
		float throttle, float loadFactor, float speedLimitRpm, EngineWearInputs wear) {
		this(structureValid, ignitionEnabled, cylinderCount, sparkPlugMask, throttle, loadFactor, speedLimitRpm,
			wear, true);
	}

	/**
	 * A multi-cylinder engine whose parts are all new. Every call site that predates
	 * wear, and every test that is not about it.
	 *
	 * <p>The Camshaft defaults to <i>fitted</i>, which is the right default for a
	 * convenience constructor: every one of these describes an engine the caller
	 * intends to be able to run, and a test that is about a missing valvetrain says so
	 * by naming it.
	 */
	public EngineInputs(boolean structureValid, boolean ignitionEnabled, int cylinderCount, int sparkPlugMask,
		float throttle, float loadFactor, float speedLimitRpm) {
		this(structureValid, ignitionEnabled, cylinderCount, sparkPlugMask, throttle, loadFactor, speedLimitRpm,
			EngineWearInputs.PRISTINE, true);
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
