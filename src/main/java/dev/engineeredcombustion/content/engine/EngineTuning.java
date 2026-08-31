package dev.engineeredcombustion.content.engine;

/**
 * Every tunable number of the engine simulation, in one place.
 *
 * <p>These are gameplay-scaled values, not real-world engine physics. "Torque"
 * here is an abstract quantity whose only job is to make the engine behave
 * believably; dividing it by {@link #FLYWHEEL_INERTIA} yields RPM change per
 * game tick.
 *
 * <h2>Constraints imposed by Create 6.0.10</h2>
 * Two limits in {@code RotationPropagator} are hard requirements, not
 * preferences - violating either makes Create <b>destroy the block</b>:
 * <ul>
 * <li>{@code maxRotationSpeed} (config default 256 RPM, but a server may set it
 * as low as 64). {@link #MAX_RPM} stays below the default, and the speed the
 * engine actually publishes is clamped to the configured value at runtime
 * rather than trusting the default.</li>
 * <li>{@code MAX_FLICKER_SCORE = 128}. {@code KineticBlockEntity#onSpeedChanged}
 * adds 5 every time a block's speed crosses zero or reverses, and only one
 * point decays per tick. The large gap between {@link #START_RPM} and
 * {@link #STALL_RPM}, plus the publishing rules below, exist so the engine can
 * never dither around zero.</li>
 * </ul>
 */
public final class EngineTuning {

	private EngineTuning() {
	}

	// --- engine layout ------------------------------------------------------

	/**
	 * Most cylinders one inline engine may have.
	 *
	 * <p>The single place this limit lives. It bounds the assembly scan - which is
	 * therefore a fixed handful of block lookups and never a world search - and it
	 * sizes every per-cylinder array in {@link EngineState}, so nothing allocates
	 * per tick.
	 *
	 * <p>Four is the first milestone's ceiling, not a permanent one. Raising it is a
	 * change to this number and to the engine's models; nothing else in the
	 * simulation assumes a count.
	 */
	public static final int MAX_CYLINDERS = 4;

	/**
	 * Crank phase of a cylinder, in degrees, for the simplified one-power-event-per
	 * -revolution engine this mod currently simulates.
	 *
	 * <pre>
	 * phaseOffset(i) = i * 360 / cylinderCount
	 * </pre>
	 *
	 * so an inline-1 fires at 0, an inline-2 at 0 and 180, an inline-3 at 0, 120
	 * and 240, and an inline-4 at 0, 90, 180 and 270 - evenly spaced round one
	 * revolution.
	 *
	 * <p>These are <b>prototype two-stroke-like</b> crank phases. A real four-stroke
	 * engine spreads its firing over 720 degrees and needs an explicit crank
	 * configuration and a firing order, neither of which exists yet; when they do,
	 * this is the function they replace.
	 *
	 * <p>The same value drives the simulation and the renderers, which is what makes
	 * the crank throw a player can see through the crankcase window the throw the
	 * combustion actually happened on.
	 */
	public static float cylinderPhaseOffsetDegrees(int index, int cylinderCount) {
		if (cylinderCount <= 1)
			return 0.0F;
		return 360.0F * index / cylinderCount;
	}

	// --- speeds, in RPM -----------------------------------------------------

	/**
	 * Minimum forward speed at which debug combustion can begin. Deliberately
	 * below Create's Hand Crank, which generates exactly 32 RPM
	 * ({@code HandCrankBlock#getRotationSpeed}), so a hand crank can start the
	 * engine.
	 */
	public static final float START_RPM = 24.0F;

	/** Below this the engine can no longer sustain combustion. */
	public static final float STALL_RPM = 10.0F;

	/**
	 * Below this the crankshaft counts as standing still.
	 *
	 * <p>Deliberately far below {@link #STALL_RPM}, and a different question from
	 * it. Stalling is about <i>combustion</i>: below 10 RPM a charge can no longer
	 * carry the engine to the next one. Coming to rest is about <i>rotation</i>: a
	 * flywheel that has stopped firing keeps turning on its stored momentum long
	 * after it can no longer run, and the phase must not declare it stopped - and
	 * zero its speed - while it is visibly still turning. That zeroing is what used
	 * to make a spun-down engine snap the last 10 RPM to a halt.
	 */
	public static final float REST_RPM = 1.0F;

	/**
	 * Free-running equilibrium speed at <b>0 % throttle</b>.
	 *
	 * <p>0 % is a closed main throttle with an idle circuit still feeding the
	 * engine, not a shut-off - the ignition switch is what stops the engine. So
	 * the bottom of the throttle range is an idle, not a stall.
	 */
	public static final float IDLE_RPM = 64.0F;

	/** Equilibrium speed at 100 % throttle. */
	public static final float FULL_THROTTLE_RPM = 192.0F;

	/**
	 * Absolute clamp on the engine's own speed.
	 *
	 * <p>Deliberately a little above {@link #FULL_THROTTLE_RPM} so the flywheel
	 * may overshoot its target on the way up instead of being flattened against
	 * a wall - the overshoot is what makes the engine feel like it has inertia.
	 *
	 * <p>This is <i>not</i> the whole story on Create's speed limit. Create's
	 * {@code maxRotationSpeed} is a server config (default 256, minimum 64) and
	 * exceeding it makes {@code RotationPropagator} destroy the block, so the
	 * published speed is additionally clamped to whatever that config actually
	 * says at runtime - see {@code CrankshaftBlockEntity#speedLimit()}.
	 */
	public static final float MAX_RPM = 208.0F;

	/**
	 * Sanity bound on any speed loaded from disk or synchronised in.
	 *
	 * <p>Deliberately far above {@link #MAX_RPM}, because it guards a different
	 * thing. {@code MAX_RPM} is what the <i>engine</i> may drive itself to; this is
	 * only a defence against corrupt NBT. An engine may legitimately be turning far
	 * faster than it could ever drive itself - a fast external network can impose
	 * any speed Create's {@code maxRotationSpeed} allows, and that config has a
	 * minimum but no maximum - and clamping such an engine to its own limit would
	 * have reintroduced exactly the snap this milestone removed, in the one place a
	 * chunk reload could still hide it.
	 */
	public static final float ABSOLUTE_MAX_RPM = 4096.0F;

	// --- rotational dynamics ------------------------------------------------

	/**
	 * Resistance to speed change. Higher means the flywheel carries more momentum
	 * between power strokes, coasts down for longer, and - importantly - ripples
	 * less within a revolution, which is what keeps the speed published to Create
	 * stable. Lower makes the engine more responsive. 20 is the compromise: about
	 * +/-2 RPM of ripple at idle, ~4.5 s to spin up from a hand crank, ~8 s of
	 * coast-down.
	 */
	public static final float FLYWHEEL_INERTIA = 20.0F;

	/** Constant drag, present even at very low speed. */
	public static final float FRICTION_BASE_TORQUE = 4.0F;

	/** Speed-proportional drag, which is what makes the engine settle at idle. */
	public static final float FRICTION_TORQUE_PER_RPM = 0.08F;

	// --- coast-down drag ----------------------------------------------------
	//
	// A spin-down used to take about ten seconds from idle, which reads as a
	// flywheel on frictionless bearings rather than an engine. The obvious knob -
	// FLYWHEEL_INERTIA - is the wrong one: it also sets how much a single cylinder's
	// combustion ripples the crank speed within a revolution, how long the engine
	// takes to spin up from a hand crank, and how much smoother an inline-4 is than
	// an inline-1. Cutting it to shorten the coast would have paid for a shorter
	// spin-down with a rougher, twitchier running engine.
	//
	// So the inertia is untouched and the extra drag is added only where it belongs:
	// to an engine that is NOT making combustion torque. A real engine coasting with
	// the throttle shut is pumping air past a closed plate and driving its own
	// valvetrain and accessories, and that is a much bigger loss than the bearing
	// friction a running engine fights.
	//
	// Two things this deliberately does not do:
	//   - it does not touch a RUNNING or STARTING engine, so every equilibrium the
	//     throttle promises (idle 64, half 128, full 192) is exactly as it was, and a
	//     start attempt is not sabotaged between its firing kicks;
	//   - it does not double-charge. PASSIVE_DRAG_STRESS_PER_RPM bills a kinetic
	//     network for motoring a dead engine; this slows an engine nothing is
	//     driving. The two never apply to the same rotation, because an engine held
	//     at a speed by Create takes that speed on rather than integrating its own.

	/**
	 * How much harder friction bites on an engine that is not firing, as a multiple
	 * of the running figure.
	 *
	 * <p>Applied on top of the lubrication multiplier rather than instead of it, so
	 * a dry engine still coasts down faster than a well-oiled one.
	 */
	public static final float COAST_FRICTION_MULTIPLIER = 2.5F;

	/**
	 * Speed-independent drag of an engine being turned over without firing: pumping
	 * against a closed throttle, and everything the crank drives that is not the
	 * load.
	 *
	 * <p>Constant on purpose. It is what makes the last few RPM of a spin-down
	 * actually finish instead of asymptotically creeping, which is the half of the
	 * old coast-down that felt worst.
	 */
	public static final float PUMPING_DRAG_TORQUE = 6.0F;

	/**
	 * Width of the band over which combustion torque fades out, centred on the
	 * <i>throttle's target speed</i>. Full torque at {@code target - 16}, none at
	 * {@code target + 16}. Without it the engine would approach its target
	 * asymptotically and take absurdly long to spin up; with it the engine pulls
	 * hard towards the target and then settles.
	 */
	public static final float GOVERNOR_RANGE_RPM = 32.0F;

	/**
	 * Extra drag torque at exactly 100 % of the kinetic network's stress
	 * capacity, scaling linearly from nothing at no load.
	 *
	 * <p>This is what makes a loaded engine sag rather than either ignoring load
	 * completely or falling off a cliff at the overstress threshold. Kept small
	 * on purpose: average combustion torque at idle is only about 9, so at full
	 * load an idling engine settles near 46 RPM while a wide-open one barely
	 * moves off 190 - exactly the "open the throttle to pull the load" response
	 * a real engine has.
	 *
	 * <p>Create's own overstress rule is untouched and still applies on top: past
	 * capacity Create reports speed 0, the crank stops, and the engine stalls.
	 */
	public static final float LOAD_DRAG_TORQUE = 2.5F;

	// --- combustion ---------------------------------------------------------

	/**
	 * Crank angle of the single power event per revolution.
	 *
	 * <p>{@link CrankMath#pistonPosition} gives 0 (bottom dead centre) at 0
	 * degrees and 1 (top dead centre) at 180, so firing at 180 means combustion
	 * starts with the piston at the top and pushes it back down over the
	 * following 180 degrees - the expansion stroke of a two-stroke engine.
	 */
	public static final float FIRING_ANGLE_DEGREES = 180.0F;

	/** How far past the firing angle combustion keeps pushing (TDC -> BDC). */
	public static final float POWER_STROKE_DEGREES = 180.0F;

	/** Fraction of each revolution during which combustion torque is applied. */
	public static final float POWER_STROKE_DUTY = POWER_STROKE_DEGREES / 360.0F;

	/**
	 * How long the visible flash inside the combustion chamber lasts.
	 *
	 * <p>Three ticks - 0.15 s. Short enough that even at 192 RPM (3.2 firings a
	 * second, so one every ~6 ticks) two flashes never overlap, which is what
	 * keeps the effect reading as discrete bangs instead of a permanent glow.
	 *
	 * <p>This is the knob to reach for if a fast engine ever looks like it is
	 * simply lit rather than firing: shortening it changes nothing about when a
	 * combustion happens, because the flash is started by the authoritative
	 * combustion counter and this only says how long it lingers.
	 */
	public static final int COMBUSTION_FLASH_TICKS = 3;

	/**
	 * Peak combustion torque at 0 % throttle, derived rather than hand-tuned.
	 *
	 * <p>See {@link #peakCombustionTorqueFor(float)}: this is that solution
	 * evaluated at {@link #IDLE_RPM}, i.e. exactly the value the engine used
	 * before throttle existed. Kept as a named constant because it is the figure
	 * every other torque in the model is worth comparing against.
	 */
	public static final float PEAK_COMBUSTION_TORQUE = peakCombustionTorqueFor(IDLE_RPM);

	/**
	 * Peak magnitude of one cylinder's compression torque.
	 *
	 * <p>The gas in a cylinder is a spring. Between bottom and top dead centre the
	 * piston works against it and the crank is held back; past top dead centre the
	 * same gas pushes the piston down again and hands the energy back. So this
	 * torque integrates to <b>exactly zero</b> over a revolution - see
	 * {@link #compressionTorqueAt(float)} - and it therefore changes no equilibrium
	 * speed and costs the engine no fuel. What it changes is the <i>shape</i> of
	 * the rotation.
	 *
	 * <p>That shape is the whole reason it exists. On an inline-1 it is a single
	 * lump per revolution, felt as the engine labouring up to compression and
	 * being flicked over it, and it is what makes cranking one by hand feel like
	 * cranking an engine. On an inline-4 the four lumps sit 90 degrees apart and
	 * very nearly cancel, so a four-cylinder engine turns visibly and audibly
	 * smoother than a single - without one line of code anywhere saying "more
	 * cylinders are smoother".
	 *
	 * <p>Deliberately modest against combustion (about 36 at idle) and friction
	 * (about 9): enough to feel, never enough to stall a running engine.
	 */
	public static final float COMPRESSION_PEAK_TORQUE = 6.0F;

	// --- throttle -----------------------------------------------------------

	/** Throttle is a whole percentage, 0-100, so it can ride Create's integer value UI. */
	public static final int THROTTLE_MIN_PERCENT = 0;
	public static final int THROTTLE_MAX_PERCENT = 100;
	public static final int THROTTLE_DEFAULT_PERCENT = 0;

	/**
	 * Throttle lever angles in degrees about the carburetor's throttle shaft.
	 * Closed points the arm down and forward, open swings it up.
	 */
	public static final float THROTTLE_LEVER_CLOSED_DEGREES = 40.0F;
	public static final float THROTTLE_LEVER_OPEN_DEGREES = -20.0F;

	/** Discrete steps the float bowl's visible fuel level is drawn in. */
	public static final int FUEL_LEVEL_RENDER_STEPS = 16;

	/**
	 * Minimum ticks between two Carburetor tank syncs while only the amount is
	 * changing.
	 *
	 * <p>Fuel is drawn once per combustion event, which at full throttle is
	 * about 3.2 times a second. Syncing the block entity on every millibucket
	 * would put that on the wire for no visible benefit, because the rendered
	 * level only moves in {@link #FUEL_LEVEL_RENDER_STEPS} steps anyway. A step
	 * change, or the tank emptying or filling from empty, still syncs at once.
	 */
	public static final int TANK_SYNC_INTERVAL_TICKS = 10;

	// --- publishing to Create's kinetic network -----------------------------
	//
	// Two jobs, and they used to be done by one number that could not do both.
	//
	// Create re-propagates a whole kinetic network every time a source changes
	// its generated speed, so the engine must not push its within-revolution
	// combustion ripple onto the network. The old answer was a plain deadband:
	// publish only once the engine's speed had moved 8 RPM from the published
	// value. That silenced the ripple - and made a permanent steady-state error
	// unavoidable, because any error smaller than the deadband could never be
	// corrected. A world reload restored one of those parked values from NBT and
	// the engine then ran for ever at a speed Create had simply been left holding.
	//
	// The ripple is now removed where it belongs - by *filtering* the output, see
	// {@link #OUTPUT_FILTER_ALPHA} - which leaves the publishing rule free to be
	// what it should always have been: a rate limit, not a dead zone. Every error
	// above {@link #NETWORK_RPM_FINE_DELTA} is eventually published; how quickly
	// depends only on how big it is.

	/**
	 * Generated speed is rounded to this step before Create ever sees it.
	 *
	 * <p>Halved from 4 now that the published value is a filtered one. The step is
	 * what keeps the published number tidy and bounds how many distinct values the
	 * network can ever be given; it no longer has to double as the engine's noise
	 * floor, and at 2 RPM the engine's idle, half and full-throttle equilibria all
	 * land exactly on their targets (64, 128, 192) instead of up to 4 RPM off.
	 */
	public static final float NETWORK_RPM_QUANTUM = 2.0F;

	/**
	 * A difference this large between the engine's filtered output and the value
	 * Create is holding is published as soon as the minimum interval allows.
	 *
	 * <p>This is the throttle-change, load-change and source-handoff path: those
	 * move the engine by tens of RPM, and the network has to follow promptly.
	 */
	public static final float NETWORK_RPM_MAJOR_DELTA = 6.0F;

	/**
	 * The smallest error worth correcting at all, published once
	 * {@link #NETWORK_RECONCILE_INTERVAL_TICKS} have passed.
	 *
	 * <p><b>This is what guarantees convergence.</b> Anything at or above it is
	 * published within a second; below it the published value is already within
	 * one quantum of the truth and moving it would be churn for no visible
	 * difference.
	 *
	 * <p>Deliberately larger than half a quantum. That difference - here 0.5 RPM
	 * either side of every step boundary - is the hysteresis that stops an engine
	 * sitting exactly on a boundary from flipping between two adjacent steps once
	 * a second. It only has to exceed the ripple that survives the output filter,
	 * which is about +/-0.2 RPM.
	 */
	public static final float NETWORK_RPM_FINE_DELTA = 1.5F;

	/** Minimum ticks between two non-zero generated-speed updates. */
	public static final int NETWORK_MIN_UPDATE_INTERVAL_TICKS = 4;

	/**
	 * How long a small error is allowed to stand before it is published anyway.
	 *
	 * <p>One second. Fast enough that no player will ever catch the engine
	 * disagreeing with its own readout, slow enough that a slowly drifting engine
	 * re-propagates its network at most once a second.
	 */
	public static final int NETWORK_RECONCILE_INTERVAL_TICKS = 20;

	// --- generated-output filter --------------------------------------------

	/**
	 * Smoothing factor of the low-pass filter between the engine's instantaneous
	 * angular velocity and the speed Create is told it generates, per tick.
	 *
	 * <p>A single-cylinder engine fires once per revolution, so its speed genuinely
	 * oscillates - about +/-2 RPM at {@link #FLYWHEEL_INERTIA} = 20. That ripple is
	 * real physics and the piston, the crank and the sound must keep it; what must
	 * not have it is the kinetic network, because every change there costs a full
	 * re-propagation.
	 *
	 * <p>1/32 gives a time constant of 32 ticks - 1.6 s - which attenuates the idle
	 * ripple (a period of 18.75 ticks) by about 90 % and everything faster by more.
	 * What survives is roughly +/-0.2 RPM, comfortably inside the hysteresis band
	 * described at {@link #NETWORK_RPM_FINE_DELTA}.
	 *
	 * <p>This is the mod's only output filter. The instantaneous speed is
	 * untouched: {@code EngineState#getSimulatedRpm} is still the engine's honest
	 * angular velocity and still what the crank angle and combustion timing run on.
	 */
	public static final float OUTPUT_FILTER_ALPHA = 1.0F / 32.0F;

	/**
	 * A step larger than this is adopted by the filter at once instead of being
	 * faded in.
	 *
	 * <p>Filtering is for ripple, not for events. Catching, stalling, a throttle
	 * swung open, a load dropped, a source handoff and the post-load reconciliation
	 * are all real discontinuities, and lagging 1.6 s behind them would be a bug of
	 * its own. Comfortably above the ripple this filter exists to remove.
	 */
	public static final float OUTPUT_FILTER_SNAP_RPM = 12.0F;

	/**
	 * How long the post-load reconciliation waits for the rest of the engine's
	 * blocks to load before going ahead with whatever it can see.
	 *
	 * <p>An engine may straddle a chunk boundary, and its Cylinder or Flywheel can
	 * legitimately be a tick or two behind the Crankshaft on a world load. Declaring
	 * such an engine broken - and tearing down its kinetic network - because a
	 * neighbour was late is exactly the failure this budget avoids. It is a budget
	 * rather than an open-ended wait so that an engine whose neighbour never loads
	 * still reconciles, just conservatively.
	 */
	public static final int POST_LOAD_RECONCILE_WAIT_TICKS = 100;

	// --- fuel ---------------------------------------------------------------

	/**
	 * Gasoline drawn per combustion event, in millibuckets. Charged per <i>firing
	 * event</i>, never per tick, so consumption scales with engine speed exactly
	 * like a real engine: one revolution, one charge.
	 *
	 * <p>That is also the whole of the throttle's effect on fuel use, and it is
	 * deliberately the whole of it - there is no separate throttle term. A faster
	 * engine simply completes more revolutions per second, so it fires more often
	 * and burns proportionally more:
	 *
	 * <pre>
	 *  64 RPM (idle)          1.07 firings/s   1000 mB lasts ~15.6 min
	 * 192 RPM (full throttle) 3.20 firings/s   1000 mB lasts ~5.2 min
	 * </pre>
	 *
	 * <p>Pre-start firing attempts are charged the same amount - a real engine
	 * burns fuel while you crank it too.
	 */
	public static final int FUEL_PER_COMBUSTION_MB = 1;

	/** Carburetor tank size, in millibuckets. */
	public static final int CARBURETOR_CAPACITY_MB = 1000;

	// --- lubrication --------------------------------------------------------

	/** Oil sump tank size, in millibuckets. */
	public static final int OIL_CAPACITY_MB = 1000;

	/** Below this the engine reports LOW and friction starts to bite. */
	public static final int LOW_OIL_THRESHOLD_MB = 100;

	/**
	 * Friction multipliers per lubrication state.
	 *
	 * <p>They multiply the existing friction torque rather than introducing a
	 * second slowdown, so the consequence emerges from the same equilibrium the
	 * engine already solves: combustion torque equals friction torque. Against the
	 * tuned combustion torque that puts a low engine at about 57 RPM and a dry one
	 * at about 26 - both still running, neither with anything in reserve.
	 *
	 * <p>Nothing here damages the engine. Wear and seizure are a later milestone.
	 */
	public static final float FRICTION_MULTIPLIER_NORMAL = 1.0F;
	public static final float FRICTION_MULTIPLIER_LOW = 1.5F;
	public static final float FRICTION_MULTIPLIER_DRY = 3.0F;

	/**
	 * Running combustion events per millibucket of oil drawn.
	 *
	 * <p>A gameplay abstraction, not a model of real oil consumption. At idle this
	 * is roughly one millibucket a minute, so a full sump is observable within a
	 * minute of running and lasts long enough that refilling is not a chore.
	 * Starting attempts are excluded: only an engine actually running counts.
	 */
	public static final int COMBUSTION_EVENTS_PER_OIL_MB = 64;

	/** Oil drawn each time that count is reached. */
	public static final int OIL_PER_CONSUMPTION_MB = 1;

	// --- starting -----------------------------------------------------------

	/**
	 * A start attempt needs this many successful firing opportunities before the
	 * engine catches, chosen once per attempt in {@code [MIN, MAX]}. This is what
	 * turns starting into "crank... puff... puff... BRUMM" instead of the engine
	 * snapping to RUNNING the instant it crosses START_RPM.
	 *
	 * <p>The count is rolled once when an attempt begins and then held; it is
	 * never re-rolled per tick or per revolution.
	 */
	public static final int MIN_START_CYCLES = 2;
	public static final int MAX_START_CYCLES = 5;

	/**
	 * How much more cranking a start attempt asks for per cylinder beyond the
	 * first, as a fraction of the rolled count.
	 *
	 * <p>Start progress counts <i>engine-wide firing events</i>, and an inline-4
	 * produces four of those per revolution against a single's one. Left alone,
	 * that would make a four-cylinder engine catch in a quarter of the revolutions
	 * - near enough instantly, which is the one thing starting must not become.
	 *
	 * <p>At 0.5, an inline-4 needs 2.5 times the events but gets them four times as
	 * often, so it catches in about 60 % of the revolutions an inline-1 does.
	 * Noticeably easier and smoother, which is true of real multi-cylinder engines,
	 * and still several seconds of cranking.
	 */
	public static final float START_CYCLES_PER_EXTRA_CYLINDER = 0.5F;

	/**
	 * Firing events a start attempt needs, for a rolled base count and a cylinder
	 * count, on an engine whose cylinders are all healthy. Always at least one.
	 */
	public static int requiredStartCycles(int rolledCycles, int cylinderCount) {
		return requiredStartCycles(rolledCycles, cylinderCount, 1.0F);
	}

	/**
	 * The same, for an engine that has lost some of its compression.
	 *
	 * <p>Most of what makes a worn engine hard to start is already paid for
	 * elsewhere: every firing kick is multiplied by that cylinder's compression, so
	 * a tired engine is genuinely being flicked over more weakly. This is the
	 * remainder - a worn engine also has to catch on <i>more</i> of those weaker
	 * kicks, because each one is less likely to carry it to the next.
	 *
	 * <p>Scaled by how much compression is actually gone, measured against the
	 * worst the model allows, so it is continuous rather than a threshold: a
	 * lightly used engine asks for nothing extra, and one at the service limit for
	 * {@link #START_CYCLES_WEAR_PENALTY} more events. Even that engine still
	 * starts - it just takes noticeably longer, which is exactly what a tired
	 * engine does.
	 *
	 * @param averageCompressionEfficiency mean compression over the engine's
	 *                                     cylinders, {@code [MIN, 1]}
	 */
	public static int requiredStartCycles(int rolledCycles, int cylinderCount,
		float averageCompressionEfficiency) {
		float scale = 1.0F + START_CYCLES_PER_EXTRA_CYLINDER * Math.max(0, cylinderCount - 1);
		float lostCompression = clamp01((1.0F - averageCompressionEfficiency)
			/ (1.0F - MIN_COMPRESSION_EFFICIENCY));
		int penalty = Math.round(START_CYCLES_WEAR_PENALTY * lostCompression);
		return Math.max(1, Math.round(rolledCycles * scale) + penalty);
	}

	/**
	 * Fraction of normal combustion torque delivered by a pre-start firing kick.
	 * Enough to feel the engine trying to catch, far too little to run on.
	 */
	public static final float START_KICK_TORQUE_FACTOR = 0.35F;

	/**
	 * A start attempt is abandoned after this many ticks without a usable firing
	 * opportunity - the engine stopped turning, ran dry, or ignition went away.
	 * Stops a half-finished start from being remembered indefinitely.
	 */
	public static final int START_ATTEMPT_TIMEOUT_TICKS = 30;

	// --- active generation --------------------------------------------------

	/**
	 * How many revolutions the engine may go without a combustion event before it
	 * stops counting as actively generating.
	 *
	 * <p>Measured in revolutions rather than ticks because the firing interval is
	 * a property of speed: one revolution is 18.75 ticks at idle and 6.25 at full
	 * throttle, so a fixed tick budget would be far too tight at the bottom of the
	 * range and far too slack at the top.
	 *
	 * <p>2.5 tolerates a single missed firing - a momentary fuel hiccup, or a
	 * revolution that crossed the firing angle on a tick boundary - without ever
	 * tolerating an engine that has genuinely stopped burning.
	 */
	public static final float GENERATION_COMBUSTION_REVOLUTIONS = 2.5F;

	/**
	 * Hard ceiling on that allowance, in ticks. A crawling engine must not be able
	 * to claim it is generating for minutes on the strength of one old firing.
	 */
	public static final int GENERATION_COMBUSTION_LIMIT_TICKS = 60;

	// --- stress -------------------------------------------------------------

	/** Capacity per RPM, Create's convention. 32 * 64 RPM = 2048 SU at idle. */
	public static final double STRESS_CAPACITY_PER_RPM = 32.0D;

	/**
	 * Parasitic load an engine imposes while it is <i>not</i> generating, per RPM.
	 *
	 * <p>Compression, bearing friction and pumping losses: turning a dead engine
	 * over costs real work, and this is what that costs the network doing the
	 * turning. Applied only while the engine is not actively generating - a running
	 * engine fights its own friction inside the simulation and must not also be
	 * billed for it on the network it is feeding.
	 *
	 * <p>1/32nd of the capacity a running engine provides, so motoring a dead
	 * engine is felt without being punitive: at 64 RPM one dead engine costs 64 SU
	 * against the 2048 SU a single running engine supplies. Ten of them cost 640 SU
	 * - noticeable, still affordable, and exactly the discouragement a wall of
	 * unfuelled engines should get.
	 */
	public static final double PASSIVE_DRAG_STRESS_PER_RPM = 1.0D;

	// --- sound --------------------------------------------------------------
	//
	// The engine's audio is deliberately in two layers, because the machine makes
	// two different kinds of noise and they follow two different clocks:
	//
	//   MECHANICAL  a continuous loop - crankshaft, bearings, flywheel, the piston
	//               pumping against compression. It follows MECHANICAL RPM and
	//               plays whenever the crank turns, including while the engine is
	//               being cranked, coasting, or motored by another Create source.
	//               It contains no combustion whatsoever.
	//
	//   COMBUSTION  one short positional pulse per charge that actually burned,
	//               fired from the authoritative combustion counter. This is what
	//               carries the engine's rhythm, and it is the reason nothing here
	//               tries to fake a firing rate by pitching a loop up.
	//
	// A single-cylinder engine fires once per revolution: 1.07 times a second at
	// idle, 3.2 at full throttle. At those rates the ear resolves every event, so
	// the correct sound is a train of distinct pulses over a mechanical bed - not
	// a smooth engine loop.

	/**
	 * Speed at which {@code engine_mechanical.ogg} plays back unshifted.
	 *
	 * <p>The asset was synthesised at the engine's idle character, so idle is by
	 * definition pitch 1.0 and the mapping only has to describe the deviation.
	 */
	public static final float SOUND_REFERENCE_RPM = IDLE_RPM;


	/**
	 * How strongly speed is allowed to bend pitch, as an exponent on the speed
	 * ratio. Well below 1 on purpose: Create's RPM values are gameplay numbers,
	 * not crankshaft RPM, so mapping them proportionally would take the engine
	 * from a murmur to a chipmunk across its normal range.
	 *
	 * <p>Retuned for the throttle range. The old 0.35 was picked when the engine
	 * only ever ran at 64 RPM, and it saturates against
	 * {@link #SOUND_MAX_PITCH} at about 110 RPM - so with a throttle fitted, the
	 * top two thirds of the range would all have sounded identical. At 0.20 the
	 * idle-to-full sweep (64 to 192 RPM, a ratio of 3) spans 1.00 to 1.25, which
	 * is audibly a throttle opening and still nowhere near comical.
	 */
	public static final float SOUND_PITCH_EXPONENT = 0.20F;

	public static final float SOUND_MIN_PITCH = 0.80F;
	public static final float SOUND_MAX_PITCH = 1.28F;

	/**
	 * Volume a loop is created at, before it fades up to its nominal level.
	 *
	 * <p>Must be greater than zero. {@code SoundEngine#play} discards any instance
	 * whose volume is zero at the moment it is handed over ("Skipped playing sound,
	 * volume was zero") and a discarded instance is never ticked, so a loop that
	 * starts silent never becomes audible - it is not a fade-in, it is a deletion.
	 * Create's own looping instances start at 0.01 and 0.05 for the same reason.
	 */
	public static final float SOUND_INITIAL_VOLUME = 0.05F;

	/** Volumes are Minecraft attenuation units; blocks fall off over roughly 16 * volume blocks. */

	/**
	 * The mechanical layer while the engine is being turned over and not firing -
	 * cranking, or motored by another Create source. This is the whole of what such
	 * an engine sounds like, so it carries the sound.
	 */
	public static final float SOUND_MECHANICAL_CRANKING_VOLUME = 0.42F;

	/**
	 * The mechanical layer underneath a running engine. Quieter, because here it is
	 * a <i>bed</i>: the combustion pulses are the engine's voice and the mechanical
	 * layer must not compete with them or the rhythm turns to mush.
	 */
	public static final float SOUND_MECHANICAL_RUNNING_VOLUME = 0.26F;

	/**
	 * The mechanical layer of a flywheel spinning down with no combustion in it.
	 * Between the other two: louder than the running bed - nothing is masking it
	 * now - and quieter than cranking, because nothing is driving it either.
	 *
	 * <p>Hearing this alone, with the pulses gone but the engine still turning, is
	 * the whole point of splitting the layers.
	 */
	public static final float SOUND_MECHANICAL_COASTING_VOLUME = 0.34F;

	/** One charge burning in a running engine. The engine's voice. */
	public static final float SOUND_COMBUSTION_VOLUME = 0.55F;

	/**
	 * The same charge burning in an engine that has not caught yet. Duller and
	 * quieter: it is the same event, in a cylinder that is barely turning and has
	 * no momentum behind it.
	 */
	public static final float SOUND_COMBUSTION_STARTING_VOLUME = 0.40F;

	/**
	 * Pitch of a combustion pulse at {@link #SOUND_REFERENCE_RPM}, and how far
	 * speed is allowed to move it.
	 *
	 * <p>Deliberately shallow, and deliberately <i>not</i> how the firing rate is
	 * communicated: the rate comes from the events themselves. This only makes a
	 * hard-working engine sound a little tighter than an idling one.
	 */
	public static final float SOUND_COMBUSTION_PITCH_RANGE = 0.10F;

	/**
	 * Random spread applied to every pulse, so a steady engine does not sound like
	 * one sample on a metronome. Kept small - this is a real engine's
	 * cycle-to-cycle variation, not a random pitch generator.
	 */
	public static final float SOUND_COMBUSTION_PITCH_JITTER = 0.045F;
	public static final float SOUND_COMBUSTION_VOLUME_JITTER = 0.10F;

	/**
	 * Firing rate, in events per second, above which individual pulses stop being
	 * played one for one.
	 *
	 * <p>The current engine cannot reach this: one cylinder firing once per
	 * revolution tops out at 3.2 Hz at {@link #FULL_THROTTLE_RPM}. It exists so
	 * that the audio scheduler does not <i>have</i> to be rewritten when a faster
	 * engine, a four-stroke, or a second cylinder arrives - see
	 * {@code EngineCombustionAudio}, which decimates pulses and fades in a
	 * continuous combustion layer above this rate instead of machine-gunning
	 * one-shots.
	 *
	 * <p>12 Hz is roughly where a human ear stops resolving separate impacts and
	 * starts hearing a pitch, which is exactly where discrete pulses stop being the
	 * right representation.
	 */
	public static final float SOUND_COMBUSTION_PULSE_MAX_RATE_HZ = 12.0F;

	/** Firing rate at which the continuous combustion layer reaches full volume. */
	public static final float SOUND_COMBUSTION_BLEND_FULL_RATE_HZ = 24.0F;

	/** Volume of that continuous layer once it has fully faded in. */
	public static final float SOUND_COMBUSTION_LOOP_VOLUME = 0.45F;

	/**
	 * The ignition tick. Quiet on purpose - it is a coil discharging, not an
	 * event, and it has to stay well under the pulse that may follow it or the
	 * two stop being distinguishable.
	 */
	public static final float SOUND_SPARK_VOLUME = 0.16F;
	public static final float SOUND_START_VOLUME = 0.70F;
	public static final float SOUND_STALL_VOLUME = 0.60F;
	public static final float SOUND_STOP_VOLUME = 0.50F;

	/** Per-tick volume ramp of the loops, so they never click in or cut out. */
	public static final float SOUND_FADE_PER_TICK = 0.08F;

	/**
	 * Ticks a loop survives without being refreshed before it fades itself out.
	 *
	 * <p>This is the whole orphan-prevention mechanism: the block entity refreshes
	 * its loop every client tick, so a broken, unloaded or replaced engine simply
	 * stops refreshing and the sound retires on its own. Nothing has to notice the
	 * block is gone and explicitly kill the audio.
	 */
	public static final int SOUND_KEEP_ALIVE_TICKS = 3;

	/** Below this the engine is treated as not turning at all, audibly. */
	public static final float SOUND_MIN_AUDIBLE_RPM = 1.0F;

	/**
	 * Depth of the pitch wobble a dry engine gets, as a fraction of its pitch.
	 *
	 * <p>Purely cosmetic roughness. It is deliberately tiny and derived from the
	 * game time rather than from any random source, so it cannot desynchronise
	 * between players or accumulate error; the HUD remains the authoritative
	 * warning about lubrication and this only reinforces it.
	 */
	public static final float SOUND_DRY_ROUGHNESS = 0.04F;

	/** Wobble rate of that roughness, in radians per tick. */
	public static final float SOUND_DRY_ROUGHNESS_RATE = 0.9F;

	// --- wear: condition bands ----------------------------------------------
	//
	// Wear is stored as a float in [0, 1] - 0 is a part fresh out of the crate, 1
	// is a part at its service limit - and shown to the player as one of the six
	// bands in WearCondition. These are the boundaries, and they are the ONLY
	// place any of them is written down: nothing in the UI, the simulation or the
	// diagnostics compares wear against a literal.
	//
	// Each constant is the lowest wear that still reads as that band, so the bands
	// are [0, .10) [.10, .30) [.30, .50) [.50, .70) [.70, .90) [.90, 1].

	public static final float CONDITION_GOOD_WEAR = 0.10F;
	public static final float CONDITION_USED_WEAR = 0.30F;
	public static final float CONDITION_WORN_WEAR = 0.50F;
	public static final float CONDITION_POOR_WEAR = 0.70F;
	public static final float CONDITION_CRITICAL_WEAR = 0.90F;

	// --- wear: what wear does to the machine --------------------------------

	/**
	 * Compression lost per unit of piston wear, linear and quadratic terms.
	 *
	 * <p>Together they define {@link EngineWearMath#compressionEfficiency(float)}:
	 *
	 * <pre>
	 * efficiency = 1 - LINEAR * wear - QUADRATIC * wear^2
	 * </pre>
	 *
	 * <p>Overwhelmingly quadratic, and that is the whole design. A worn engine has
	 * to be clearly worse without a <i>healthy</i> engine ever feeling second-hand,
	 * and the trap to avoid is reading 10 % wear as 10 % power loss. A quadratic
	 * curve spends almost none of its budget near zero and all of it near the
	 * limit, which is both what a real engine does and what the milestone asks for:
	 *
	 * <pre>
	 * wear  0.00  1.000   PRISTINE   full compression
	 * wear  0.10  0.992   GOOD       nothing a player can feel
	 * wear  0.30  0.958   USED       a small loss
	 * wear  0.50  0.900   WORN       clearly noticeable
	 * wear  0.70  0.818   POOR       large
	 * wear  0.90  0.712   CRITICAL   severe
	 * wear  1.00  0.650              the floor, reached exactly
	 * </pre>
	 *
	 * <p>The linear term is small but non-zero on purpose: it keeps the curve
	 * strictly decreasing at the origin, so a test that samples monotonicity does
	 * not have to special-case a flat spot, and so the very first wear an engine
	 * takes is worth something rather than being swallowed by float precision.
	 */
	public static final float COMPRESSION_LOSS_LINEAR = 0.05F;
	public static final float COMPRESSION_LOSS_QUADRATIC = 0.30F;

	/**
	 * Compression a cylinder keeps at the service limit, and the floor the curve
	 * above is clamped to.
	 *
	 * <p>Deliberately well above zero. A critically worn cylinder is a bad
	 * cylinder, not a dead one: it still fires, still consumes its charge, still
	 * counts as an active cylinder, and still contributes - it simply contributes
	 * two thirds of what it should. Wear must not be able to kill an engine in
	 * this milestone, and this constant is where that promise is kept.
	 */
	public static final float MIN_COMPRESSION_EFFICIENCY = 0.65F;

	/**
	 * Extra internal friction from worn bearings, linear and quadratic terms, as a
	 * fraction of the healthy figure.
	 *
	 * <pre>
	 * multiplier = 1 + LINEAR * wear + QUADRATIC * wear^2
	 * </pre>
	 *
	 * <p>The same shape as the compression curve above, for the same reason and to
	 * the same schedule - a pristine engine multiplies its friction by 1.0 and one
	 * at the service limit by 1.8, but almost all of that arrives in the last third:
	 *
	 * <pre>
	 * wear  0.10  1.017   GOOD       imperceptible
	 * wear  0.30  1.093   USED       a small loss
	 * wear  0.50  1.225   WORN       clearly noticeable
	 * wear  0.70  1.413   POOR       large
	 * wear  0.90  1.657   CRITICAL   severe
	 * wear  1.00  1.800              the service limit
	 * </pre>
	 *
	 * <p>The endpoint is unchanged from this system's first cut, deliberately: the
	 * behaviour of a <i>critically</i> worn engine - that it fights its own
	 * friction hard enough to need throttle to idle - is a physical result worth
	 * keeping, and moving the endpoint would have quietly deleted it. What changed
	 * is only how much of that penalty a <i>healthy</i> engine pays, which is now
	 * almost none.
	 *
	 * <p>Applied to the friction torque the engine already solves its equilibrium
	 * against, so every consequence emerges rather than being written down: a worn
	 * engine loses reserve torque, sags further under load, coasts down sooner, and
	 * needs more combustion events - and therefore more fuel - to hold the same
	 * speed. Nothing anywhere subtracts RPM.
	 */
	public static final float BEARING_FRICTION_LINEAR = 0.10F;
	public static final float BEARING_FRICTION_QUADRATIC = 0.70F;

	/**
	 * Total extra friction at the service limit - the sum of the two terms above,
	 * kept as a name because it is the number the design is stated in.
	 */
	public static final float MAX_EXTRA_BEARING_FRICTION =
		BEARING_FRICTION_LINEAR + BEARING_FRICTION_QUADRATIC;

	// --- wear: how fast it accumulates --------------------------------------
	//
	// Everything here is per REVOLUTION, never per tick. That is the whole reason
	// the rates are honest: an engine wears because it turned, so a server running
	// at 15 TPS wears its engines at the same rate per revolution as one at 20,
	// and a fast engine wears faster than a slow one without a single line saying
	// so. It also means an externally motored engine wears its bearings exactly as
	// a running one does at the same speed, which is the physically true answer.
	//
	// THE ANCHOR, and the whole point of the 13.1 rebalance. A properly lubricated,
	// filtered and normally operated engine experiences NEAR-NEGLIGIBLE wear of its
	// major internal parts. The calibration point is full throttle (192 RPM) under
	// half load, with normal oil and an Air Filter fitted:
	//
	//     100 hours of continuous running -> about 0.035 bearing wear
	//     250 hours of continuous running -> about 0.089 bearing wear
	//
	// Both still inside PRISTINE. The service limit is over two thousand hours
	// away, which is the number that matters: it is not a lifetime a player will
	// ever reach by playing, and it is not supposed to be. Crankshafts and Piston
	// Assemblies are not consumables. They are replaced because an engine was
	// abused - run dry, oversped, or left unfiltered for a very long time - and
	// essentially never because time passed.
	//
	// Everything harmful is therefore expressed as a MULTIPLE of these rates, and
	// the multiples are large. A rate that a healthy engine takes two thousand
	// hours to accumulate is only dangerous when something multiplies it by a
	// thousand, so the abuse multipliers below are deliberately far bigger than
	// the ones the first cut of this system used.

	/**
	 * Bearing wear a healthy engine accumulates per revolution of the crankshaft.
	 *
	 * <p>1.75e-8 is 57 million revolutions to the service limit before any
	 * multiplier - a hundred and fourteen times slower than this system's first
	 * cut, which is the order-of-magnitude correction the 13.1 rebalance is. At
	 * full throttle under half load that is about 2,000 hours of continuous
	 * running, so the honest summary of a well-kept engine's bearing life is
	 * "longer than the world will exist", not a number of evenings.
	 *
	 * <p>Solved from the calibration point rather than picked: 250 hours at 192
	 * RPM is 2.88 million revolutions, the speed and load factors there multiply
	 * by about 1.76, and 0.089 wear over that is 1.75e-8 per revolution.
	 */
	public static final float BASE_BEARING_WEAR_PER_REVOLUTION = 1.75E-8F;

	/**
	 * Piston and ring wear from the piston simply moving in its bore, per
	 * revolution.
	 *
	 * <p>Charged to every cylinder that has a Piston Assembly in it, firing or
	 * not, because a piston being pushed up and down a bore wears whether or not
	 * anything is burning above it. This is the wear an externally motored engine
	 * gets - see {@link #CYLINDER_WEAR_PER_COMBUSTION} for the half it does not.
	 */
	public static final float BASE_CYLINDER_WEAR_PER_REVOLUTION = 4.5E-9F;

	/**
	 * Piston and ring wear from one charge actually burning in that cylinder.
	 *
	 * <p>Charged per combustion event and nowhere else, so an engine that is not
	 * burning anything cannot accumulate it however fast it is being spun. That is
	 * the same rule the Stress Capacity follows, for the same reason: being turned
	 * is not running.
	 *
	 * <p>Twice the motion figure, so about two thirds of a running cylinder's wear
	 * comes from combustion and a third from motion. A firing cylinder therefore
	 * wears at 1.35e-8 per revolution against the bearings' 1.75e-8 - slightly
	 * slower, which leaves the bearings as the pacing item on a well-kept engine
	 * and the cylinders as the pacing item on an unfiltered one.
	 */
	public static final float CYLINDER_WEAR_PER_COMBUSTION = 9.0E-9F;

	/**
	 * Wear multipliers per lubrication state, applied to <b>both</b> bearing and
	 * cylinder wear.
	 *
	 * <p>Separate from {@link #FRICTION_MULTIPLIER_NORMAL} and its neighbours, and
	 * deliberately much larger. Those say how much harder an unlubricated engine
	 * is to turn; these say how much of itself it destroys doing it, and the two
	 * are not the same physics. Low oil is a bad idea; running dry is ruinous.
	 *
	 * <h2>NORMAL means safe</h2>
	 * 1.0 is not a placeholder waiting for an oil-condition system to make it
	 * interesting. It is the statement that <b>adequate serviceable lubrication is
	 * safe</b>: an engine with oil in it and a filter on it wears at the base rate
	 * above, and the base rate above is negligible. A later milestone may split oil
	 * <i>level</i> from oil <i>condition</i> and put a multiplier between 1 and
	 * {@link #WEAR_MULTIPLIER_OIL_LOW} for tired oil; nothing here forecloses that,
	 * and nothing here promises oil can never age.
	 *
	 * <h2>Why these are so much larger than they were</h2>
	 * The healthy baseline dropped by a factor of a hundred and fourteen, so a
	 * multiplier that used to be frightening is now nothing. These are sized
	 * against the <i>result</i>, not against the old numbers:
	 * <ul>
	 * <li><b>LOW, 18x.</b> Clearly harmful and clearly not fatal. A few seconds of
	 * it is invisible; a hundred hours of it takes a well-kept engine's bearings
	 * from PRISTINE to WORN, which is the point at which "top the oil up" stops
	 * being advice and starts being maintenance.</li>
	 * <li><b>DRY, 1000x.</b> Serious lubrication failure. Thirty seconds costs
	 * about 0.001 - genuinely nothing, so the accidental empty sump a player
	 * notices and fixes is forgiven. A dry engine left running under its own power
	 * destroys its bearings in something like seven hours, and a dry engine being
	 * motored hard by a Create network does it far faster than that. Dangerous, and
	 * never instant: see {@link #OVERSPEED_WEAR_COEFFICIENT} for the term that
	 * turns "dangerous" into "minutes" when it is stacked with abuse.</li>
	 * </ul>
	 */
	public static final float WEAR_MULTIPLIER_OIL_NORMAL = 1.0F;
	public static final float WEAR_MULTIPLIER_OIL_LOW = 18.0F;
	public static final float WEAR_MULTIPLIER_OIL_DRY = 1000.0F;

	/**
	 * Cylinder wear multiplier for an engine breathing through an open intake.
	 *
	 * <p>Applies to cylinder wear only - both the motion half and the combustion
	 * half, because both are about air being drawn down the bore - and never to
	 * the bearings. Unfiltered air is abrasive to rings and bores; it is not what
	 * kills a main bearing.
	 *
	 * <p>The Air Filter stays optional, and this is what makes that choice
	 * interesting rather than free: an unfiltered engine runs perfectly and wears
	 * its cylinders eight times as fast. Against the rebalanced baseline that is
	 * still measured in hundreds of hours, not seconds - five unfiltered minutes
	 * ruin nothing at all, and it takes something like 250 hours of unfiltered
	 * running to wear a bore to WORN. That is the correct shape for an optional
	 * part: a long-term consequence a player can knowingly accept, never a trap.
	 * Filter durability is deliberately not part of this milestone.
	 */
	public static final float WEAR_MULTIPLIER_UNFILTERED = 8.0F;

	/**
	 * Speed at which the engine is designed to run continuously - its own
	 * full-throttle target.
	 *
	 * <p>Below it {@link EngineWearMath#rpmWearFactor(float)} adds only a gentle
	 * stress term; above it the overspeed term takes over. Named separately from
	 * {@link #FULL_THROTTLE_RPM} even though it is the same number, because they
	 * are two different statements: one is what the throttle asks for, the other
	 * is what the bearings were sized for.
	 */
	public static final float RATED_CONTINUOUS_RPM = FULL_THROTTLE_RPM;

	/**
	 * Extra wear at the rated speed from mechanical stress alone, on top of simply
	 * completing more revolutions.
	 *
	 * <p>Quadratic in {@code rpm / RATED}, so idling is charged almost nothing
	 * (1.04x) and full throttle a modest 1.35x. A real engine is harder on itself
	 * near its limit than a straight per-revolution count would suggest, and this
	 * is that, kept small enough that revving is a choice rather than a punishment.
	 */
	public static final float RPM_STRESS_COEFFICIENT = 0.35F;

	/**
	 * How sharply wear climbs once the engine is turned faster than it was built
	 * for, quadratic in the fraction over.
	 *
	 * <p>The engine's own governor cannot get here: it takes combustion torque
	 * away well below {@link #MAX_RPM}. Only an external Create source can, and
	 * that is exactly the situation this is for - an engine geared up by a network
	 * far stronger than itself is being destroyed, and should be.
	 *
	 * <p>At Create's default 256 RPM ceiling the factor is about 10x, on top of
	 * four times the revolutions of an idling engine: roughly forty times the wear
	 * rate, which makes sustained overspeed the fastest way to ruin a well-oiled
	 * engine and the reason a healthy engine can still be destroyed at all.
	 *
	 * <p>Continuous and quadratic <i>through</i> the rated speed, which is the
	 * property that matters far more than the coefficient: the curve's slope is
	 * zero exactly where the governor sits, so the cost of overshooting is
	 * proportional to the square of how far over. A spike to 195 RPM costs 1.4 %
	 * extra and a governor ripple costs less than that, while 256 RPM costs
	 * everything. There is no threshold anywhere to fall off.
	 */
	public static final float OVERSPEED_WEAR_COEFFICIENT = 80.0F;

	/**
	 * How much a fully loaded network adds to bearing wear, and to cylinder wear.
	 *
	 * <p>Bearings carry the load, so they feel it more: a full load is 1.6x on the
	 * bearings and 1.25x in the bores. Both are linear in the engine's existing
	 * normalised load factor - the network's absolute stress figure is deliberately
	 * never used here, because it scales with speed and the wear model already has
	 * a speed term.
	 *
	 * <p>Deliberately modest, and deliberately <i>not</i> in the same league as the
	 * lubrication and overspeed terms. <b>Load is not abuse.</b> An engine exists to
	 * power machinery, and an engine hauling a full network with oil in it is doing
	 * exactly what it was built to do: it should cost a little more bearing life
	 * than freewheeling, and it should never approach what running the same engine
	 * dry costs. A player who works an engine hard is playing the game correctly.
	 */
	public static final float BEARING_LOAD_WEAR_COEFFICIENT = 0.6F;
	public static final float CYLINDER_LOAD_WEAR_COEFFICIENT = 0.25F;

	// --- wear: what the player is warned about ------------------------------

	/**
	 * Load above which the goggles say the engine is working hard enough for it to
	 * matter. Purely a display threshold; the wear itself is continuous.
	 */
	public static final float HEAVY_LOAD_WARNING_FACTOR = 0.75F;

	/**
	 * How far over the rated speed the engine has to be before the goggles call it
	 * overspeed, as a fraction.
	 *
	 * <p>A margin rather than an exact comparison, so an engine sitting on its
	 * full-throttle target with the usual couple of RPM of combustion ripple does
	 * not flicker a warning at the player.
	 */
	public static final float OVERSPEED_WARNING_MARGIN = 0.05F;

	// --- wear: what Create is told ------------------------------------------

	/**
	 * Step the engine's effective cylinder capacity is reported to Create in,
	 * measured in cylinder-equivalents.
	 *
	 * <p>Create caches one capacity number per source and re-propagating it is not
	 * free, while wear moves by something like 1e-6 per revolution - so publishing
	 * the raw figure would rebuild the kinetic network's stress bookkeeping
	 * several times a second for a change no player could ever see. Quantising to
	 * a hundredth of a cylinder turns a whole cylinder's lifetime into at most 35
	 * updates, which is the entire point.
	 *
	 * <p>Everything that is <i>not</i> slow drift still publishes at once: a
	 * cylinder starting or stopping firing, a Piston Assembly swapped, the engine
	 * catching or stalling, the structure changing. See
	 * {@code EngineState#updatePublishedCapacity}.
	 */
	public static final float CAPACITY_QUANTUM = 0.01F;

	/**
	 * Step the synchronised wear figures are quantised to before the block entity
	 * decides whether the client needs telling.
	 *
	 * <p>Same reasoning as the capacity quantum, applied to the wire instead of to
	 * Create: the server owns the exact value and saves it exactly, and the client
	 * only ever needs enough to name a condition band and trace the same
	 * coast-down curve. A hundredth is far finer than either needs.
	 */
	public static final float WEAR_SYNC_QUANTUM = 0.01F;

	// --- wear: starting ------------------------------------------------------

	/**
	 * Most extra firing events a start attempt asks for because the engine has
	 * lost compression, on top of the healthy count.
	 *
	 * <p>Scaled by how much compression is actually gone, so a moderately worn
	 * engine is barely harder to start and one at the service limit takes three
	 * more cycles. That is on top of the weaker firing kicks worn cylinders
	 * already produce, which is where most of the difficulty comes from - this is
	 * only what stops a badly worn engine from catching as decisively as a new one.
	 */
	public static final float START_CYCLES_WEAR_PENALTY = 3.0F;

	// --- wear: sound ---------------------------------------------------------

	/**
	 * Depth of the slow chatter a mechanically worn engine gets, as a fraction of
	 * its pitch, at the service limit.
	 *
	 * <p>Reuses the existing dry-engine roughness mechanism rather than adding a
	 * sound asset: a pure function of game time, so it cannot desynchronise
	 * between players and adds no state anywhere. It fades in from
	 * {@link #CONDITION_WORN_WEAR} so that a healthy engine is untouched, and it
	 * is deliberately smaller and slower than the dry wobble - a tired bearing, not
	 * a cartoon.
	 */
	public static final float SOUND_WEAR_ROUGHNESS = 0.025F;

	/** Wobble rate of that chatter, in radians per tick. Slower than the dry wobble. */
	public static final float SOUND_WEAR_ROUGHNESS_RATE = 0.31F;

	// --- wear: development ---------------------------------------------------

	/**
	 * System property that multiplies every wear rate, for development and manual
	 * testing only.
	 *
	 * <p>Real wear is measured in thousands of hours, which makes testing it by
	 * playing impossible. Setting
	 * {@code -Dengineered_combustion.wearMultiplier=100000} on a development client
	 * compresses a whole engine's life into a couple of minutes, which is what the
	 * milestone's manual test matrix needs. The 13.1 rebalance is why that is
	 * {@link #MAX_WEAR_MULTIPLIER} rather than the 2000 it used to be: against a
	 * baseline of some 2,800 hours, 2000x is an hour and a half.
	 *
	 * <p>A JVM property rather than a command or a config, deliberately: it cannot
	 * be reached from inside a running game, so there is no cheat button on a
	 * Survival server, and it needs no new command surface. It is read once, at
	 * class initialisation, and it is ignored unless it parses to a positive
	 * number no larger than {@link #MAX_WEAR_MULTIPLIER}.
	 */
	public static final String WEAR_MULTIPLIER_PROPERTY = "engineered_combustion.wearMultiplier";

	/** Ceiling on that property, so a typo cannot make wear meaningless. */
	public static final float MAX_WEAR_MULTIPLIER = 100000.0F;

	private static final float WEAR_MULTIPLIER = readWearMultiplier();

	/**
	 * Global multiplier on every accumulated wear increment. 1 in any normal game.
	 *
	 * @see #WEAR_MULTIPLIER_PROPERTY
	 */
	public static float wearRateMultiplier() {
		return WEAR_MULTIPLIER;
	}

	private static float readWearMultiplier() {
		try {
			String raw = System.getProperty(WEAR_MULTIPLIER_PROPERTY);
			if (raw == null)
				return 1.0F;
			float parsed = Float.parseFloat(raw.trim());
			return parsed > 0.0F && parsed <= MAX_WEAR_MULTIPLIER ? parsed : 1.0F;
		} catch (RuntimeException ignored) {
			// A malformed or unreadable property must never stop an engine from
			// running. The honest fallback is the shipping rate.
			return 1.0F;
		}
	}

	// --- helpers ------------------------------------------------------------

	/** Magnitude of friction torque at a given speed, fully lubricated. Always positive. */
	public static float frictionTorqueAt(float rpm) {
		return FRICTION_BASE_TORQUE + FRICTION_TORQUE_PER_RPM * Math.abs(rpm);
	}

	/**
	 * Friction torque including the penalty for poor lubrication.
	 *
	 * <p>This is the only place oil affects the engine mechanically. Everything
	 * else about a dry engine - that it revs lower, hauls less and stalls sooner
	 * under load - falls out of the existing simulation solving its equilibrium
	 * against this larger number.
	 */
	public static float frictionTorqueAt(float rpm, LubricationState lubrication) {
		return frictionTorqueAt(rpm) * lubrication.frictionMultiplier();
	}

	/** Extra drag from the kinetic load hanging off the engine. Always positive. */
	public static float loadDragTorque(float loadFactor) {
		return LOAD_DRAG_TORQUE * clamp01(loadFactor);
	}

	/**
	 * The <i>additional</i> drag an engine that is not firing suffers, over and above
	 * the friction it fights while running. Always positive.
	 *
	 * <p>Returned as the extra rather than as a replacement total, so the caller adds
	 * it to the ordinary friction term and that term keeps being counted exactly
	 * once - see {@code EngineState#integrate}.
	 *
	 * @see #COAST_FRICTION_MULTIPLIER
	 * @see #PUMPING_DRAG_TORQUE
	 */
	public static float coastDragTorqueAt(float rpm, LubricationState lubrication) {
		return frictionTorqueAt(rpm, lubrication) * (COAST_FRICTION_MULTIPLIER - 1.0F) + PUMPING_DRAG_TORQUE;
	}

	/**
	 * Speed the engine is being asked to hold, for a throttle in {@code [0, 1]}.
	 *
	 * <pre>
	 * target = IDLE + (FULL_THROTTLE - IDLE) * throttle
	 * </pre>
	 */
	public static float targetRpmForThrottle(float throttle) {
		return IDLE_RPM + (FULL_THROTTLE_RPM - IDLE_RPM) * clamp01(throttle);
	}

	/**
	 * Fraction of the available peak torque delivered at a given speed:
	 * 1 below the governor band, 0 above it, linear in between.
	 *
	 * <p>The band is centred on {@code targetRpm}, so it is exactly {@code 0.5}
	 * when the engine is sitting on its target. That identity is what
	 * {@link #peakCombustionTorqueFor(float)} inverts.
	 *
	 * <p>This is a <i>governor</i>, not a speed clamp: it only scales the torque
	 * combustion is allowed to make. The engine still has to accelerate there
	 * through its own inertia, and may overshoot past the target on the way.
	 */
	public static float governorFactor(float rpm, float targetRpm) {
		float half = GOVERNOR_RANGE_RPM / 2.0F;
		float factor = 1.0F - (rpm - (targetRpm - half)) / GOVERNOR_RANGE_RPM;
		return factor < 0.0F ? 0.0F : Math.min(factor, 1.0F);
	}

	/**
	 * Peak combustion torque that makes {@code targetRpm} the engine's
	 * equilibrium, derived rather than hand-tuned.
	 *
	 * <p>Equilibrium is where the governed combustion torque, averaged over one
	 * revolution, cancels friction:
	 *
	 * <pre>
	 * peak * POWER_STROKE_DUTY * governorFactor(target, target) = friction(target)
	 * peak * POWER_STROKE_DUTY * 0.5                            = friction(target)
	 * </pre>
	 *
	 * so {@code peak = friction(target) / (DUTY * 0.5)}. Opening the throttle
	 * therefore does not move a speed dial - it hands combustion more torque,
	 * and the engine accelerates on that torque until friction catches up again.
	 *
	 * <p>Friction here is the fully-lubricated figure on purpose. A dry engine
	 * fights more friction than this solution assumed and so settles below its
	 * target at every throttle setting, which is exactly the existing
	 * lubrication behaviour, now present across the whole range.
	 */
	public static float peakCombustionTorqueFor(float targetRpm) {
		return frictionTorqueAt(targetRpm) / (POWER_STROKE_DUTY * 0.5F);
	}

	/**
	 * Peak combustion torque of <i>one cylinder</i> of an engine that has this
	 * many, so that a fully-firing engine still settles on its throttle target
	 * whatever its cylinder count.
	 *
	 * <pre>
	 * perCylinder = peakCombustionTorqueFor(target) / cylinderCount
	 * </pre>
	 *
	 * <p><b>Why the division is right, and not a way of taking the power back.</b>
	 * The throttle is a governor setpoint: 0 % means "hold 64 RPM", and it has to
	 * mean that for an inline-4 as much as for a single, or the whole readout
	 * stops making sense. A real governor achieves that by metering <i>less charge
	 * per cylinder</i> the more cylinders it is feeding - which is exactly this.
	 *
	 * <p>What more cylinders buy is not a higher free-running speed. It is:
	 * <ul>
	 * <li><b>Stress Capacity</b>, which scales with the number of cylinders that
	 * are genuinely firing - see {@code EngineState#getFiringCylinderCount()} -
	 * so an inline-4 supplies four times the power budget an inline-1 does, and
	 * therefore sags far less under the same real load, because the load factor it
	 * feels is that load over a four times larger capacity;</li>
	 * <li><b>smoothness</b>, from four smaller impulses 90 degrees apart instead of
	 * one big one;</li>
	 * <li>and it costs four times the gasoline, because four cylinders fire four
	 * times per revolution.</li>
	 * </ul>
	 *
	 * <p>It also makes a misfire mean something. A cylinder with no Spark Plug
	 * contributes nothing, so an inline-4 running on three cylinders makes three
	 * quarters of the torque the governor solved for and settles visibly below its
	 * target - which is precisely what a real engine dropping a cylinder does.
	 */
	public static float peakCombustionTorqueFor(float targetRpm, int cylinderCount) {
		return peakCombustionTorqueFor(targetRpm) / Math.max(1, cylinderCount);
	}

	/** Combustion torque actually delivered during a power stroke. */
	public static float combustionTorqueAt(float rpm, float targetRpm) {
		return peakCombustionTorqueFor(targetRpm) * governorFactor(rpm, targetRpm);
	}

	/** Combustion torque one cylinder of a {@code cylinderCount}-cylinder engine delivers. */
	public static float combustionTorqueAt(float rpm, float targetRpm, int cylinderCount) {
		return peakCombustionTorqueFor(targetRpm, cylinderCount) * governorFactor(rpm, targetRpm);
	}

	/**
	 * Torque one cylinder's trapped charge exerts on the crank at a given
	 * <i>local</i> crank angle.
	 *
	 * <pre>
	 * torque = -COMPRESSION_PEAK_TORQUE * sin(theta) * (1 - cos(theta)) / 2
	 * </pre>
	 *
	 * <p>Negative - resisting - from bottom dead centre (0 degrees) up to top dead
	 * centre (180), positive - assisting - on the way back down, and zero at both
	 * dead centres, where the crank has no leverage on the piston at all. The
	 * {@code (1 - cos)/2} factor is the piston's own position, so the resistance
	 * builds as the charge is actually squeezed instead of peaking halfway up an
	 * empty bore.
	 *
	 * <p><b>It integrates to exactly zero over one revolution</b>, which is what
	 * makes it a spring rather than a second friction: it can shape the rotation
	 * without moving the speed the engine settles at, so every equilibrium the
	 * throttle promises still holds.
	 */
	public static float compressionTorqueAt(float localCrankAngleDegrees) {
		double theta = Math.toRadians(localCrankAngleDegrees);
		return (float) (-COMPRESSION_PEAK_TORQUE * Math.sin(theta) * (1.0D - Math.cos(theta)) / 2.0D);
	}

	public static float clamp01(float value) {
		return value < 0.0F ? 0.0F : Math.min(value, 1.0F);
	}

	/** Throttle lever angle in degrees for a throttle in {@code [0, 1]}. */
	public static float throttleLeverDegrees(float throttle) {
		return THROTTLE_LEVER_CLOSED_DEGREES
			+ (THROTTLE_LEVER_OPEN_DEGREES - THROTTLE_LEVER_CLOSED_DEGREES) * clamp01(throttle);
	}

	/**
	 * Playback pitch of the <b>mechanical</b> layer at a given mechanical speed.
	 *
	 * <p>This is the single place engine speed becomes loop pitch, and it is the
	 * only layer that has any business being pitched by speed: it is a rotating
	 * object, so its sound genuinely is a function of how fast it rotates. The
	 * firing rhythm is <i>not</i> here - it comes from the combustion events
	 * themselves.
	 *
	 * <p>Deliberately not proportional: pitch follows the speed ratio raised to
	 * {@link #SOUND_PITCH_EXPONENT}, then clamps. Over the engine's whole range
	 * (rest to {@link #MAX_RPM}) that spans 0.80x to about 1.26x - audibly
	 * responsive across the throttle, never silly.
	 *
	 * <p>One reference speed for every state, so cranking, running and coasting are
	 * one continuous curve: a hand crank sits at 0.87x, idle at exactly 1.0x, full
	 * throttle at 1.25x, and no state change can make the pitch jump.
	 *
	 * <p>Safe to call with any value, including zero and negatives; the engine is
	 * turned backwards often enough that this must not produce NaN.
	 */
	public static float mechanicalLayerPitch(float rpm) {
		float ratio = Math.abs(rpm) / SOUND_REFERENCE_RPM;
		if (ratio <= 0.0F)
			return SOUND_MIN_PITCH;
		return clampPitch((float) Math.pow(ratio, SOUND_PITCH_EXPONENT));
	}

	/**
	 * Pitch of one combustion pulse, before its random jitter.
	 *
	 * <p>Bends by at most {@link #SOUND_COMBUSTION_PITCH_RANGE} across the entire
	 * speed range, and that shallowness is the point: <b>pitch must never be used
	 * to imply a firing rate</b>. The rate the player hears is the rate the engine
	 * actually fired at, because every pulse is one real combustion event.
	 */
	public static float combustionPulsePitch(float rpm) {
		float ratio = Math.abs(rpm) / SOUND_REFERENCE_RPM;
		if (ratio <= 0.0F)
			return 1.0F - SOUND_COMBUSTION_PITCH_RANGE;
		// log2 of the speed ratio: one octave of speed moves the pulse by the full
		// range, which over idle-to-full-throttle (a ratio of 3) is about +0.16.
		float octaves = (float) (Math.log(ratio) / Math.log(2.0));
		return clampPitch(1.0F + SOUND_COMBUSTION_PITCH_RANGE * clamp(octaves, -1.5F, 1.5F));
	}

	/**
	 * How far the continuous combustion layer has faded in at a given firing rate:
	 * 0 while individual pulses still carry the engine, 1 once they cannot.
	 *
	 * <p>Always 0 for the current engine, whose fastest firing rate is 3.2 Hz. This
	 * is the seam a faster engine, a four-stroke or a second cylinder crosses, and
	 * it exists now so that crossing it later is a tuning change rather than an
	 * audio rewrite.
	 */
	public static float combustionLoopBlend(float rateHz) {
		if (rateHz <= SOUND_COMBUSTION_PULSE_MAX_RATE_HZ)
			return 0.0F;
		float span = SOUND_COMBUSTION_BLEND_FULL_RATE_HZ - SOUND_COMBUSTION_PULSE_MAX_RATE_HZ;
		return clamp01((rateHz - SOUND_COMBUSTION_PULSE_MAX_RATE_HZ) / span);
	}

	/**
	 * How long the engine may go without a combustion event, at a given speed, and
	 * still count as actively generating.
	 *
	 * <p>Scaled by the firing interval - see
	 * {@link #GENERATION_COMBUSTION_REVOLUTIONS} - and hard-capped, so it is
	 * neither too tight at idle nor open-ended on a crawling engine.
	 */
	public static int generationCombustionAllowanceTicks(float rpm) {
		float speed = Math.abs(rpm);
		if (speed < REST_RPM)
			return GENERATION_COMBUSTION_LIMIT_TICKS;
		// 1200 = 60 s/min * 20 ticks/s, so this is ticks per revolution.
		float ticksPerRevolution = 1200.0F / speed;
		int allowance = Math.round(GENERATION_COMBUSTION_REVOLUTIONS * ticksPerRevolution) + 2;
		return Math.min(allowance, GENERATION_COMBUSTION_LIMIT_TICKS);
	}

	private static float clampPitch(float pitch) {
		return pitch < SOUND_MIN_PITCH ? SOUND_MIN_PITCH : Math.min(pitch, SOUND_MAX_PITCH);
	}

	private static float clamp(float value, float min, float max) {
		return value < min ? min : Math.min(value, max);
	}

	/**
	 * rpm -> degrees of crank rotation per game tick.
	 *
	 * <p>rpm * 360 deg/rev / 60 s/min / 20 ticks/s, identical to Create's
	 * {@code KineticBlockEntity#convertToAngular}. Sign is preserved, so negative
	 * speed turns the crank backwards.
	 */
	public static float degreesPerTick(float rpm) {
		return rpm * 360.0F / 60.0F / 20.0F;
	}
}
