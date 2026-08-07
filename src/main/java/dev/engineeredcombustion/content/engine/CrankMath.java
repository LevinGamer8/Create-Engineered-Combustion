package dev.engineeredcombustion.content.engine;

/**
 * Geometry helpers that turn a crank angle into mechanical positions.
 *
 * <p>Kept separate from both the block entities and the renderers so that the
 * server and the client necessarily agree on the same geometry, and so that a
 * proper crank/rod/stroke model can replace the approximation below without
 * touching anything else.
 */
public final class CrankMath {

	private CrankMath() {
	}

	/**
	 * Normalized piston position for a crank angle.
	 *
	 * <p>{@code 0} = bottom dead centre, {@code 1} = top dead centre. This is the
	 * classic sinusoidal approximation {@code 0.5 - 0.5 * cos(theta)}, i.e. an
	 * infinitely long connecting rod. It is exactly symmetric, which a real
	 * crank/rod pair is not; that refinement belongs to a later milestone.
	 */
	public static float pistonPosition(float crankAngleDegrees) {
		double radians = Math.toRadians(crankAngleDegrees);
		return (float) (0.5D - 0.5D * Math.cos(radians));
	}
}
