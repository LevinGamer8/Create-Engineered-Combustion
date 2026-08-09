package dev.engineeredcombustion.content.engine;

/**
 * Geometry helpers that turn a crank angle into mechanical positions.
 *
 * <p>Kept separate from both the block entities and the renderers so that the
 * server and the client necessarily agree on the same geometry, and so that a
 * proper crank/rod/stroke model can replace the approximation below without
 * touching anything else.
 *
 * <h2>Two levels of fidelity, on purpose</h2>
 * <dl>
 * <dt>{@link #pistonPosition}</dt>
 * <dd>The gameplay/readout value. Still the sinusoidal approximation it has
 * always been, so combustion timing, the goggle overlay and every saved number
 * behave exactly as before.</dd>
 * <dt>{@link #wristPinHeight} / {@link #rodSwing}</dt>
 * <dd>The <i>visual</i> kinematics: an exact slider-crank, used only by the
 * renderers. A real connecting rod is finite, so the piston spends slightly
 * longer near bottom dead centre than near top - which is what makes the
 * animation look mechanical rather than like a sine wave.</dd>
 * </dl>
 * Both are driven by the same authoritative crank angle, so they can never
 * disagree about <i>where in the cycle</i> the engine is.
 */
public final class CrankMath {

	/**
	 * Crank radius: main journal axis to crank pin, in 1/16 blocks. Twice this
	 * is the stroke. Must match the modelled offset of the crank pin in
	 * {@code block/crank_assembly_*.json}.
	 */
	public static final float CRANK_RADIUS = 3.0F;

	/**
	 * Connecting rod length, wrist pin to crank pin, in 1/16 blocks. Must match
	 * the distance between the two eyes in {@code block/connecting_rod_*.json}.
	 */
	public static final float ROD_LENGTH = 14.5F;

	/**
	 * Height of the main journal axis inside the crankshaft block. It is the
	 * block centre because that is where Create attaches a shaft, and the
	 * flywheel rotates about the same point.
	 */
	public static final float CRANK_AXIS_HEIGHT = 8.0F;

	/**
	 * Where the piston and connecting rod models place the wrist pin. Renderers
	 * translate by the difference between this and {@link #wristPinHeight}, and
	 * pivot the rod about the result.
	 */
	public static final float WRIST_PIN_MODEL_HEIGHT = 8.0F;

	/** One block, in the 1/16 units the models are authored in. */
	private static final float BLOCK = 16.0F;

	private CrankMath() {
	}

	/**
	 * Normalized piston position for a crank angle.
	 *
	 * <p>{@code 0} = bottom dead centre, {@code 1} = top dead centre. This is the
	 * classic sinusoidal approximation {@code 0.5 - 0.5 * cos(theta)}, i.e. an
	 * infinitely long connecting rod. It is exactly symmetric, which a real
	 * crank/rod pair is not; the renderers use the exact geometry below instead.
	 */
	public static float pistonPosition(float crankAngleDegrees) {
		double radians = Math.toRadians(crankAngleDegrees);
		return (float) (0.5D - 0.5D * Math.cos(radians));
	}

	/**
	 * Exact slider-crank wrist pin height, in 1/16 blocks measured from the
	 * bottom of the <i>cylinder</i> block.
	 *
	 * <pre>
	 * y = axis - r*cos(theta) + sqrt(l^2 - r^2*sin^2(theta))   (crankshaft block)
	 * </pre>
	 * The {@code -r*cos} sign puts bottom dead centre at theta 0, matching both
	 * {@link #pistonPosition} and the crank model, which is authored with its pin
	 * at the bottom.
	 */
	public static float wristPinHeight(float crankAngleDegrees) {
		double theta = Math.toRadians(crankAngleDegrees);
		double sin = Math.sin(theta);
		double alongRod = Math.sqrt(ROD_LENGTH * ROD_LENGTH - CRANK_RADIUS * CRANK_RADIUS * sin * sin);
		return (float) (CRANK_AXIS_HEIGHT - CRANK_RADIUS * Math.cos(theta) + alongRod - BLOCK);
	}

	/**
	 * How far the connecting rod leans out of vertical, in radians, about the
	 * crankshaft's own axis.
	 *
	 * <p>{@code sin(swing) = r*sin(theta)/l} is exactly the condition that the
	 * big end lands on the crank pin, so a rod rotated by this much about the
	 * wrist pin stays attached at both ends by construction rather than by
	 * eyeballed constants. Peaks at about 12 degrees with the current
	 * {@code r/l} ratio.
	 */
	public static float rodSwing(float crankAngleDegrees) {
		double theta = Math.toRadians(crankAngleDegrees);
		return (float) Math.asin(CRANK_RADIUS * Math.sin(theta) / ROD_LENGTH);
	}
}
