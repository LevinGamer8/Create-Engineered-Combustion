package dev.engineeredcombustion.prototype.fourstroke;

/**
 * A whole prototype four-stroke engine: one master cycle angle, N cylinders, and the
 * torque they put on the crank.
 *
 * <p><b>Prototype. Nothing in the mod depends on this.</b> It exists so the design
 * can be <i>driven</i> rather than only argued about - the tests turn this engine
 * over, forwards, backwards and in jumps, and measure what comes out.
 *
 * <p>Deliberately <b>not</b> a simulation: there is no inertia, no friction, no
 * governor, no phase machine, no fuel tank, no wear. Those all exist, correct and
 * tested, in the production {@code EngineState}, and Milestone 15B's job is to graft
 * this timing onto them rather than to grow a second engine here. What this class
 * models is exactly the part that has to change: <b>when each cylinder fires, and
 * what the crank feels at each point in the cycle</b>.
 */
public final class FourStrokeEngine {

	private final FourStrokeFiringOrder configuration;
	private final FourStrokeCylinderTiming[] cylinders;

	/**
	 * The engine's single authoritative angle, in {@code [0, 720)}.
	 *
	 * <p>The direct successor to the production {@code EngineState#crankAngleDegrees}:
	 * one number for the whole engine, from which every cylinder's angle, stroke and
	 * piston position is derived. Widening it from 360 to 720 is the single
	 * representational change Milestone 15 makes.
	 */
	private float masterCycleAngle;

	/**
	 * Ticks since each cylinder last burned a charge, or {@code -1} for never - the
	 * same representation and meaning as the production
	 * {@code EngineState#ticksSinceCombustion}, so the active-cylinder rule can be
	 * exercised against a four-stroke firing interval.
	 */
	private final int[] ticksSinceCombustion;

	/** Whether the charge each cylinder lit is still within its power stroke. */
	private final boolean[] burning;

	/** Total ignitions per cylinder since construction. What the tests count. */
	private final int[] ignitions;

	public FourStrokeEngine(FourStrokeFiringOrder configuration) {
		this.configuration = configuration;
		this.cylinders = new FourStrokeCylinderTiming[configuration.cylinderCount()];
		for (int i = 0; i < cylinders.length; i++)
			cylinders[i] = new FourStrokeCylinderTiming(i, configuration);
		this.ticksSinceCombustion = new int[cylinders.length];
		java.util.Arrays.fill(ticksSinceCombustion, -1);
		this.burning = new boolean[cylinders.length];
		this.ignitions = new int[cylinders.length];
		// Seed every cylinder's angle without pretending the crank moved.
		step(0.0F, false);
	}

	/**
	 * Turns the crank by {@code deltaDegrees} and settles every cylinder.
	 *
	 * <p>One pass over the cylinders, at one shared angle, exactly as
	 * {@code EngineState#tickSimulation} does. The order of what happens inside is
	 * the production order too: advance, offer the firing opportunity, then decide
	 * which charges are still pushing.
	 *
	 * @param deltaDegrees signed crank travel. Negative turns the engine backwards
	 * @param canIgnite    spark, ignition and fuel are all available
	 * @return which cylinders ignited on this step, one bit each
	 */
	public int step(float deltaDegrees, boolean canIgnite) {
		masterCycleAngle = FourStrokeCycle.normalizeCycle(masterCycleAngle + deltaDegrees);

		int ignitedMask = 0;
		for (int i = 0; i < cylinders.length; i++) {
			if (ticksSinceCombustion[i] >= 0 && ticksSinceCombustion[i] < Integer.MAX_VALUE)
				ticksSinceCombustion[i]++;

			FourStrokeCylinderTiming.Event event = cylinders[i].advance(masterCycleAngle, deltaDegrees, canIgnite);
			if (event == FourStrokeCylinderTiming.Event.IGNITED) {
				ignitedMask |= 1 << i;
				ignitions[i]++;
				ticksSinceCombustion[i] = 0;
				burning[i] = true;
			} else if (event == FourStrokeCylinderTiming.Event.MISFIRED) {
				burning[i] = false;
			}

			// A charge pushes until the power stroke ends or the crank stops going
			// forwards - the production rule, unchanged, with the stroke now identified
			// from the cycle angle rather than from a 360-degree window.
			if (burning[i] && (deltaDegrees <= 0.0F || cylinders[i].phase() != FourStrokePhase.POWER))
				burning[i] = false;
		}
		return ignitedMask;
	}

	/**
	 * Net torque on the crank right now: every cylinder's combustion, gas spring and
	 * pumping, summed.
	 *
	 * <p><b>Where smoothness comes from, and the only place.</b> Nothing here says an
	 * inline-4 is smoother than a single; it falls out of adding four impulses spaced
	 * 180 degrees apart instead of one every 720. The per-cylinder combustion peak is
	 * divided by the cylinder count, matching the production
	 * {@code peakCombustionTorqueFor(target, count)}, so the configurations are
	 * compared at equal average power and only their <i>ripple</i> differs.
	 */
	public float netTorque(float combustionPeak, float compressionPeak, float pumpingPeak) {
		float perCylinder = combustionPeak / cylinders.length;
		float total = 0.0F;
		for (int i = 0; i < cylinders.length; i++)
			total += cylinders[i].torque(burning[i], perCylinder, compressionPeak, pumpingPeak);
		return total;
	}

	/**
	 * Which cylinders count towards generated capacity, one bit each - the
	 * four-stroke form of {@code EngineState#deriveActiveCylinderMask}.
	 *
	 * <p>The rule is unchanged in meaning and changed in unit: a cylinder is active if
	 * it burned a charge within {@code tolerance} <b>firing intervals</b>, where a
	 * firing interval is now one 720-degree cycle rather than one revolution. Stating
	 * the allowance in firing intervals rather than revolutions is what makes it
	 * survive the change - the constant keeps meaning "tolerate one missed firing"
	 * whatever the engine's stroke count.
	 *
	 * @param rpm       current crankshaft speed
	 * @param tolerance firing intervals of grace. The production value is 2.5
	 */
	public int activeCylinderMask(float rpm, float tolerance) {
		int allowance = generationAllowanceTicks(rpm, tolerance);
		int mask = 0;
		for (int i = 0; i < cylinders.length; i++)
			if (ticksSinceCombustion[i] >= 0 && ticksSinceCombustion[i] <= allowance)
				mask |= 1 << i;
		return mask;
	}

	/**
	 * How many ticks a cylinder may go without firing and still count as active.
	 *
	 * <pre>
	 * ticks per cycle = 720 deg / (rpm * 360 deg/rev / 1200 ticks/min)
	 *                 = 2400 / rpm
	 * allowance       = tolerance * 2400 / rpm + 2
	 * </pre>
	 *
	 * <p>The {@code +2} is the production slack for a firing that lands on a tick
	 * boundary. There is deliberately no hard ceiling here: the production
	 * {@code GENERATION_COMBUSTION_LIMIT_TICKS} of 60 exists to stop a crawling
	 * engine claiming generation for ever, but under four-stroke it is <i>shorter
	 * than the firing interval</i> below about 40 RPM and would make a perfectly
	 * healthy engine's mask flicker. See the design document.
	 */
	public static int generationAllowanceTicks(float rpm, float tolerance) {
		float speed = Math.abs(rpm);
		if (speed < 1.0F)
			return Integer.MAX_VALUE;
		return Math.round(tolerance * (2.0F * 1200.0F / speed)) + 2;
	}

	// ------------------------------------------------------------------------
	// Reads
	// ------------------------------------------------------------------------

	public float masterCycleAngle() {
		return masterCycleAngle;
	}

	/** The physical crank angle: what is saved today, and what the renderers use. */
	public float masterPhysicalAngle() {
		return FourStrokeCycle.physicalAngle(masterCycleAngle);
	}

	public FourStrokeCylinderTiming cylinder(int index) {
		return cylinders[index];
	}

	public int cylinderCount() {
		return cylinders.length;
	}

	public FourStrokeFiringOrder configuration() {
		return configuration;
	}

	/** How many charges cylinder {@code index} has burned since construction. */
	public int ignitionCount(int index) {
		return ignitions[index];
	}

	public int totalIgnitions() {
		int total = 0;
		for (int count : ignitions)
			total += count;
		return total;
	}

	// ------------------------------------------------------------------------
	// Save / reload
	// ------------------------------------------------------------------------

	/**
	 * Everything a world save must carry to bring this engine back on the same
	 * stroke - and nothing that can be re-derived from it.
	 *
	 * @param masterCycleAngle the one authoritative angle. Replaces today's saved
	 *                         360-degree crank angle; the physical angle is
	 *                         {@code % 360} of it, so saving both would be saving one
	 *                         fact twice
	 * @param armedMask        one bit per cylinder: a charge is inducted and unburnt.
	 *                         Genuinely per-cylinder, genuinely not derivable
	 * @param completedCycles  cycle counter of cylinder 0, for event identity
	 */
	public record Save(float masterCycleAngle, int armedMask, long completedCycles) {
	}

	public Save save() {
		int armedMask = 0;
		for (int i = 0; i < cylinders.length; i++)
			if (cylinders[i].isArmed())
				armedMask |= 1 << i;
		return new Save(masterCycleAngle, armedMask, cylinders[0].completedCycles());
	}

	/**
	 * Restores a saved engine. The cylinders' own angles are re-derived from the
	 * master angle, not restored, which is why a reload cannot land them on a
	 * different stroke from the one they were saved on.
	 */
	public void restore(Save save) {
		masterCycleAngle = FourStrokeCycle.normalizeCycle(save.masterCycleAngle());
		for (int i = 0; i < cylinders.length; i++)
			cylinders[i].restore((save.armedMask() & (1 << i)) != 0, save.completedCycles());
		step(0.0F, false);
	}
}
