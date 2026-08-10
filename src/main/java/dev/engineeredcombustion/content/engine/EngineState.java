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
 * <dd>What Create says the crankshaft is <i>actually</i> doing right now. This
 * is the only input to the crank angle, which is why the crankshaft, the piston,
 * the flywheel disc and every attached Create shaft - on either end - can never
 * visually disagree; they all ultimately come from this one number, on both
 * client and server.</dd>
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
 * <h2>Throttle</h2>
 * The throttle never writes a speed. It scales the torque a combustion event is
 * worth and moves the governor band with it, so the engine has to accelerate to
 * its new equilibrium through the same inertia it always had - see
 * {@code integrate()} and {@link EngineTuning#peakCombustionTorqueFor(float)}.
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

	/** Main throttle opening, {@code [0, 1]}. Re-read from the carburetor each tick. */
	private float throttle;
	/** Network stress over capacity on the last simulated tick, {@code [0, 1]}. */
	private float loadFactor;
	/** Highest speed this engine may reach, reconciled with Create's config. */
	private float speedLimitRpm = EngineTuning.MAX_RPM;
	/**
	 * Speed the throttle is asking for, already capped by {@link #speedLimitRpm}.
	 * Held rather than recomputed so the governor and the clamp cannot disagree on
	 * a server that has lowered Create's {@code maxRotationSpeed} below the
	 * engine's full-throttle target.
	 */
	private float targetRpm = EngineTuning.IDLE_RPM;

	// --- combustion ---------------------------------------------------------
	private boolean firedThisRevolution;
	private boolean powerStrokeActive;
	/** 1 while running, a fraction of that for a pre-start kick. */
	private float powerStrokeStrength;
	private int ticksSinceCombustion = -1;
	private boolean fuelAvailable;

	/**
	 * Counts ignition coil firings, and counts charges that actually burned.
	 *
	 * <p><b>These two are the engine's event channel.</b> Both are incremented on
	 * the server, at exactly the point the thing they name happens, and both are
	 * synchronised as plain numbers. The client does not decide when a spark or a
	 * combustion occurred - it notices that a counter moved and reacts.
	 *
	 * <p>That replaces re-deriving the events on the client from the crank angle,
	 * which was cheap but only <i>approximately</i> right: the client cannot know
	 * whether the server's fuel draw succeeded, so a dry engine still flashed for
	 * a revolution, and the flash and the firing sound came from two different
	 * mechanisms and could land a tick or two apart. A counter cannot disagree
	 * with the event it counts.
	 *
	 * <p>Wrapping is fine and overflow is irrelevant: only inequality is ever
	 * tested, so any change means "one or more happened since you last looked".
	 */
	private int sparkEventId;
	private int combustionEventId;

	/**
	 * Ticks left on the visible flash inside the combustion chamber. Client-side
	 * bookkeeping, started by {@link #triggerCombustionFlash()} when the
	 * combustion counter moves and run down by {@link #updateClientVisuals()}.
	 */
	private int combustionFlashTicks;

	// --- lubrication --------------------------------------------------------
	private LubricationState lubrication = LubricationState.DRY;
	/**
	 * Running combustion events banked towards the next oil draw. Counted rather
	 * than timed, so oil use follows how hard the engine has actually worked.
	 */
	private int combustionEventsSinceOilDraw;

	// --- start attempt ------------------------------------------------------
	private int startProgress;
	private int requiredStartCycles;
	private int ticksSinceStartActivity;

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
	 * Runs the purely visual side of the engine on the client.
	 *
	 * <p>The power stroke is re-derived from the crank angle, which is exact on
	 * both sides. The combustion flash is <i>not</i> derived here: it is started
	 * by {@link #triggerCombustionFlash()} when the server's combustion counter
	 * moves, and this only counts it down. Predicting it was the bug - the client
	 * cannot know whether the server's fuel draw succeeded, so an engine whose
	 * tank had just run dry went on flashing for a revolution, and the flash and
	 * the firing sound were produced by two different mechanisms that could land a
	 * tick or two apart.
	 */
	public void updateClientVisuals() {
		powerStrokeActive = (phase == EnginePhase.RUNNING || phase == EnginePhase.STARTING)
			&& isWithinPowerStroke();

		if (combustionFlashTicks > 0)
			combustionFlashTicks--;
	}

	/**
	 * Lights the chamber for {@link EngineTuning#COMBUSTION_FLASH_TICKS} ticks.
	 *
	 * <p>Called on the client when {@link #getCombustionEventId()} changes, i.e.
	 * exactly once per charge that really burned - the same event that consumed
	 * the fuel, delivered the torque and advanced the start attempt.
	 */
	public void triggerCombustionFlash() {
		combustionFlashTicks = EngineTuning.COMBUSTION_FLASH_TICKS;
	}

	// ------------------------------------------------------------------------
	// Step 2 - server only
	// ------------------------------------------------------------------------

	/**
	 * Runs combustion, inertia and friction for one tick and decides what Create
	 * should be told.
	 *
	 * @return true when {@link #getPublishedRpm()} changed and Create's generated
	 *         rotation therefore has to be updated
	 */
	public boolean tickSimulation(EngineInputs inputs, FuelSupply fuel, OilSupply oil, java.util.Random random) {
		this.structureValid = inputs.structureValid();
		this.ignitionEnabled = inputs.ignitionEnabled();
		this.externallyDriven = inputs.externallyDriven();
		this.throttle = inputs.throttle();
		this.loadFactor = inputs.loadFactor();
		this.speedLimitRpm = inputs.speedLimitRpm();
		this.targetRpm = inputs.targetRpm();
		this.fuelAvailable = fuel.hasFuel();
		boolean externallyDriven = this.externallyDriven;
		// Read every tick: the sump can be filled or drained by a pipe at any time,
		// and lubrication has to take effect immediately rather than at some
		// revalidation interval.
		this.lubrication = oil.lubrication();

		if (ticksSinceCombustion >= 0 && ticksSinceCombustion < Integer.MAX_VALUE)
			ticksSinceCombustion++;
		if (ticksSincePublish < Integer.MAX_VALUE)
			ticksSincePublish++;

		// While something else is turning us and we are not making our own power,
		// the simulation has no say: Create's speed IS the engine's speed.
		if (externallyDriven && !phase.simulationOwnsSpeed())
			simulatedRpm = mechanicalRpm;

		// Fuel is now a hard requirement. An engine with a missing or empty
		// carburetor is mechanically fine but can never produce power.
		boolean combustionPossible = structureValid && ignitionEnabled && fuelAvailable;
		float requiredRpm = phase == EnginePhase.RUNNING ? EngineTuning.STALL_RPM : EngineTuning.START_RPM;
		// Forward rotation only. Cranking the engine backwards never ignites it.
		boolean mayIgnite = combustionPossible && lastAngleDeltaDegrees > 0.0F && simulatedRpm >= requiredRpm;

		boolean firingAngleCrossed = crossedFiringAngle();

		// The coil is wired to the crank, not to the fuel system. It fires whenever
		// the ignition is on and the engine is turning forwards fast enough for a
		// firing opportunity - whether or not there is any gasoline to light. That
		// is the mechanically honest model, and it is the useful one: a plug that
		// visibly sparks while the engine refuses to catch tells the player the
		// problem is fuel, and a plug that stays dark tells them it is ignition.
		if (firingAngleCrossed && structureValid && ignitionEnabled && lastAngleDeltaDegrees > 0.0F
			&& simulatedRpm >= requiredRpm)
			sparkEventId++;

		boolean ignitedThisTick = false;
		if (firingAngleCrossed) {
			// Fuel is drawn per firing event, never per tick, and only if the whole
			// charge is actually available - a partial draw must not produce power.
			if (mayIgnite && fuel.consume(EngineTuning.FUEL_PER_COMBUSTION_MB)) {
				firedThisRevolution = true;
				ignitedThisTick = true;
				ticksSinceCombustion = 0;
				ticksSinceStartActivity = 0;
				// One increment, here, at the single point where a charge is paid for
				// and burns. Everything downstream of a combustion - the torque, the
				// start cycle, the oil wear, and on the client the chamber flash and
				// the firing sound - is therefore describing this same event.
				combustionEventId++;
				if (phase != EnginePhase.RUNNING)
					registerStartCycle(random);
				else
					// Only a running engine wears oil. Start attempts are deliberately
					// free, so a hard-to-start engine is not also an oil sink.
					drawOilForCombustion(oil);
			} else {
				firedThisRevolution = false;
			}
		}

		// The crank must actually be turning forwards for a power stroke to push.
		// firedThisRevolution only changes when the firing angle is crossed, so on a
		// stalled crank it would otherwise stay latched and deliver free torque
		// every tick forever. This also makes an overstressed network - where
		// Create reports speed 0 - correctly produce no combustion torque.
		powerStrokeActive = firedThisRevolution && combustionPossible && lastAngleDeltaDegrees > 0.0F
			&& isWithinPowerStroke();
		powerStrokeStrength = phase == EnginePhase.RUNNING ? 1.0F : EngineTuning.START_KICK_TORQUE_FACTOR;

		integrate();

		// A source that is faster than us wins; our own speed can never be below
		// what Create is physically imposing on the shaft. Applies while STARTING
		// too, so a firing kick shows as a brief rise above the cranking speed.
		if (externallyDriven && phase.simulationOwnsSpeed())
			simulatedRpm = Math.max(simulatedRpm, mechanicalRpm);

		expireStaleStartAttempt(mayIgnite);
		advancePhase(combustionPossible, ignitedThisTick);

		return updatePublishedRpm();
	}

	/**
	 * Counts one successful pre-start firing opportunity, rolling the number of
	 * cycles this attempt needs the first time.
	 *
	 * <p>The required count is chosen once per attempt and then held - re-rolling
	 * it per revolution would make starting feel arbitrary, which is exactly what
	 * this is meant to avoid.
	 */
	private void registerStartCycle(java.util.Random random) {
		if (requiredStartCycles <= 0)
			requiredStartCycles = EngineTuning.MIN_START_CYCLES
				+ random.nextInt(EngineTuning.MAX_START_CYCLES - EngineTuning.MIN_START_CYCLES + 1);
		startProgress++;
	}

	/**
	 * Counts one running combustion event towards oil wear, and draws from the
	 * sump once enough have accumulated.
	 *
	 * <p>Counting events rather than ticks is what keeps consumption honest at any
	 * speed: one revolution costs the same whether the engine is idling or flat
	 * out. The counter only resets on a draw that actually succeeded, so an engine
	 * running dry does not silently forfeit the progress it made - and because the
	 * supply refuses partial draws, the tank can never go negative.
	 */
	private void drawOilForCombustion(OilSupply oil) {
		if (combustionEventsSinceOilDraw < Integer.MAX_VALUE)
			combustionEventsSinceOilDraw++;
		if (combustionEventsSinceOilDraw < EngineTuning.COMBUSTION_EVENTS_PER_OIL_MB)
			return;
		if (oil.consume(EngineTuning.OIL_PER_CONSUMPTION_MB))
			combustionEventsSinceOilDraw = 0;
	}

	/**
	 * Abandons a start attempt that has gone quiet - the engine stopped turning,
	 * ran out of fuel, or ignition was switched off - so a nearly-complete start
	 * is not remembered indefinitely.
	 */
	private void expireStaleStartAttempt(boolean mayIgnite) {
		if (mayIgnite)
			ticksSinceStartActivity = 0;
		else if (ticksSinceStartActivity < Integer.MAX_VALUE)
			ticksSinceStartActivity++;

		if (startProgress > 0 && ticksSinceStartActivity > EngineTuning.START_ATTEMPT_TIMEOUT_TICKS)
			resetStartAttempt();
	}

	private void resetStartAttempt() {
		startProgress = 0;
		requiredStartCycles = 0;
		firedThisRevolution = false;
		powerStrokeActive = false;
	}

	/**
	 * netTorque -&gt; angular acceleration -&gt; angular velocity.
	 *
	 * <p>The throttle appears here and nowhere else. It does not set a speed: it
	 * chooses how much torque a combustion event is worth
	 * ({@link EngineTuning#peakCombustionTorqueFor}) and where the governor
	 * starts taking that torque away again. Everything the player sees - spinning
	 * up over seconds, overshooting slightly, sagging under load, coasting back
	 * down when the throttle closes - falls out of integrating that torque
	 * against friction and flywheel inertia, exactly as it did before the
	 * throttle existed.
	 */
	private void integrate() {
		float netTorque = powerStrokeActive
			? EngineTuning.combustionTorqueAt(simulatedRpm, targetRpm) * powerStrokeStrength
			: 0.0F;
		// Friction always opposes the current direction of rotation, and is exactly
		// zero at rest so it can never push a stationary engine into motion. The
		// kinetic load Create has hung on the engine is drag of the same kind.
		float drag = EngineTuning.frictionTorqueAt(simulatedRpm, lubrication)
			+ EngineTuning.loadDragTorque(loadFactor);
		netTorque -= Math.signum(simulatedRpm) * drag;

		float next = simulatedRpm + netTorque / EngineTuning.FLYWHEEL_INERTIA;

		// Friction alone must never drag the engine through zero into reverse.
		if (!powerStrokeActive && simulatedRpm != 0.0F && Math.signum(next) != Math.signum(simulatedRpm))
			next = 0.0F;

		simulatedRpm = clamp(next, -speedLimitRpm, speedLimitRpm);
	}

	private void advancePhase(boolean combustionPossible, boolean ignitedThisTick) {
		switch (phase) {
			case STOPPED -> {
				if (mechanicalRpm != 0.0F)
					phase = EnginePhase.CRANKING;
			}
			case CRANKING -> {
				// The first successful firing opens a start attempt; it does not start
				// the engine. That now takes several cycles. This deliberately tests
				// "ignited on this tick" rather than the latched firedThisRevolution,
				// which would otherwise bounce the phase back and forth once an
				// abandoned attempt drops us out of STARTING.
				if (ignitedThisTick)
					phase = EnginePhase.STARTING;
				else if (mechanicalRpm == 0.0F)
					// stop() rather than a bare phase change, so the simulated speed is
					// zeroed too and the readout does not show a stopped engine still
					// bleeding off RPM.
					stop();
			}
			case STARTING -> {
				if (requiredStartCycles > 0 && startProgress >= requiredStartCycles) {
					phase = EnginePhase.RUNNING;
					resetStartAttempt();
				} else if (mechanicalRpm == 0.0F && simulatedRpm < EngineTuning.STALL_RPM) {
					stop();
				} else if (startProgress == 0) {
					// expireStaleStartAttempt cleared it - the attempt went cold.
					phase = EnginePhase.CRANKING;
				}
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
		resetStartAttempt();
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
		// The upper bound is the *runtime* limit, not the tuning constant: Create's
		// maxRotationSpeed is a server config and going past it makes
		// RotationPropagator destroy the block rather than merely refuse the speed.
		quantised = clamp(quantised, EngineTuning.NETWORK_RPM_QUANTUM, speedLimitRpm);
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

	/** Main throttle opening on the last simulated tick, {@code [0, 1]}. */
	public float getThrottle() {
		return throttle;
	}

	/** Speed the current throttle setting is asking the engine to hold. */
	public float getTargetRpm() {
		return targetRpm;
	}

	/** Network stress over capacity on the last simulated tick, {@code [0, 1]}. */
	public float getLoadFactor() {
		return loadFactor;
	}

	/**
	 * Ignition firings so far. Compare against a remembered value to detect that
	 * the coil fired; never interpret the number itself.
	 */
	public int getSparkEventId() {
		return sparkEventId;
	}

	/** Charges burned so far. Same contract as {@link #getSparkEventId()}. */
	public int getCombustionEventId() {
		return combustionEventId;
	}

	/** Whether the combustion chamber should be drawn lit this frame. */
	public boolean isCombustionFlashActive() {
		return combustionFlashTicks > 0;
	}

	/**
	 * Flash brightness, 1 on the tick it fired and fading to 0.
	 *
	 * @param partialTicks interpolation into the current frame, so the fade is
	 *                     smooth rather than stepping once per tick
	 */
	public float getCombustionFlashIntensity(float partialTicks) {
		if (combustionFlashTicks <= 0)
			return 0.0F;
		float remaining = combustionFlashTicks - partialTicks;
		if (remaining <= 0.0F)
			return 0.0F;
		return remaining / EngineTuning.COMBUSTION_FLASH_TICKS;
	}

	/** Firing opportunities banked so far in the current start attempt. */
	public int getStartProgress() {
		return startProgress;
	}

	/** How many this attempt needs, or 0 when no attempt is in progress. */
	public int getRequiredStartCycles() {
		return requiredStartCycles;
	}

	/** Whether the fuel supply reported usable fuel on the last simulated tick. */
	public boolean isFuelAvailable() {
		return fuelAvailable;
	}

	public boolean isIgnitionEnabled() {
		return ignitionEnabled;
	}

	/** How well lubricated the engine was on the last simulated tick. */
	public LubricationState getLubrication() {
		return lubrication;
	}

	/** Running combustion events banked towards the next millibucket of oil. */
	public int getCombustionEventsSinceOilDraw() {
		return combustionEventsSinceOilDraw;
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

	public void setStartAttempt(int startProgress, int requiredStartCycles) {
		this.startProgress = Math.max(0, startProgress);
		this.requiredStartCycles = Math.max(0, requiredStartCycles);
	}

	public void setFuelAvailable(boolean fuelAvailable) {
		this.fuelAvailable = fuelAvailable;
	}

	/**
	 * Adopts the server's event counters. Client side, from the synchronised
	 * block entity data; the values are never interpreted, only compared.
	 */
	public void setEventIds(int sparkEventId, int combustionEventId) {
		this.sparkEventId = sparkEventId;
		this.combustionEventId = combustionEventId;
	}

	public void setLubrication(LubricationState lubrication) {
		this.lubrication = lubrication;
	}

	/** Restores the wear counter so a chunk reload does not reset oil progress. */
	public void setCombustionEventsSinceOilDraw(int events) {
		this.combustionEventsSinceOilDraw = Math.max(0, events);
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
