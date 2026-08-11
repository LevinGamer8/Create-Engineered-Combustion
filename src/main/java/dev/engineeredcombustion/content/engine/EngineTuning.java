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
	 * count. Always at least one.
	 */
	public static int requiredStartCycles(int rolledCycles, int cylinderCount) {
		float scale = 1.0F + START_CYCLES_PER_EXTRA_CYLINDER * Math.max(0, cylinderCount - 1);
		return Math.max(1, Math.round(rolledCycles * scale));
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
