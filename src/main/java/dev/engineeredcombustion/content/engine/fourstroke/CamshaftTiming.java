package dev.engineeredcombustion.content.engine.fourstroke;

/**
 * The camshaft, and everything that hangs off it: cam angle, lobe placement,
 * pushrod travel and rocker swing.
 *
 * <h2>There is no camshaft clock</h2>
 * Every value here is a <b>pure function of the engine's authoritative
 * {@link CyclePosition}</b>. Nothing is integrated, nothing is stored, nothing ticks.
 * That is the single most important property of this class: a camshaft that advanced
 * its own angle would be a second clock, and two clocks in one engine drift - across
 * a reload, across a chunk unload, across a controller change, across a tick the
 * server skipped. A camshaft that is division cannot.
 */
public final class CamshaftTiming {

	/**
	 * Crank revolutions per camshaft revolution.
	 *
	 * <p><b>The defining number of a four-stroke engine</b>, and the reason the timing
	 * drive is worth making a part the player installs rather than an implementation
	 * detail: two turns of the crank, one turn of the cam, one firing per cylinder.
	 */
	public static final float TIMING_DRIVE_RATIO = 2.0F;

	/** One camshaft revolution. */
	public static final float CAM_REVOLUTION_DEGREES = 360.0F;

	/**
	 * Rocker swing at full valve lift, in degrees.
	 *
	 * <p>Shallow on purpose: a rocker that swept far would read as a lever rather than
	 * as a valve gear. But not arbitrary either - it is the angle whose tip travel is
	 * exactly the valve's:
	 *
	 * <pre>
	 * atan(VALVE_LIFT / (VALVE_CZ - ROCKER_PIVOT_Z)) = atan(1.1 / 5.4) = 11.5 degrees
	 * </pre>
	 *
	 * <p>At ten the pad lifted 0.95 while the valve fell 1.1, so the two separated by
	 * a sixth of the travel at full lift - a rocker not quite touching the valve it is
	 * supposed to be pressing, which is the sort of thing nobody sees and everybody
	 * feels.
	 */
	public static final float ROCKER_MAX_SWING_DEGREES = 11.5F;

	private CamshaftTiming() {
	}

	/**
	 * Camshaft angle for a cycle angle, in {@code [0, 360)}.
	 *
	 * <pre>
	 * camAngle = cycleAngle / 2
	 * </pre>
	 *
	 * <p><b>Zero-point convention:</b> cam 0 is engine cycle 0, which by the frozen
	 * convention is cylinder 1 at the <i>start of its compression stroke</i> - bottom
	 * dead centre, both valves just shut. That is a deliberately observable zero: a
	 * player pausing the engine at cam 0 sees every valve seated and cylinder 1's
	 * piston at the bottom of a fresh charge.
	 *
	 * <p>Half a turn per crank turn, so the cam completes exactly one revolution per
	 * 720-degree cycle by construction rather than by a counter agreeing with one.
	 */
	public static float camAngle(float cycleAngleDegrees) {
		return FourStrokeCycle.normalizeCycle(cycleAngleDegrees) / TIMING_DRIVE_RATIO;
	}

	/** The same, read straight off an authoritative position. */
	public static float camAngle(CyclePosition position) {
		return camAngle(position.angle());
	}

	/**
	 * Where a cylinder's lobe sits on the camshaft, in cam degrees.
	 *
	 * <p>Derived from the cylinder's cycle phase offset and the valve's own window, so
	 * a lobe cannot be placed anywhere the valve does not actually open. This is the
	 * answer to "how many lobes and at what phase": <b>two per cylinder</b>, at the
	 * halved centre of each valve's window, shifted by the same halved phase offset
	 * that separates the cylinders.
	 *
	 * <p>For the inline-4 that gives eight lobes on one shaft, in four pairs 90 cam
	 * degrees apart - which is 180 crank degrees, the firing interval. The lobe layout
	 * is therefore a direct picture of the firing order, and a player who looks along
	 * the camshaft is looking at 1-3-4-2.
	 *
	 * @param cylinder 0-based
	 */
	public static float lobeAngleDegrees(FourStrokeFiringOrder configuration, int cylinder, ValveTiming valve) {
		float windowCentre = valve.openAngleDegrees() + valve.durationDegrees() / 2.0F;
		// The cylinder's own angle is master + offset, so a lobe that acts at a given
		// LOCAL angle sits at master = local - offset. Halved into cam degrees.
		float masterAngleAtPeak =
			FourStrokeCycle.normalizeCycle(windowCentre - configuration.cyclePhaseOffsetDegrees(cylinder));
		return camAngle(masterAngleAtPeak);
	}

	// ------------------------------------------------------------------------
	// What the player sees move
	// ------------------------------------------------------------------------

	/**
	 * Pushrod displacement, in the same {@code 0..1} units as valve lift.
	 *
	 * <p>Identical to the valve lift because the rocker ratio is modelled as
	 * <b>1:1</b>. Real engines use 1.4:1 or thereabouts to get more valve lift than cam
	 * lift; here the two ends of the rocker move together, which is simpler to model
	 * and - more to the point - simpler to <i>read</i>: a player watching a pushrod
	 * rise by one unit sees a valve fall by one unit, and the linkage explains itself.
	 * The separate method exists so a ratio can be introduced later without every
	 * renderer changing.
	 */
	public static float pushrodLift(float cylinderCycleAngleDegrees, ValveTiming valve) {
		return valve.lift(cylinderCycleAngleDegrees);
	}

	/**
	 * Rocker arm swing in degrees, positive meaning the valve end is pressed down.
	 *
	 * <p>Just the lift mapped onto a swing, kept here so the one place a rocker angle
	 * is decided is beside the one place a valve lift is.
	 */
	public static float rockerAngleDegrees(float cylinderCycleAngleDegrees, ValveTiming valve) {
		return valve.lift(cylinderCycleAngleDegrees) * ROCKER_MAX_SWING_DEGREES;
	}

	/**
	 * Whether this cylinder is sealed - both valves seated - at its current angle.
	 *
	 * <p>True across COMPRESSION and POWER and false across the other two, which is
	 * exactly {@link FourStrokePhase#sealed()}. Derived independently here - one from
	 * valve windows, one from stroke boundaries - and checked against each other in the
	 * tests, because a design in which they disagree is a design with a gas leak in it.
	 */
	public static boolean isSealed(float cylinderCycleAngleDegrees) {
		return ValveTiming.INTAKE.lift(cylinderCycleAngleDegrees) == 0.0F
			&& ValveTiming.EXHAUST.lift(cylinderCycleAngleDegrees) == 0.0F;
	}
}
