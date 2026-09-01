package dev.engineeredcombustion.content.engine.fourstroke;

/**
 * The 720-degree cycle's arithmetic: angle algebra, robust event crossing, and the
 * shape of the torque a single cylinder exerts on the crank at any point in its
 * cycle.
 *
 * <h2>Two angles, and they are not the same angle</h2>
 * <dl>
 * <dt>physical crank angle, {@code [0, 360)}</dt>
 * <dd>Where the crank pin and therefore the piston <i>is</i>. Repeats every
 * revolution. This is what every renderer draws.</dd>
 * <dt>engine cycle angle, {@code [0, 720)}</dt>
 * <dd>Where the cylinder is in its four-stroke <i>cycle</i>. Repeats every two
 * revolutions, and is the authoritative state.</dd>
 * </dl>
 * They are related by {@link #physicalAngle}, which is a plain {@code % 360}
 * <i>because the convention was chosen to make it one</i> - see
 * {@link FourStrokePhase}. The relation is one-way: a cycle angle always yields a
 * physical angle, and a physical angle never yields a cycle angle, because two
 * different strokes share it.
 */
public final class FourStrokeCycle {

	/** One complete four-stroke cycle, in crankshaft degrees. */
	public static final float CYCLE_DEGREES = 720.0F;

	/** One crankshaft revolution, in degrees. The piston's own period. */
	public static final float REVOLUTION_DEGREES = 360.0F;

	/**
	 * Cycle angle at which a cylinder's charge is lit: compression top dead centre.
	 *
	 * <p>Numerically the old {@code EngineTuning#FIRING_ANGLE_DEGREES}, and that is
	 * not a coincidence - see {@link FourStrokePhase}. What Milestone 15B changes is
	 * not the angle but the <i>modulus</i> it is measured against: a cylinder reaches
	 * this cycle angle once per 720 degrees, where it reaches the same physical angle
	 * once per 360.
	 *
	 * <p>No ignition advance. Real engines fire before top dead centre and the amount
	 * varies with speed; that is out of scope, and the constant is here so adding it
	 * later is a change to one number.
	 */
	public static final float IGNITION_ANGLE_DEGREES = 180.0F;

	/**
	 * Cycle angle at which a cylinder becomes able to fire again: exhaust top dead
	 * centre, i.e. the start of the intake stroke.
	 *
	 * <p><b>This is the anti-oscillation mechanism, and it is a physical statement
	 * rather than a guard.</b> A cylinder cannot burn a charge it has not drawn in,
	 * so ignition is permitted only to a cylinder that has passed forwards through
	 * the start of its intake stroke since it last fired. Rocking the crank back and
	 * forth across the ignition point therefore cannot produce a second bang: to
	 * re-arm, the crank must travel forwards from 180 to 540, and to then fire it
	 * must travel forwards from 540 to 180. That is 720 degrees of forward progress
	 * between any two ignitions of one cylinder, and no oscillation shortens it.
	 */
	public static final float ARMING_ANGLE_DEGREES = 540.0F;

	/** How far past ignition the burning charge keeps pushing: the whole power stroke. */
	public static final float POWER_STROKE_DEGREES = 180.0F;

	/**
	 * Fraction of the <b>cycle</b> during which one cylinder's combustion pushes.
	 *
	 * <pre>
	 * 180 / 720 = 0.25
	 * </pre>
	 *
	 * <p>The old engine's duty was the same 180 degrees over 360, i.e. 0.5.
	 * <b>This halving is the entire average-power problem of Milestone 15, and also
	 * its entire solution</b>: {@code EngineTuning#peakCombustionTorqueFor} solves
	 * {@code peak * DUTY * 0.5 = friction(target)} for the peak, so substituting this
	 * duty doubles the peak automatically and the engine settles at exactly the speed
	 * it settled at before. Nothing is retuned by hand.
	 */
	public static final float POWER_STROKE_DUTY = POWER_STROKE_DEGREES / CYCLE_DEGREES;

	private FourStrokeCycle() {
	}

	/**
	 * Folds any angle into {@code [0, 720)}.
	 *
	 * <p>The {@code + 0.0F} is not redundant: {@code -0.0F % 720} is {@code -0.0F},
	 * which is not less than zero and so survives the branch, and a diagnostic
	 * overlay reading "-0.0 degrees" is a bug report waiting to happen. Adding
	 * positive zero is the IEEE-defined way to collapse it.
	 */
	public static float normalizeCycle(float degrees) {
		float wrapped = degrees % CYCLE_DEGREES;
		return (wrapped < 0.0F ? wrapped + CYCLE_DEGREES : wrapped) + 0.0F;
	}

	/** Folds any angle into {@code [0, 360)}. Negative zero collapsed as above. */
	public static float normalizeRevolution(float degrees) {
		float wrapped = degrees % REVOLUTION_DEGREES;
		return (wrapped < 0.0F ? wrapped + REVOLUTION_DEGREES : wrapped) + 0.0F;
	}

	/**
	 * The physical crank angle a cycle angle corresponds to.
	 *
	 * <p>A plain fold into one revolution, and deliberately the only bridge between
	 * the two representations. Everything mechanical - piston height, rod swing, the
	 * crank pin the player can see through the window - is a function of <i>this</i>,
	 * and everything about strokes, valves and ignition is a function of the cycle
	 * angle. Conflating them is the bug this design exists to avoid.
	 */
	public static float physicalAngle(float cycleAngleDegrees) {
		return normalizeRevolution(cycleAngleDegrees);
	}

	/**
	 * Whether a forward-turning crank passed {@code targetAngle} during a step that
	 * ended at {@code angleAfter}.
	 *
	 * <p>A crossing test rather than an equality test, because a tick advances the
	 * crank by a finite jump - about 62 degrees at the engine's own ceiling, and more
	 * if an external Create network is driving it - and will routinely step straight
	 * over any exact value.
	 *
	 * <p>Arriving exactly on the target counts as crossing it, and the following step
	 * does not count it again.
	 *
	 * @param angleAfter   this cylinder's cycle angle after the step, in {@code [0, 720)}
	 * @param deltaDegrees signed crank travel during the step. Zero or negative never
	 *                     crosses: an engine being turned backwards does not fire
	 */
	public static boolean crossedForward(float angleAfter, float deltaDegrees, float targetAngle) {
		if (deltaDegrees <= 0.0F)
			return false;
		// A single step longer than the whole cycle passes every angle in it. Guarding
		// this separately keeps the arithmetic below free of a wrap it cannot express.
		if (deltaDegrees >= CYCLE_DEGREES)
			return true;
		float travelledPastTarget = normalizeCycle(angleAfter - deltaDegrees - targetAngle);
		return travelledPastTarget + deltaDegrees >= CYCLE_DEGREES;
	}

	/**
	 * Whether a backward-turning crank passed {@code targetAngle} during a step that
	 * ended at {@code angleAfter}.
	 *
	 * <p>The mirror of {@link #crossedForward}, and it exists for one reason: a
	 * charge is not still waiting to be lit after the crank has dragged the piston
	 * back down past top dead centre. Without it the arming latch is defeated by a
	 * crank that reverses - arm at 540, wind back <i>through</i> 180 without firing,
	 * then nudge forward across 180 and collect a bang for two degrees of travel.
	 *
	 * <p>The half-open convention is the mirror of the forward one, so the two can
	 * never both fire on the same step and an angle is never counted twice: leaving
	 * the target backwards counts, arriving on it backwards does not.
	 */
	public static boolean crossedBackward(float angleAfter, float deltaDegrees, float targetAngle) {
		if (deltaDegrees >= 0.0F)
			return false;
		float travelled = -deltaDegrees;
		if (travelled >= CYCLE_DEGREES)
			return true;
		float targetBehindStart = normalizeCycle(angleAfter + travelled - targetAngle);
		return targetBehindStart < travelled;
	}

	// ------------------------------------------------------------------------
	// Torque shapes
	// ------------------------------------------------------------------------

	/**
	 * Torque the trapped charge exerts on the crank, as a multiple of the peak
	 * compression torque.
	 *
	 * <pre>
	 * -sin(theta) * (1 - cos(theta)) / 2      on COMPRESSION and POWER
	 *  0                                      on EXHAUST and INTAKE
	 * </pre>
	 *
	 * <p>The first line is the pre-15B {@code EngineTuning#compressionTorqueAt}
	 * waveform, unchanged, with {@code theta} the physical crank angle - which on
	 * {@code [0, 360)} of the cycle <i>is</i> the cycle angle. The second line is the
	 * correction Milestone 15B exists to make: the piston reaches top dead centre
	 * twice per cycle, but only one of those is a compression, because on the other
	 * the exhaust valve is open and there is nothing to squeeze.
	 *
	 * <p><b>Still integrates to exactly zero over the cycle</b>, because it
	 * integrates to zero over the sealed half and is zero over the other. That is
	 * what keeps it a spring rather than a second friction, and why switching to
	 * four-stroke compression moves no equilibrium speed. What it changes is the
	 * <i>rhythm</i>: a cylinder is fought once per two revolutions instead of once
	 * per one.
	 */
	public static float gasSpringShape(float cycleAngleDegrees) {
		float angle = normalizeCycle(cycleAngleDegrees);
		if (!FourStrokePhase.at(angle).sealed())
			return 0.0F;
		double theta = Math.toRadians(angle);
		return (float) (-Math.sin(theta) * (1.0D - Math.cos(theta)) / 2.0D);
	}

	/**
	 * Whether a charge lit at this cylinder's ignition point is still pushing.
	 *
	 * <p>Flat across the power stroke and zero elsewhere, which is exactly the shape
	 * the engine applied before 15B - it added a constant torque for every tick a
	 * latched charge was within its power stroke. Kept identical on purpose: the
	 * point of Milestone 15B is to change <i>when</i> combustion happens, not to
	 * re-model what one bang feels like.
	 */
	public static boolean withinPowerStroke(float cycleAngleDegrees) {
		return FourStrokePhase.at(cycleAngleDegrees) == FourStrokePhase.POWER;
	}
}
