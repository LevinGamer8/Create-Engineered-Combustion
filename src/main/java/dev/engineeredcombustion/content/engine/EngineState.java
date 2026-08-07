package dev.engineeredcombustion.content.engine;

/**
 * The authoritative mechanical state of a single engine.
 *
 * <p>Free of any Minecraft, NeoForge or Create type: pure simulation. The Create
 * kinetic network is fed from this state by a separate adapter
 * ({@code EngineFlywheelBlockEntity}), never the other way around.
 *
 * <h2>Two speeds, on purpose</h2>
 * <dl>
 * <dt>{@link #getMechanicalRpm() mechanical RPM}</dt>
 * <dd>What Create says the flywheel is <i>actually</i> doing right now. This is
 * the only input to the crank angle, which is why the crankshaft, the piston,
 * the flywheel disc and every attached Create shaft can never visually
 * disagree - they all ultimately come from this one number, on both client and
 * server.</dd>
 * <dt>{@link #getSimulatedRpm() simulated RPM}</dt>
 * <dd>The engine's own angular velocity, integrated from combustion torque,
 * friction and flywheel inertia. It ripples within each revolution, as a
 * single-cylinder engine should. It is quantised behind a deadband into
 * {@link #getPublishedRpm()} before Create ever sees it.</dd>
 * </dl>
 * While something else is turning the engine, simulated RPM simply follows
 * mechanical RPM. Once combustion starts, the simulation takes over and its
 * published output is what makes Create hand the kinetic source to us.
 *
 * <h2>Crank angle</h2>
 * {@link #getCrankAngleDegrees()} stays in {@code [0, 360)} and is the single
 * source of truth for every mechanical animation and for combustion timing.
 * There is deliberately no separate animation timer anywhere in the codebase.
 */
public final class EngineState {

	// --- rotation -----------------------------------------------------------
	private float crankAngleDegrees;
	private float mechanicalRpm;
	private float lastAngleDeltaDegrees;

	// --- simulation ---------------------------------------------------------
	private float simulatedRpm;
	private float publishedRpm;

	// --- conditions ---------------------------------------------------------
	private boolean structureValid;
	private boolean ignitionEnabled;
	private boolean externallyDriven;

	// --- combustion ---------------------------------------------------------
	private boolean firedThisRevolution;
	private boolean powerStrokeActive;
	private int ticksSinceCombustion = -1;

	private EnginePhase phase = EnginePhase.STOPPED;
	private int ticksSincePublish;

	// ------------------------------------------------------------------------
	// Step 1 - runs on BOTH sides
	// ------------------------------------------------------------------------

	/**
	 * Advances the crank angle by exactly one tick of the real mechanical speed.
	 *
	 * <p>Client and server both call this with the same value (Create synchronises
	 * the flywheel's kinetic speed for us), which is what keeps the animation in
	 * step without this mod sending its own per-tick packets.
	 *
	 * <p>Negative speed turns the crank backwards.
	 */
	public void advanceCrankAngle(float mechanicalRpm) {
		this.mechanicalRpm = mechanicalRpm;
		lastAngleDeltaDegrees = EngineTuning.degreesPerTick(mechanicalRpm);
		if (lastAngleDeltaDegrees != 0.0F)
			crankAngleDegrees = normalizeDegrees(crankAngleDegrees + lastAngleDeltaDegrees);
	}

	/**
	 * Client-side approximation of the power stroke, derived purely from the
	 * synced phase and the locally advanced crank angle, so it costs no packets.
	 */
	public void updateClientPowerStroke() {
		powerStrokeActive = phase == EnginePhase.RUNNING && isWithinPowerStroke();
	}

	// ------------------------------------------------------------------------
	// Step 2 - server only
	// ------------------------------------------------------------------------

	/**
	 * Runs combustion, inertia and friction for one tick and decides what Create
	 * should be told.
	 *
	 * @param externallyDriven whether Create currently gives the flywheel a source
	 *                         other than the engine itself
	 * @return true when {@link #getPublishedRpm()} changed and Create's generated
	 *         rotation therefore has to be updated
	 */
	public boolean tickSimulation(boolean structureValid, boolean ignitionEnabled, boolean externallyDriven) {
		this.structureValid = structureValid;
		this.ignitionEnabled = ignitionEnabled;
		this.externallyDriven = externallyDriven;

		if (ticksSinceCombustion >= 0 && ticksSinceCombustion < Integer.MAX_VALUE)
			ticksSinceCombustion++;
		if (ticksSincePublish < Integer.MAX_VALUE)
			ticksSincePublish++;

		// While something else is turning us and we are not making our own power,
		// the simulation has no say: Create's speed IS the engine's speed.
		if (externallyDriven && phase != EnginePhase.RUNNING)
			simulatedRpm = mechanicalRpm;

		boolean combustionPossible = structureValid && ignitionEnabled;
		float requiredRpm = phase == EnginePhase.RUNNING ? EngineTuning.STALL_RPM : EngineTuning.START_RPM;
		// Forward rotation only. Cranking the engine backwards never ignites it.
		boolean mayIgnite = combustionPossible && lastAngleDeltaDegrees > 0.0F && simulatedRpm >= requiredRpm;

		if (crossedFiringAngle()) {
			firedThisRevolution = mayIgnite;
			if (firedThisRevolution)
				ticksSinceCombustion = 0;
		}
		powerStrokeActive = firedThisRevolution && combustionPossible && isWithinPowerStroke();

		integrate();

		// A source that is faster than us wins; our own speed can never be below
		// what Create is physically imposing on the shaft.
		if (externallyDriven && phase == EnginePhase.RUNNING)
			simulatedRpm = Math.max(simulatedRpm, mechanicalRpm);

		advancePhase(combustionPossible);

		return updatePublishedRpm();
	}

	/** netTorque -> angular acceleration -> angular velocity. */
	private void integrate() {
		float netTorque = powerStrokeActive ? EngineTuning.combustionTorqueAt(simulatedRpm) : 0.0F;
		// Friction always opposes the current direction of rotation, and is exactly
		// zero at rest so it can never push a stationary engine into motion.
		netTorque -= Math.signum(simulatedRpm) * EngineTuning.frictionTorqueAt(simulatedRpm);

		float next = simulatedRpm + netTorque / EngineTuning.FLYWHEEL_INERTIA;

		// Friction alone must never drag the engine through zero into reverse.
		if (!powerStrokeActive && simulatedRpm != 0.0F && Math.signum(next) != Math.signum(simulatedRpm))
			next = 0.0F;

		simulatedRpm = clamp(next, -EngineTuning.MAX_RPM, EngineTuning.MAX_RPM);
	}

	private void advancePhase(boolean combustionPossible) {
		switch (phase) {
			case STOPPED -> {
				if (mechanicalRpm != 0.0F)
					phase = EnginePhase.CRANKING;
			}
			case CRANKING -> {
				if (firedThisRevolution)
					phase = EnginePhase.RUNNING;
				else if (mechanicalRpm == 0.0F)
					phase = EnginePhase.STOPPED;
			}
			case RUNNING -> {
				if (!combustionPossible)
					phase = EnginePhase.COASTING;
				else if (simulatedRpm < EngineTuning.STALL_RPM)
					stop();
			}
			case COASTING -> {
				if (simulatedRpm < EngineTuning.STALL_RPM)
					stop();
				else if (combustionPossible && simulatedRpm >= EngineTuning.START_RPM)
					phase = EnginePhase.RUNNING;
			}
		}
	}

	private void stop() {
		phase = EnginePhase.STOPPED;
		simulatedRpm = 0.0F;
		firedThisRevolution = false;
		powerStrokeActive = false;
	}

	// ------------------------------------------------------------------------
	// Combustion timing
	// ------------------------------------------------------------------------

	/**
	 * Detects that the crank passed the firing angle during the tick that just
	 * happened, rather than testing the angle for equality - ticks routinely skip
	 * right over any exact value. Because the test is a crossing it can fire at
	 * most once per revolution by construction.
	 *
	 * <p>Only forward rotation counts.
	 */
	private boolean crossedFiringAngle() {
		float delta = lastAngleDeltaDegrees;
		if (delta <= 0.0F)
			return false;
		if (delta >= 360.0F)
			return true;
		float travelledPastFiringAngle =
			normalizeDegrees(crankAngleDegrees - delta - EngineTuning.FIRING_ANGLE_DEGREES);
		return travelledPastFiringAngle + delta >= 360.0F;
	}

	private boolean isWithinPowerStroke() {
		return normalizeDegrees(crankAngleDegrees - EngineTuning.FIRING_ANGLE_DEGREES)
			< EngineTuning.POWER_STROKE_DEGREES;
	}

	// ------------------------------------------------------------------------
	// Create output
	// ------------------------------------------------------------------------

	/**
	 * Decides whether Create's generated speed needs to change.
	 *
	 * <p>Three separate guards keep this from thrashing the kinetic network:
	 * quantisation to {@link EngineTuning#NETWORK_RPM_QUANTUM}, a deadband of one
	 * full quantum around the currently published value, and a minimum interval
	 * between non-zero updates. Transitions to and from zero bypass the interval
	 * so the engine engages and disengages promptly; the large START/STALL gap is
	 * what guarantees those cannot repeat quickly enough to trip Create's flicker
	 * protection.
	 */
	private boolean updatePublishedRpm() {
		float target = phase.generatesPower() ? simulatedRpm : 0.0F;

		if (target < EngineTuning.STALL_RPM) {
			if (publishedRpm == 0.0F)
				return false;
			publishedRpm = 0.0F;
			ticksSincePublish = 0;
			return true;
		}

		if (publishedRpm != 0.0F) {
			if (Math.abs(target - publishedRpm) < EngineTuning.NETWORK_RPM_DEADBAND)
				return false;
			if (ticksSincePublish < EngineTuning.NETWORK_MIN_UPDATE_INTERVAL_TICKS)
				return false;
		}

		float quantised = Math.round(target / EngineTuning.NETWORK_RPM_QUANTUM) * EngineTuning.NETWORK_RPM_QUANTUM;
		quantised = clamp(quantised, EngineTuning.NETWORK_RPM_QUANTUM, EngineTuning.MAX_RPM);
		if (quantised == publishedRpm)
			return false;

		publishedRpm = quantised;
		ticksSincePublish = 0;
		return true;
	}

	// ------------------------------------------------------------------------
	// Accessors
	// ------------------------------------------------------------------------

	/** Always in {@code [0, 360)}. */
	public float getCrankAngleDegrees() {
		return crankAngleDegrees;
	}

	/** Crank angle interpolated into the current frame, for renderers. */
	public float getRenderCrankAngleDegrees(float partialTicks) {
		return normalizeDegrees(crankAngleDegrees + lastAngleDeltaDegrees * partialTicks);
	}

	/** What Create says the flywheel is really doing. Drives all animation. */
	public float getMechanicalRpm() {
		return mechanicalRpm;
	}

	/** The engine's own integrated angular velocity. */
	public float getSimulatedRpm() {
		return simulatedRpm;
	}

	/** The latched value Create sees as this engine's generated speed. */
	public float getPublishedRpm() {
		return publishedRpm;
	}

	public EnginePhase getPhase() {
		return phase;
	}

	public boolean isPowerStrokeActive() {
		return powerStrokeActive;
	}

	public boolean isIgnitionEnabled() {
		return ignitionEnabled;
	}

	public boolean isStructureValid() {
		return structureValid;
	}

	/** Ticks since the last combustion event, or -1 if it has never fired. */
	public int getTicksSinceCombustion() {
		return ticksSinceCombustion;
	}

	public RotationSource getRotationSource() {
		if (publishedRpm != 0.0F && !externallyDriven)
			return RotationSource.ENGINE;
		if (mechanicalRpm != 0.0F)
			return RotationSource.EXTERNAL;
		return RotationSource.NONE;
	}

	public float getPistonPosition() {
		return CrankMath.pistonPosition(crankAngleDegrees);
	}

	// ------------------------------------------------------------------------
	// Persistence / synchronisation support
	// ------------------------------------------------------------------------

	public void setCrankAngleDegrees(float crankAngleDegrees) {
		this.crankAngleDegrees = normalizeDegrees(crankAngleDegrees);
	}

	public void setPhase(EnginePhase phase) {
		this.phase = phase;
	}

	public void setSimulatedRpm(float simulatedRpm) {
		this.simulatedRpm = clamp(simulatedRpm, -EngineTuning.MAX_RPM, EngineTuning.MAX_RPM);
	}

	public void setPublishedRpm(float publishedRpm) {
		this.publishedRpm = publishedRpm;
	}

	public void setIgnitionEnabled(boolean ignitionEnabled) {
		this.ignitionEnabled = ignitionEnabled;
	}

	public void setStructureValid(boolean structureValid) {
		this.structureValid = structureValid;
	}

	public void setExternallyDriven(boolean externallyDriven) {
		this.externallyDriven = externallyDriven;
	}

	// ------------------------------------------------------------------------

	private static float clamp(float value, float min, float max) {
		return value < min ? min : Math.min(value, max);
	}

	private static float normalizeDegrees(float degrees) {
		float wrapped = degrees % 360.0F;
		return wrapped < 0.0F ? wrapped + 360.0F : wrapped;
	}
}
