package dev.engineeredcombustion.client;

import dev.engineeredcombustion.content.engine.fourstroke.CamshaftTiming;

/**
 * Where the valve gear's moving parts sit, in the same model units the geometry
 * that carries them is authored in.
 *
 * <h2>Why these numbers are here rather than in the renderers</h2>
 * The Crankshaft draws the camshaft, the Cylinder draws the pushrods, rockers and
 * valves, and {@code tools/generate_engine_models.py} draws the cradle, the
 * tunnels and the shaft they all run in. Three files, one mechanism - and the day
 * any two of them disagree about where the cam sits, a pushrod stops touching a
 * lobe and nobody can say which of the three is wrong. So the constants live once,
 * here, with the generator's own names, and the generator's header says so.
 *
 * <h2>Everything turns on the block centre</h2>
 * Create's {@code rotateCentered} pivots about the block's own centre and nothing
 * else. So the parts that turn - the camshaft, the rockers - are <b>authored</b>
 * about that centre and translated onto their real axis afterwards, which is why
 * the offsets below are differences from 8 rather than positions.
 */
public final class EngineValvetrain {

	/** One model unit, as a fraction of a block. Every offset here is in units. */
	public static final float UNIT = 1.0F / 16.0F;

	/** The camshaft's axis in the Crankshaft block: low on the intake flank. */
	public static final float CAM_Y = 4.5F;
	public static final float CAM_Z = -0.9F;

	/**
	 * The timing drive gear's axis, in the Crankshaft block.
	 *
	 * <p>Straight up from the camshaft at the mesh distance, which is
	 * {@code CAM_R + DRIVE_R} apart because the two wheels touch, and which puts it
	 * at very nearly the crankshaft's own height. It is the gear the crankshaft
	 * turns, through the case; what a player sees is a wheel driving one twice its
	 * size, which is the whole of the 2:1.
	 */
	public static final float TIMING_CAM_R = 3.0F;
	public static final float TIMING_DRIVE_R = 1.5F;
	public static final float TIMING_DRIVE_Y = CAM_Y + TIMING_CAM_R + TIMING_DRIVE_R;
	public static final float TIMING_DRIVE_Z = CAM_Z;

	/** Where a cylinder's two valves stand across the bore. */
	public static final float[] VALVE_X = { 5.0F, 11.0F };

	/** The rocker shaft, in the Cylinder block: above the head, below the manifold. */
	public static final float ROCKER_PIVOT_Y = 19.9F;
	public static final float ROCKER_PIVOT_Z = 0.2F;

	/** How far a valve travels off its seat at full lift. */
	public static final float VALVE_LIFT = 1.1F;

	private EngineValvetrain() {
	}

	/**
	 * How far cylinder {@code index}'s valve at {@code VALVE_X[index]} sits from the
	 * block centre, in blocks.
	 */
	public static float valveOffset(int valve) {
		return (VALVE_X[valve] - 8.0F) * UNIT;
	}

	/** The camshaft's axis, as an offset from the block centre, in blocks. */
	public static float camOffsetY() {
		return (CAM_Y - 8.0F) * UNIT;
	}

	public static float camOffsetZ() {
		return (CAM_Z - 8.0F) * UNIT;
	}

	/** The timing drive gear's axis, as an offset from the block centre, in blocks. */
	public static float timingDriveOffsetY() {
		return (TIMING_DRIVE_Y - 8.0F) * UNIT;
	}

	public static float timingDriveOffsetZ() {
		return (TIMING_DRIVE_Z - 8.0F) * UNIT;
	}

	/** The rocker shaft, as an offset from the block centre, in blocks. */
	public static float rockerOffsetY() {
		return (ROCKER_PIVOT_Y - 8.0F) * UNIT;
	}

	public static float rockerOffsetZ() {
		return (ROCKER_PIVOT_Z - 8.0F) * UNIT;
	}

	/**
	 * How far a valve is off its seat, in blocks, for a cylinder at this point in its
	 * cycle.
	 *
	 * <p>Read straight off {@code ValveTiming}, which is the simulation's own answer
	 * and is shared by both sides - so a valve is never drawn from a client-side
	 * approximation of when it should be open.
	 *
	 * @param valve 0 for the intake, 1 for the exhaust
	 */
	public static float liftOf(int valve, float cylinderCycleAngleDegrees) {
		return valveTiming(valve).lift(cylinderCycleAngleDegrees) * VALVE_LIFT * UNIT;
	}

	/**
	 * How far the rocker for that valve has swung, in radians. Positive presses the
	 * valve down, so the renderer negates it about the shaft.
	 */
	public static float rockerSwing(int valve, float cylinderCycleAngleDegrees) {
		return (float) Math.toRadians(
			CamshaftTiming.rockerAngleDegrees(cylinderCycleAngleDegrees, valveTiming(valve)));
	}

	private static dev.engineeredcombustion.content.engine.fourstroke.ValveTiming valveTiming(int valve) {
		return valve == 0 ? dev.engineeredcombustion.content.engine.fourstroke.ValveTiming.INTAKE
			: dev.engineeredcombustion.content.engine.fourstroke.ValveTiming.EXHAUST;
	}
}
