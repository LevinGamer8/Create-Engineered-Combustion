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

	// --- stress -------------------------------------------------------------

	/** Capacity per RPM, Create's convention. 32 * 64 RPM = 2048 SU at idle. */
	public static final double STRESS_CAPACITY_PER_RPM = 32.0D;

	// --- helpers ------------------------------------------------------------

	/** Magnitude of friction torque at a given speed. Always positive. */
	public static float frictionTorqueAt(float rpm) {
		return FRICTION_BASE_TORQUE + FRICTION_TORQUE_PER_RPM * Math.abs(rpm);
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
