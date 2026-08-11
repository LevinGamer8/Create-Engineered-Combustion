package dev.engineeredcombustion.content.engine;

/**
 * The authoritative mechanical state of a single engine.
 *
 * <p>Free of any Minecraft, NeoForge or Create type: pure simulation. The Create
 * kinetic network is fed from this state by a separate adapter
 * ({@code EngineFlywheelBlockEntity}), never the other way around.
 *
 * <h2>One momentum, two readings of it</h2>
 * <dl>
 * <dt>{@link #getSimulatedRpm() simulated RPM}</dt>
 * <dd><b>The engine's angular velocity, always.</b> There is one crankshaft and
 * it has one speed, so this is never allowed to mean something different from
 * what the shaft is physically doing. When Create holds the shaft at a speed -
 * because another source on the network is turning it - this <i>absorbs</i> that
 * speed rather than keeping a stale opinion beside it. When nothing is holding
 * the shaft, this free-runs on friction and inertia. That single rule is what
 * makes an engine spun to 200 RPM by a fast network coast down from 200 when the
 * network is taken away, instead of snapping back to whatever it was doing
 * before it was connected.</dd>
 * <dt>{@link #getMechanicalRpm() mechanical RPM}</dt>
 * <dd>The speed the crank angle actually advances by this tick: Create's speed
 * while Create is driving the shaft, the engine's own momentum while it is not.
 * The only input to the crank angle, which is why the crankshaft, the piston,
 * the flywheel disc and every attached Create shaft - on either end - can never
 * visually disagree.</dd>
 * <dt>{@link #getPublishedRpm() published RPM}</dt>
 * <dd>What Create is told this engine <i>generates</i>. Non-zero only while
 * {@link #isActivelyGenerating()}, quantised behind a deadband, and capped at
 * what the engine's own combustion could sustain.</dd>
 * </dl>
 *
 * <h2>Generation is one predicate</h2>
 * {@link #isActivelyGenerating()} is the single authority on whether this engine
 * is producing power. Generated speed, stress capacity, passive drag, the HUD and
 * the audio all read it; none of them re-derives its own version. Turning is not
 * generating: an engine that is out of fuel, unlit, mid-start or merely being
 * spun by a neighbour is turning, and contributes nothing.
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

	/**
	 * Whether the crankshaft is turning on nothing but its own momentum, because
	 * Create is not holding it at any speed.
	 *
	 * <p>Computed identically on both sides by {@link #tickRotation}, and it is
	 * what lets a disconnected engine keep visibly spinning down: while it is true
	 * the crank angle advances from {@link #simulatedRpm} rather than from Create.
	 */
	private boolean freeRotation;

	/**
	 * The latched answer to {@link #isActivelyGenerating()}.
	 *
	 * <p>Evaluated once per server tick, from {@link #evaluateActiveGeneration()},
	 * and synchronised - so the client's overlays, audio and rotation rule get the
	 * server's answer rather than an approximation of it. This is deliberately a
	 * stored bit rather than a live predicate: the conditions live in exactly one
	 * method, and every consumer on either side reads exactly one field.
	 */
	private boolean activelyGenerating;

	// --- conditions ---------------------------------------------------------
	private boolean structureValid;
	private boolean ignitionEnabled;
	/**
	 * Whether the cylinder head has a Spark Plug in it.
	 *
	 * <p>The coil has somewhere to discharge only if this is true. It is what
	 * separates "the ignition is switched on" from "a spark can happen", and it is
	 * deliberately independent of {@link #structureValid}: an engine with no plug
	 * turns over perfectly.
	 */
	private boolean sparkPlugInstalled;
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
	 * One tick of rotation: reconciles the engine's momentum with whatever Create
	 * is doing to the shaft, then advances the crank angle.
	 *
	 * <p><b>Run on both sides, from the same inputs.</b> Every value it reads -
	 * Create's kinetic speed, whether this block has a kinetic source, the latched
	 * generation flag - is synchronised, so client and server derive the same crank
	 * angle and the same momentum without this mod sending a packet per tick.
	 *
	 * @param shaftSpeed       what Create says this crankshaft is doing
	 * @param shaftDriven      whether Create is <i>holding</i> the shaft at that
	 *                         speed. True even at zero when the network is
	 *                         overstressed - a jammed network stops the engine, it
	 *                         does not release it to freewheel
	 * @param externallyDriven whether the rotation on this shaft originates
	 *                         somewhere other than this engine
	 */
	public void tickRotation(float shaftSpeed, boolean shaftDriven, boolean externallyDriven) {
		this.externallyDriven = externallyDriven;
		this.freeRotation = !shaftDriven;
		if (shaftDriven)
			absorbImposedSpeed(shaftSpeed);
		advanceCrankAngle(freeRotation ? simulatedRpm : shaftSpeed);
	}

	/**
	 * Takes on the speed Create is imposing on the shaft, because there is only one
	 * crankshaft and it can only be doing one thing.
	 *
	 * <p>This is the whole of the fix for the RPM snap. The engine used to keep its
	 * own idea of its speed while an external network span it, so disconnecting a
	 * fast source revealed a stale number underneath and the engine appeared to
	 * teleport from 200 RPM back to 64. Now there is no second number to reveal.
	 *
	 * <p>Two cases must <i>not</i> absorb, and both are about the speed already
	 * being this engine's own work:
	 * <ul>
	 * <li>the engine is the network's source - then Create's speed came <i>from</i>
	 * the engine, and absorbing it back would pin the engine to its own published
	 * value and quietly cancel the load sag that makes it respond to work;</li>
	 * <li>the engine's combustion has already carried it past the speed it is being
	 * turned at - a firing kick during a start, or a spin-up. Absorbing then would
	 * let a hand crank hold a running engine down at cranking speed forever.</li>
	 * </ul>
	 *
	 * <p>Sign is carried through untouched, so an engine driven backwards holds
	 * backwards momentum and coasts down in the direction it was actually turning.
	 */
	private void absorbImposedSpeed(float shaftSpeed) {
		if (!externallyDriven && shaftSpeed != 0.0F)
			return;
		if (shaftSpeed != 0.0F && (activelyGenerating || phase.isFiring())
			&& Math.abs(simulatedRpm) >= Math.abs(shaftSpeed))
			return;
		simulatedRpm = shaftSpeed;
	}

	/**
	 * Advances the crank angle by exactly one tick of the given speed.
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
		powerStrokeActive = phase.isFiring() && isWithinPowerStroke();

		if (combustionFlashTicks > 0)
			combustionFlashTicks--;
	}

	/**
	 * Spins the flywheel down on the client while nothing is driving it.
	 *
	 * <p>The client has to do this itself, because a coasting engine generates
	 * nothing: Create's speed for it is zero, so there is no synchronised number
	 * left to animate from. What makes that safe is that the coast is
	 * <i>deterministic</i> - it runs the very same integration the server runs,
	 * with no combustion and no network load (a freewheeling engine is by
	 * definition on no network), from a starting speed the server synchronised at
	 * the moment it stopped generating. Both sides therefore trace the same curve,
	 * and the periodic resync only ever confirms it.
	 */
	public void tickClientCoast() {
		if (!freeRotation || simulatedRpm == 0.0F)
			return;
		integrate(0.0F, 0.0F);
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
		this.sparkPlugInstalled = inputs.sparkPlugInstalled();
		this.throttle = inputs.throttle();
		this.loadFactor = inputs.loadFactor();
		this.speedLimitRpm = inputs.speedLimitRpm();
		this.targetRpm = inputs.targetRpm();
		this.fuelAvailable = fuel.hasFuel();
		// Read every tick: the sump can be filled or drained by a pipe at any time,
		// and lubrication has to take effect immediately rather than at some
		// revalidation interval.
		this.lubrication = oil.lubrication();

		if (ticksSinceCombustion >= 0 && ticksSinceCombustion < Integer.MAX_VALUE)
			ticksSinceCombustion++;
		if (ticksSincePublish < Integer.MAX_VALUE)
			ticksSincePublish++;

		// The engine's speed is no longer reconciled with Create here. tickRotation
		// did that, before the crank angle was advanced and on both sides, so by the
		// time the simulation runs there is exactly one momentum to integrate.

		// THE TWO GATES, in the order the machine imposes them.
		//
		// A spark needs an assembled engine, a live ignition and somewhere for the
		// coil to discharge - a Spark Plug. Fuel has nothing to do with it: the
		// coil is wired to the crank, not to the fuel system, so a plug fires
		// whether or not there is gasoline to light. That is the mechanically
		// honest model and it is the useful one, because it makes the two failures
		// distinguishable by looking: a plug that visibly sparks while the engine
		// refuses to catch says the problem is fuel, a plug that stays dark says it
		// is ignition, and no plug at all says so on the overlay.
		//
		// Combustion needs a spark AND a charge to light. Nothing may reorder these
		// two: fuel must never be what decides whether the plug sparks.
		boolean sparkPossible = structureValid && ignitionEnabled && sparkPlugInstalled;
		boolean combustionPossible = sparkPossible && fuelAvailable;
		float requiredRpm = phase == EnginePhase.RUNNING ? EngineTuning.STALL_RPM : EngineTuning.START_RPM;
		// Forward rotation only. Cranking the engine backwards never ignites it.
		boolean mayIgnite = combustionPossible && lastAngleDeltaDegrees > 0.0F && simulatedRpm >= requiredRpm;

		boolean firingAngleCrossed = crossedFiringAngle();

		if (firingAngleCrossed && sparkPossible && lastAngleDeltaDegrees > 0.0F && simulatedRpm >= requiredRpm)
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

		integrate(powerStrokeActive
			? EngineTuning.combustionTorqueAt(simulatedRpm, targetRpm) * powerStrokeStrength
			: 0.0F, EngineTuning.loadDragTorque(loadFactor));

		expireStaleStartAttempt(mayIgnite);
		advancePhase(combustionPossible, ignitedThisTick);

		// Deliberately last, and deliberately after the phase has settled: this is
		// the one evaluation of "is this engine producing power", and everything
		// downstream - the speed Create is told, the capacity the network gets, the
		// drag it does not get, the HUD, the audio - is a consequence of it.
		activelyGenerating = evaluateActiveGeneration();

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
	private void integrate(float combustionTorque, float loadDragTorque) {
		// Friction always opposes the current direction of rotation, and is exactly
		// zero at rest so it can never push a stationary engine into motion. The
		// kinetic load Create has hung on the engine is drag of the same kind.
		float drag = EngineTuning.frictionTorqueAt(simulatedRpm, lubrication) + loadDragTorque;
		float netTorque = combustionTorque - Math.signum(simulatedRpm) * drag;

		float next = simulatedRpm + netTorque / EngineTuning.FLYWHEEL_INERTIA;

		// Friction alone must never drag the engine through zero into reverse. It
		// lands exactly on zero instead, which is what lets a coast-down actually
		// finish rather than creeping at a fraction of an RPM forever.
		if (combustionTorque == 0.0F && simulatedRpm != 0.0F
			&& Math.signum(next) != Math.signum(simulatedRpm))
			next = 0.0F;

		// The ceiling never *reduces* an existing speed: an engine that a fast
		// external network has spun past its own limit has to be allowed to coast
		// back down through friction, because clamping it would be exactly the
		// instantaneous snap this milestone exists to remove. The engine's own
		// combustion still cannot climb past the limit - the governor takes its
		// torque away well below it.
		float ceiling = Math.max(speedLimitRpm, Math.abs(simulatedRpm));
		simulatedRpm = clamp(next, -ceiling, ceiling);
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
				else if (hasComeToRest())
					// stop() rather than a bare phase change, so the simulated speed is
					// zeroed too and the readout does not show a stopped engine still
					// bleeding off RPM.
					stop();
			}
			case STARTING -> {
				if (requiredStartCycles > 0 && startProgress >= requiredStartCycles) {
					phase = EnginePhase.RUNNING;
					resetStartAttempt();
				} else if (hasComeToRest()) {
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
				// Tested against rest rather than against stall speed. Stalling is
				// about combustion - below 10 RPM no charge can carry the engine to
				// the next one - and this engine has already stopped burning. What is
				// left is a flywheel with momentum in it, and calling that stopped
				// while it is still visibly turning is what used to snap away the last
				// of a coast-down.
				if (hasComeToRest())
					stop();
				else if (combustionPossible && simulatedRpm >= EngineTuning.START_RPM)
					phase = EnginePhase.RUNNING;
			}
		}
	}

	/**
	 * Whether the crankshaft has genuinely come to a standstill: nothing is turning
	 * it and its own momentum has run out.
	 *
	 * <p>Both halves matter. Testing only the mechanical speed would declare a
	 * freewheeling engine stopped the instant its network let go of it, because a
	 * disconnected engine <i>has</i> no Create speed - and {@link #stop()} would
	 * then zero the momentum that the spin-down is made of.
	 */
	private boolean hasComeToRest() {
		return mechanicalRpm == 0.0F && Math.abs(simulatedRpm) < EngineTuning.REST_RPM;
	}

	private void stop() {
		phase = EnginePhase.STOPPED;
		simulatedRpm = 0.0F;
		firedThisRevolution = false;
		powerStrokeActive = false;
		activelyGenerating = false;
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
	 * <b>The</b> definition of this engine producing power, and the only one.
	 *
	 * <p>Every condition is here, in the order the machine imposes them, and each
	 * of them is a way an engine can be turning without generating anything:
	 * <ol>
	 * <li><b>caught</b> - {@link EnginePhase#mayGenerate()}. Cranking, starting and
	 * coasting are all rotation without self-sustaining combustion;</li>
	 * <li><b>assembled</b> - a cylinder with a piston in it and exactly one
	 * flywheel;</li>
	 * <li><b>lit</b> - the effective ignition, which is the player's switch unless
	 * a Redstone Control Module is holding it;</li>
	 * <li><b>able to spark</b> - a Spark Plug in the head;</li>
	 * <li><b>fuelled</b> - the carburetor can still pay for a charge. This is what
	 * makes fuel starvation instant: the tick after the last usable millibucket is
	 * drawn, the engine is not generating, whatever it is still doing
	 * mechanically;</li>
	 * <li><b>above stall</b>, and turning forwards;</li>
	 * <li><b>actually burning</b> - a charge really did fire within the last few
	 * revolutions. This is the condition that cannot be faked by an external
	 * source: a dead engine spun at 200 RPM by its neighbour satisfies every
	 * mechanical test and fails this one.</li>
	 * </ol>
	 *
	 * <p>Nothing here asks whether the engine is externally driven, and that is
	 * deliberate: two fuelled engines on one shaft are both genuinely burning fuel,
	 * and only one of them can be Create's source. Being spun by a neighbour is not
	 * disqualifying - producing no combustion is.
	 */
	private boolean evaluateActiveGeneration() {
		return phase.mayGenerate() && structureValid && ignitionEnabled && sparkPlugInstalled && fuelAvailable
			&& simulatedRpm >= EngineTuning.STALL_RPM && combustionIsCurrent();
	}

	/**
	 * Whether a charge burned recently enough that the engine is still, in any
	 * meaningful sense, running on combustion.
	 *
	 * <p>Scaled by speed, because the firing interval is: see
	 * {@link EngineTuning#generationCombustionAllowanceTicks}.
	 */
	private boolean combustionIsCurrent() {
		return ticksSinceCombustion >= 0
			&& ticksSinceCombustion <= EngineTuning.generationCombustionAllowanceTicks(simulatedRpm);
	}

	/**
	 * The fastest rotation this engine may claim to be generating.
	 *
	 * <p>Its own speed, but never more than its own combustion could hold: the
	 * governor's torque reaches zero at {@code target + GOVERNOR_RANGE / 2}, so
	 * that is the engine's honest ceiling at the current throttle. The cap never
	 * binds during normal running - an engine sits on its target, with a couple of
	 * RPM of ripple and a small overshoot on the way up - and only bites when
	 * something else on the network is spinning the engine faster than it could
	 * ever drive itself. Without it, motoring an idling engine at 200 RPM would
	 * have tripled the capacity it contributes for no extra fuel.
	 */
	private float generationCeiling() {
		return Math.min(simulatedRpm, targetRpm + EngineTuning.GOVERNOR_RANGE_RPM / 2.0F);
	}

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
		// The single gate. An engine that is not actively generating publishes
		// nothing, so Create's KineticNetwork#getActualCapacityOf - which multiplies
		// the registered capacity by |getGeneratedSpeed()| - hands it a capacity of
		// exactly zero, however fast the network is spinning it.
		float target = activelyGenerating ? generationCeiling() : 0.0F;

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

	/** The speed the crank is really turning at this tick. Drives all animation. */
	public float getMechanicalRpm() {
		return mechanicalRpm;
	}

	/** The engine's angular velocity: the one momentum, whatever is causing it. */
	public float getSimulatedRpm() {
		return simulatedRpm;
	}

	/** The latched value Create sees as this engine's generated speed. */
	public float getPublishedRpm() {
		return publishedRpm;
	}

	/**
	 * Whether this engine is producing power right now, and therefore whether it
	 * may contribute generated rotation and Stress Capacity to Create.
	 *
	 * <p>Valid on both sides: evaluated on the server by
	 * {@link #evaluateActiveGeneration()} and synchronised. Ask this - never a
	 * combination of phase, fuel and speed assembled at the call site.
	 */
	public boolean isActivelyGenerating() {
		return activelyGenerating;
	}

	/**
	 * Whether the crankshaft is turning on stored momentum alone, with nothing on
	 * the kinetic network driving it.
	 */
	public boolean isFreeRotating() {
		return freeRotation;
	}

	/** Whether something other than this engine is turning the shaft. */
	public boolean isExternallyDriven() {
		return externallyDriven;
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

	/**
	 * Whether a Spark Plug was fitted on the last simulated tick.
	 *
	 * <p>Synchronised so the client-side overlays can explain a dead engine
	 * without re-reading the world.
	 */
	public boolean isSparkPlugInstalled() {
		return sparkPlugInstalled;
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

	/**
	 * Where the rotation on this crankshaft is coming from.
	 *
	 * <p>Ordered so the answer is the <i>cause</i> rather than a symptom: an engine
	 * that is burning fuel is its own source even while a neighbour also drives the
	 * network, and an engine that is merely being spun says so plainly - which is
	 * the line to read when checking that a multi-engine network is honest.
	 */
	public RotationSource getRotationSource() {
		if (activelyGenerating)
			return RotationSource.ENGINE;
		if (externallyDriven && mechanicalRpm != 0.0F)
			return RotationSource.EXTERNAL;
		if (mechanicalRpm != 0.0F)
			return RotationSource.MOMENTUM;
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

	/**
	 * Restores the engine's momentum, bounded only against corrupt data.
	 *
	 * <p>Not clamped to the engine's own {@code MAX_RPM}: an engine that a fast
	 * external network is spinning genuinely holds more momentum than it could ever
	 * make for itself, and that momentum is what its coast-down is made of. Clamping
	 * it here would have put the RPM snap back on the one path a chunk reload still
	 * went through.
	 */
	public void setSimulatedRpm(float simulatedRpm) {
		this.simulatedRpm = clamp(simulatedRpm, -EngineTuning.ABSOLUTE_MAX_RPM, EngineTuning.ABSOLUTE_MAX_RPM);
	}

	public void setPublishedRpm(float publishedRpm) {
		this.publishedRpm = publishedRpm;
	}

	/**
	 * Adopts the server's answer to {@link #isActivelyGenerating()}.
	 *
	 * <p>Synchronised rather than recomputed, so no client-side approximation of
	 * the predicate can ever exist to disagree with the server's.
	 */
	public void setActivelyGenerating(boolean activelyGenerating) {
		this.activelyGenerating = activelyGenerating;
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

	public void setSparkPlugInstalled(boolean sparkPlugInstalled) {
		this.sparkPlugInstalled = sparkPlugInstalled;
	}

	public void setStructureValid(boolean structureValid) {
		this.structureValid = structureValid;
	}
	// externallyDriven has no setter on purpose: tickRotation is the one place it
	// is written, on both sides, from Create's own synchronised source pointer.

	// ------------------------------------------------------------------------

	private static float clamp(float value, float min, float max) {
		return value < min ? min : Math.min(value, max);
	}

	private static float normalizeDegrees(float degrees) {
		float wrapped = degrees % 360.0F;
		return wrapped < 0.0F ? wrapped + 360.0F : wrapped;
	}
}
