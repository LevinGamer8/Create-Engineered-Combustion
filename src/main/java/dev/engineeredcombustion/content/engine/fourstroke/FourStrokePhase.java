package dev.engineeredcombustion.content.engine.fourstroke;

/**
 * The four strokes of one cylinder, and where each of them sits in the engine's
 * 720-degree cycle.
 *
 * <h2>Why the cycle starts on compression</h2>
 * The obvious convention is INTAKE at 0. This one is deliberately rotated so the
 * cycle angle agrees with the crank angle the engine <i>already</i> had before
 * Milestone 15B, and that turns out to preserve every existing constant instead
 * of re-deriving it.
 *
 * <p>{@link dev.engineeredcombustion.content.engine.CrankMath#pistonPosition} is
 * {@code 0.5 - 0.5*cos(theta)}, so the engine's crank angle puts <b>bottom dead
 * centre at 0</b> and top dead centre at 180 - not the automotive convention, and
 * the one that matters here because it is the one already on disk, in the models
 * and in the renderers. Anchoring the cycle to it gives:
 *
 * <pre>
 *   cycle   0 - 180   COMPRESSION   BDC -&gt; TDC   piston rising
 *   cycle 180 - 360   POWER         TDC -&gt; BDC   piston falling
 *   cycle 360 - 540   EXHAUST       BDC -&gt; TDC   piston rising
 *   cycle 540 - 720   INTAKE        TDC -&gt; BDC   piston falling
 * </pre>
 *
 * and therefore, for free:
 * <ul>
 * <li>{@code physicalAngle == cycleAngle % 360} exactly, so the piston geometry
 * the renderers already draw needs no new arithmetic at all;</li>
 * <li>compression top dead centre stays at <b>180</b>, the old
 * {@code EngineTuning#FIRING_ANGLE_DEGREES} unchanged;</li>
 * <li>the power stroke stays {@code [180, 360)};</li>
 * <li>the existing compression waveform, written for a crank angle with BDC at 0,
 * transfers verbatim onto {@code [0, 360)} - see
 * {@link FourStrokeCycle#gasSpringShape}.</li>
 * </ul>
 *
 * <p>The cycle is a loop, so numbering it from compression rather than from intake
 * costs nothing: COMPRESSION -&gt; POWER -&gt; EXHAUST -&gt; INTAKE -&gt; COMPRESSION is the
 * same four-stroke cycle every textbook draws, entered at a different point.
 */
public enum FourStrokePhase {

	/** Both valves shut, the charge is squeezed. The crank is resisted throughout. */
	COMPRESSION("compression", 0.0F),

	/** The charge burns and pushes the piston back down. The engine's only positive stroke. */
	POWER("power", 180.0F),

	/** Exhaust valve open, the piston pushes the burnt gas out. */
	EXHAUST("exhaust", 360.0F),

	/** Intake valve open, the piston draws a fresh charge in. */
	INTAKE("intake", 540.0F);

	/** Degrees of crankshaft rotation in one complete four-stroke cycle. */
	public static final float CYCLE_DEGREES = 720.0F;

	/** Every stroke is exactly one half-revolution, dead centre to dead centre. */
	public static final float STROKE_DEGREES = 180.0F;

	private static final FourStrokePhase[] VALUES = values();

	private final String id;
	private final float startDegrees;

	FourStrokePhase(String id, float startDegrees) {
		this.id = id;
		this.startDegrees = startDegrees;
	}

	/** Stable identifier, for translation keys and diagnostics. */
	public String getId() {
		return id;
	}

	/** Cycle angle at which this stroke begins, inclusive. */
	public float startDegrees() {
		return startDegrees;
	}

	/** Cycle angle at which this stroke ends, exclusive. */
	public float endDegrees() {
		return startDegrees + STROKE_DEGREES;
	}

	/**
	 * Which stroke a cylinder is on at a given cycle angle.
	 *
	 * <p>Derived from the angle by division rather than by comparing against piston
	 * position, and that is the whole point of the class: a piston at top dead centre
	 * is on COMPRESSION at cycle 180 and on EXHAUST at cycle 540, and no amount of
	 * looking at where the piston is can tell those apart.
	 */
	public static FourStrokePhase at(float cycleAngleDegrees) {
		float angle = FourStrokeCycle.normalizeCycle(cycleAngleDegrees);
		int index = (int) (angle / STROKE_DEGREES);
		return VALUES[Math.min(index, VALUES.length - 1)];
	}

	/**
	 * How far through this stroke the given cycle angle is, in {@code [0, 1)}.
	 *
	 * <p>0 at the dead centre the stroke starts on, approaching 1 at the one it ends
	 * on. Meaningful only for the stroke {@link #at} returns for the same angle.
	 */
	public static float strokeProgress(float cycleAngleDegrees) {
		float angle = FourStrokeCycle.normalizeCycle(cycleAngleDegrees);
		return (angle % STROKE_DEGREES) / STROKE_DEGREES;
	}

	/** Whether this stroke ends at top dead centre, i.e. the piston is rising. */
	public boolean pistonRising() {
		return this == COMPRESSION || this == EXHAUST;
	}

	/**
	 * Whether the cylinder is sealed - both valves shut - during this stroke.
	 *
	 * <p>The condition under which the trapped charge acts as a gas spring, and
	 * therefore the condition {@link FourStrokeCycle#gasSpringShape} is nonzero on.
	 */
	public boolean sealed() {
		return this == COMPRESSION || this == POWER;
	}

	/** Whether a valve is open and the piston is moving gas through it. */
	public boolean pumping() {
		return this == INTAKE || this == EXHAUST;
	}
}
