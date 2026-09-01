package dev.engineeredcombustion.prototype.fourstroke;

/**
 * The 720-degree cycle's arithmetic: angle algebra, robust event crossing, and the
 * torque a single cylinder exerts on the crank at any point in its cycle.
 *
 * <p><b>Prototype. Nothing in the mod depends on this.</b>
 *
 * <h2>Two angles, and they are not the same angle</h2>
 * <dl>
 * <dt>physical crank angle, {@code [0, 360)}</dt>
 * <dd>Where the crank pin and therefore the piston <i>is</i>. Repeats every
 * revolution. This is exactly the production {@code EngineState#getCrankAngleDegrees}
 * and it is what every renderer already draws.</dd>
 * <dt>engine cycle angle, {@code [0, 720)}</dt>
 * <dd>Where the cylinder is in its four-stroke <i>cycle</i>. Repeats every two
 * revolutions. Nothing in the production engine has this yet, and it is what the
 * whole of Milestone 15 is about adding.</dd>
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
	 * <p>Numerically the production {@code EngineTuning#FIRING_ANGLE_DEGREES}, and
	 * that is not a coincidence - see {@link FourStrokePhase}. What changes in
	 * Milestone 15B is not the angle but the <i>modulus</i> it is measured against:
	 * a cylinder reaches this cycle angle once per 720 degrees, where it reaches the
	 * same physical angle once per 360.
	 *
	 * <p>No ignition advance. Real engines fire before top dead centre and the amount
	 * varies with speed; that is explicitly out of scope, and the constant is here so
	 * that adding it later is a change to one number.
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
	 *
	 * @see FourStrokeCylinderTiming#advance
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
	 * <p>The production engine's {@code POWER_STROKE_DUTY} is the same 180 degrees
	 * over 360, i.e. 0.5. <b>This halving is the entire average-power problem of
	 * Milestone 15, and also its entire solution</b>: {@code peakCombustionTorqueFor}
	 * already solves {@code peak * DUTY * 0.5 = friction(target)} for the peak, so
	 * substituting this duty doubles the peak automatically and the engine settles at
	 * exactly the speed it settles at today. Nothing is retuned by hand.
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
	 * angle. Milestone 15B has to keep that separation; conflating them is the bug
	 * this whole design exists to avoid.
	 */
	public static float physicalAngle(float cycleAngleDegrees) {
		return normalizeRevolution(cycleAngleDegrees);
	}

	/**
	 * Whether a forward-turning crank passed {@code targetAngle} during a step that
	 * ended at {@code angleAfter}.
	 *
	 * <p>Structurally the production {@code EngineState#crossedFiringAngle},
	 * generalised to an arbitrary target and to the 720-degree modulus. A crossing
	 * test rather than an equality test, because a tick advances the crank by a
	 * finite jump - about 62 degrees at the engine's own ceiling, and more if an
	 * external Create network is driving it - and will routinely step straight over
	 * any exact value.
	 *
	 * <p>Arriving exactly on the target counts as crossing it, and the following step
	 * does not count it again.
	 *
	 * @param angleAfter this cylinder's cycle angle after the step, in {@code [0, 720)}
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
	 * The prototype had exactly that hole and its own test found it.
	 *
	 * <p>The half-open convention is the mirror of the forward one, so the two can
	 * never both fire on the same step and an angle is never counted twice: leaving
	 * the target backwards counts, arriving on it backwards does not.
	 *
	 * @param angleAfter this cylinder's cycle angle after the step, in {@code [0, 720)}
	 * @param deltaDegrees signed crank travel. Zero or positive never crosses backwards
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
	// Torque
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
	 * <p>The first line is the production {@code EngineTuning#compressionTorqueAt}
	 * waveform, unchanged, with {@code theta} the physical crank angle - which on
	 * {@code [0, 360)} of the cycle <i>is</i> the cycle angle. The second line is the
	 * correction Milestone 15 exists to make: the piston reaches top dead centre
	 * twice per cycle, but only one of those is a compression, because on the other
	 * the exhaust valve is open and there is nothing to squeeze.
	 *
	 * <p><b>Still integrates to exactly zero over the cycle</b>, because it
	 * integrates to zero over the sealed half and is zero over the other. That is
	 * what keeps it a spring rather than a second friction, and it is why switching
	 * to four-stroke compression moves no equilibrium speed. What it does change is
	 * the <i>rhythm</i>: a cylinder is fought once per two revolutions instead of
	 * once per one, which is precisely what makes motoring a four-stroke feel
	 * different from motoring a two-stroke.
	 */
	public static float gasSpringTorque(float cycleAngleDegrees) {
		float angle = normalizeCycle(cycleAngleDegrees);
		if (!FourStrokePhase.at(angle).sealed())
			return 0.0F;
		double theta = Math.toRadians(angle);
		return (float) (-Math.sin(theta) * (1.0D - Math.cos(theta)) / 2.0D);
	}

	/**
	 * Pumping resistance while a valve is open, as a multiple of the peak pumping
	 * torque. Always non-positive: pumping is a loss, never a return.
	 *
	 * <pre>
	 * -sin^2(theta)     on EXHAUST and INTAKE
	 *  0                on COMPRESSION and POWER
	 * </pre>
	 *
	 * <p>Deliberately crude, and the crudeness is the design. Piston speed goes as
	 * {@code sin(theta)} and the crank's leverage on the piston goes as
	 * {@code sin(theta)} as well, so a resistance proportional to speed shows up at
	 * the crank as {@code sin^2}. That is enough to make the non-power strokes cost
	 * something and to put the loss where the piston is actually moving; it is not
	 * gas dynamics, and Milestone 15 explicitly does not want gas dynamics.
	 *
	 * <p><b>Unlike the gas spring, this does not integrate to zero</b>, so switching
	 * it on shifts the speed the engine settles at. Its mean over the cycle is
	 * {@code -1/8} of the peak per cylinder (mean of {@code sin^2} is {@code 1/2},
	 * over half the cycle, split across two strokes), so the equilibrium solution
	 * that derives combustion torque has to absorb it:
	 *
	 * <pre>
	 * peak = (friction(target) + (pumpPeakIntake + pumpPeakExhaust) / 8) / (DUTY * 0.5)
	 * </pre>
	 *
	 * The design document recommends shipping 15B with both peaks at zero and adding
	 * them, with that correction, as a separate step.
	 */
	public static float pumpingTorque(float cycleAngleDegrees) {
		float angle = normalizeCycle(cycleAngleDegrees);
		if (!FourStrokePhase.at(angle).pumping())
			return 0.0F;
		double sin = Math.sin(Math.toRadians(angle));
		return (float) -(sin * sin);
	}

	/**
	 * Combustion torque from a charge lit at this cylinder's ignition point, as a
	 * multiple of the peak combustion torque.
	 *
	 * <p>Flat across the power stroke and zero elsewhere, which is exactly the shape
	 * the production engine already applies - it adds a constant
	 * {@code combustionTorqueAt(...)} for every tick a latched charge is within its
	 * power stroke. Kept identical on purpose: the point of Milestone 15 is to change
	 * <i>when</i> combustion happens, not to re-model what one bang feels like.
	 */
	public static float combustionTorque(float cycleAngleDegrees) {
		return FourStrokePhase.at(cycleAngleDegrees) == FourStrokePhase.POWER ? 1.0F : 0.0F;
	}
}
