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
	public static final float SOUND_RUNNING_VOLUME = 0.55F;
	public static final float SOUND_CRANKING_VOLUME = 0.40F;
	public static final float SOUND_FIRE_ATTEMPT_VOLUME = 0.45F;
	/**
	 * The ignition tick. Quiet on purpose - it is a coil discharging, not an
	 * event, and it has to stay well under the cough that may follow it or the
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

	/** Combustion torque actually delivered during a power stroke. */
	public static float combustionTorqueAt(float rpm, float targetRpm) {
		return peakCombustionTorqueFor(targetRpm) * governorFactor(rpm, targetRpm);
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
	 * Playback pitch for the running engine loop at a given mechanical speed.
	 *
	 * <p>This is the single place engine speed becomes audio pitch. It is
	 * deliberately <i>not</i> proportional: pitch follows the speed ratio raised to
	 * {@link #SOUND_PITCH_EXPONENT}, then clamps. Over the engine's whole range
	 * (stall to {@link #MAX_RPM}) that spans 0.80x to about 1.26x - audibly
	 * responsive across the throttle, never silly.
	 *
	 * <p>Because this is a pure function of speed, sweeping the throttle only
	 * ever changes the pitch of the loop that is already playing. Loop identity
	 * is decided by {@code EngineLoopKind}, which depends on the engine's phase
	 * alone, so no throttle movement can start a second loop.
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
