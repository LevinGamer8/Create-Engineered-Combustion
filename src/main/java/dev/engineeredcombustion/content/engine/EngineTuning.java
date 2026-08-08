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
 * <li>{@code maxRotationSpeed} (config default 256 RPM). {@link #MAX_RPM} stays
 * below it.</li>
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

	// --- speeds, in RPM -----------------------------------------------------

	/**
	 * Minimum forward speed at which debug combustion can begin. Deliberately
	 * below Create's Hand Crank, which generates exactly 32 RPM
	 * ({@code HandCrankBlock#getRotationSpeed}), so a hand crank can start the
	 * engine.
	 */
	public static final float START_RPM = 24.0F;

	/** Below this the engine stops entirely. */
	public static final float STALL_RPM = 10.0F;

	/** Free-running equilibrium speed. Combustion torque is derived from this. */
	public static final float IDLE_RPM = 64.0F;

	/** Safety clamp, kept under Create's default {@code maxRotationSpeed} of 256. */
	public static final float MAX_RPM = 192.0F;

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
	 * Width of the band over which combustion torque fades out, centred on
	 * {@link #IDLE_RPM}. Full torque at {@code IDLE - 16}, none at
	 * {@code IDLE + 16}. Without it the engine would approach idle asymptotically
	 * and take absurdly long to spin up; with it the engine pulls hard off the
	 * hand crank and then settles.
	 */
	public static final float GOVERNOR_RANGE_RPM = 32.0F;

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
	 * Peak combustion torque, derived rather than hand-tuned.
	 *
	 * <p>Solved so that at exactly {@link #IDLE_RPM} the governed combustion
	 * torque, averaged over one revolution, cancels friction. Changing IDLE_RPM,
	 * the friction constants or the governor range therefore moves the engine's
	 * equilibrium speed correctly instead of silently desynchronising two magic
	 * numbers.
	 */
	public static final float PEAK_COMBUSTION_TORQUE =
		frictionTorqueAt(IDLE_RPM) / (POWER_STROKE_DUTY * governorFactor(IDLE_RPM));

	// --- publishing to Create's kinetic network -----------------------------

	/** Generated speed is rounded to this step before Create ever sees it. */
	public static final float NETWORK_RPM_QUANTUM = 4.0F;

	/**
	 * The engine's speed has to move this far from the currently published value
	 * before a new value is pushed.
	 *
	 * <p>Must comfortably exceed the engine's within-revolution ripple (about
	 * +/-2 RPM at {@link #FLYWHEEL_INERTIA} = 20), otherwise the ripple alone
	 * would re-propagate the kinetic network once per revolution and every
	 * downstream machine would visibly stutter.
	 */
	public static final float NETWORK_RPM_DEADBAND = 8.0F;

	/** Minimum ticks between two non-zero generated-speed updates. */
	public static final int NETWORK_MIN_UPDATE_INTERVAL_TICKS = 4;

	// --- fuel ---------------------------------------------------------------

	/**
	 * Gasoline drawn per combustion event, in millibuckets. Charged per <i>firing
	 * event</i>, never per tick, so consumption scales with engine speed exactly
	 * like a real engine: one revolution, one charge. At idle (64 RPM ~ 1.07
	 * revolutions per second) a full 1000 mB carburetor lasts about 16 minutes.
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

	// --- stress -------------------------------------------------------------

	/** Capacity per RPM, Create's convention. 32 * 64 RPM = 2048 SU at idle. */
	public static final double STRESS_CAPACITY_PER_RPM = 32.0D;

	// --- sound --------------------------------------------------------------

	/**
	 * Speed at which {@code engine_running.ogg} plays back unshifted.
	 *
	 * <p>The asset was synthesised at the engine's idle character, so idle is by
	 * definition pitch 1.0 and the mapping only has to describe the deviation.
	 */
	public static final float SOUND_REFERENCE_RPM = IDLE_RPM;

	/** Cranking speed at which {@code engine_cranking.ogg} plays unshifted, matching Create's Hand Crank. */
	public static final float SOUND_CRANKING_REFERENCE_RPM = 32.0F;

	/**
	 * How strongly speed is allowed to bend pitch, as an exponent on the speed
	 * ratio. Well below 1 on purpose: Create's RPM values are gameplay numbers,
	 * not crankshaft RPM, so mapping them proportionally would take the engine
	 * from a murmur to a chipmunk across its normal range.
	 */
	public static final float SOUND_PITCH_EXPONENT = 0.35F;

	public static final float SOUND_MIN_PITCH = 0.75F;
	public static final float SOUND_MAX_PITCH = 1.45F;

	/** Volumes are Minecraft attenuation units; blocks fall off over roughly 16 * volume blocks. */
	public static final float SOUND_RUNNING_VOLUME = 0.55F;
	public static final float SOUND_CRANKING_VOLUME = 0.40F;
	public static final float SOUND_FIRE_ATTEMPT_VOLUME = 0.45F;
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

	/**
	 * Fraction of {@link #PEAK_COMBUSTION_TORQUE} available at a given speed:
	 * 1 below the governor band, 0 above it, linear in between.
	 */
	public static float governorFactor(float rpm) {
		float half = GOVERNOR_RANGE_RPM / 2.0F;
		float factor = 1.0F - (rpm - (IDLE_RPM - half)) / GOVERNOR_RANGE_RPM;
		return factor < 0.0F ? 0.0F : Math.min(factor, 1.0F);
	}

	/** Combustion torque actually delivered during a power stroke at this speed. */
	public static float combustionTorqueAt(float rpm) {
		return PEAK_COMBUSTION_TORQUE * governorFactor(rpm);
	}

	/**
	 * Playback pitch for the running engine loop at a given mechanical speed.
	 *
	 * <p>This is the single place engine speed becomes audio pitch. It is
	 * deliberately <i>not</i> proportional: pitch follows the speed ratio raised to
	 * {@link #SOUND_PITCH_EXPONENT}, then clamps. Over the engine's whole range
	 * (stall to {@link #MAX_RPM}) that spans roughly 0.75x to 1.45x - audibly
	 * responsive, never silly.
	 *
	 * <p>Safe to call with any value, including zero and negatives; the engine is
	 * turned backwards often enough that this must not produce NaN.
	 */
	public static float mapMechanicalRpmToEnginePitch(float rpm) {
		return mapSpeedRatioToPitch(rpm, SOUND_REFERENCE_RPM);
	}

	/** The same curve for the cranking loop, referenced to hand-crank speed instead of idle. */
	public static float mapMechanicalRpmToCrankingPitch(float rpm) {
		return mapSpeedRatioToPitch(rpm, SOUND_CRANKING_REFERENCE_RPM);
	}

	private static float mapSpeedRatioToPitch(float rpm, float referenceRpm) {
		float ratio = Math.abs(rpm) / referenceRpm;
		if (ratio <= 0.0F)
			return SOUND_MIN_PITCH;
		return clampPitch((float) Math.pow(ratio, SOUND_PITCH_EXPONENT));
	}

	private static float clampPitch(float pitch) {
		return pitch < SOUND_MIN_PITCH ? SOUND_MIN_PITCH : Math.min(pitch, SOUND_MAX_PITCH);
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
