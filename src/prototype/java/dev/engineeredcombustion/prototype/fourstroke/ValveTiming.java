package dev.engineeredcombustion.prototype.fourstroke;

/**
 * When each valve is open, and how far.
 *
 * <p><b>Prototype. Nothing in the mod depends on this.</b> It lives in
 * {@code src/prototype/java}, which only the {@code simulationTest} source set
 * compiles, so the production jar cannot contain it. See
 * {@code docs/milestone-15-valvetrain-design.md}.
 *
 * <h2>One window per valve, and nothing else</h2>
 * A valve is open for exactly one of the four strokes and shut for the other three.
 * That is the whole timing model, and it is deliberately the whole of it: real
 * engines open before the dead centre and close after it, and that overlap is worth
 * having <i>only</i> once there is gas to model flowing through it. There is not.
 *
 * <p>The window is stored as an angle and a duration rather than as a
 * {@link FourStrokePhase}, so adding overlap later is a change to two numbers and
 * not a change to the shape of anything. With the defaults below the two windows
 * are exactly adjacent and both reach zero lift at 540 degrees, so no overlap
 * exists and the tests prove it.
 */
public enum ValveTiming {

	/**
	 * Draws the fresh charge in. Open across the INTAKE stroke, {@code [540, 720)}.
	 *
	 * <p>Its closing point is also the point at which a cylinder becomes able to fire
	 * in {@link FourStrokeCycle}: the charge is inducted during this window and
	 * compressed after it, which is why {@code ARMING_ANGLE_DEGREES} is the start of
	 * it and not an independent number.
	 */
	INTAKE(FourStrokePhase.INTAKE),

	/** Pushes the burnt charge out. Open across the EXHAUST stroke, {@code [360, 540)}. */
	EXHAUST(FourStrokePhase.EXHAUST);

	private final float openAngleDegrees;
	private final float durationDegrees;

	ValveTiming(FourStrokePhase stroke) {
		this.openAngleDegrees = stroke.startDegrees();
		this.durationDegrees = FourStrokePhase.STROKE_DEGREES;
	}

	/** Cycle angle at which this valve begins to lift. */
	public float openAngleDegrees() {
		return openAngleDegrees;
	}

	/** How many crank degrees the valve is off its seat for. */
	public float durationDegrees() {
		return durationDegrees;
	}

	/** Cycle angle at which the valve is back on its seat. */
	public float closeAngleDegrees() {
		return FourStrokeCycle.normalizeCycle(openAngleDegrees + durationDegrees);
	}

	/** The stroke this valve is open across. */
	public FourStrokePhase stroke() {
		return FourStrokePhase.at(openAngleDegrees);
	}

	/**
	 * How far this valve is off its seat at a given cycle angle: {@code 0} shut,
	 * {@code 1} fully lifted.
	 *
	 * @param cycleAngleDegrees <b>this cylinder's own</b> cycle angle, not the
	 *                          engine's. A cylinder's valves follow its own place in
	 *                          the cycle, which is the master angle shifted by that
	 *                          cylinder's phase offset
	 */
	public float lift(float cycleAngleDegrees) {
		float since = FourStrokeCycle.normalizeCycle(cycleAngleDegrees - openAngleDegrees);
		if (since >= durationDegrees)
			return 0.0F;
		return liftCurve(since / durationDegrees);
	}

	/** Whether the valve is off its seat at all. */
	public boolean isOpen(float cycleAngleDegrees) {
		return lift(cycleAngleDegrees) > 0.0F;
	}

	/**
	 * The cam profile, as a fraction of full lift for a fraction of the way through
	 * the open window.
	 *
	 * <pre>
	 * lift(t) = (1 - cos(2*pi*t)) / 2
	 * </pre>
	 *
	 * <p>A raised cosine, chosen for one property above all: <b>both its value and
	 * its slope are zero at each end</b>. A valve that snapped open would read as a
	 * flickering block rather than a mechanism, and one that merely reached zero with
	 * a non-zero slope - a triangle, or a half-sine - would visibly kink as it met
	 * the seat. This meets the seat the way a cam does.
	 *
	 * <p>Peak lift falls at the middle of the stroke, which for the intake is
	 * cylinder-local 630 degrees and for the exhaust 450. It is emphatically not a
	 * gas-flow model: no real cam is symmetric, and a real one is chosen by what the
	 * port does. This is the shape a player reads as "a cam is pushing that open".
	 *
	 * @param progress fraction through the open window, in {@code [0, 1)}
	 */
	public static float liftCurve(float progress) {
		if (progress <= 0.0F || progress >= 1.0F)
			return 0.0F;
		return (float) ((1.0D - Math.cos(2.0D * Math.PI * progress)) / 2.0D);
	}
}
