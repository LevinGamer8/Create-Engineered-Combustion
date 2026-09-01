package dev.engineeredcombustion.prototype.fourstroke;

/**
 * A whole prototype four-stroke engine: one authoritative position, N cylinders, and
 * the torque they put on the crank.
 *
 * <p><b>Prototype. Nothing in the mod depends on this.</b> It exists so the design
 * can be <i>driven</i> rather than argued about - the tests turn this engine over
 * forwards, backwards, in jumps and across saves, and measure what comes out.
 *
 * <p>Deliberately <b>not</b> a second engine. There is no inertia, friction,
 * governor, phase machine, fuel tank or wear here: those exist, correct and tested,
 * in the production {@code EngineState}, and Milestone 15B grafts this timing onto
 * them rather than replacing them. What this models is exactly the part that has to
 * change - <b>when each cylinder fires, and what the crank feels at each point in
 * the cycle</b>. {@code FourStrokeRig} adds momentum on top when a question needs it.
 */
public final class FourStrokeEngine {

	private final FourStrokeFiringOrder configuration;
	private final FourStrokeCylinderTiming[] cylinders;

	/**
	 * The engine's single authoritative position: which cycle, and where in it.
	 *
	 * <p>The direct successor to the production {@code EngineState#crankAngleDegrees}.
	 * Widening that one field from a wrapped 360-degree angle to a cycle counter plus
	 * a wrapped 720-degree angle is the whole representational change Milestone 15
	 * makes.
	 */
	private final CyclePosition position = new CyclePosition();

	/** Ticks since each cylinder last burned, or -1 for never. Production's representation. */
	private final int[] ticksSinceCombustion;

	/** Whether the charge each cylinder lit is still within its power stroke. */
	private final boolean[] burning;

	/** Bit i set when cylinder i has a Spark Plug fitted. */
	private int sparkPlugMask;

	/** Bit i set when cylinder i has a Piston Assembly fitted. */
	private int pistonMask;

	private boolean structureValid = true;
	private boolean ignitionEnabled = true;

	/** Total ignitions per cylinder since construction. What the tests count. */
	private final int[] ignitions;

	/**
	 * Firing opportunities the whole engine has passed since its last combustion.
	 *
	 * <p>The physical replacement for a wall-clock start timeout: it counts
	 * compression top dead centres that produced nothing, so it advances with the
	 * crank rather than with the clock and means the same thing at 8 RPM as at 200.
	 */
	private int missedOpportunities;

	/** Firing opportunities the whole engine has offered, ever. */
	private int opportunities;

	/**
	 * Crank degrees turned since the last successful combustion, counting travel in
	 * either direction.
	 *
	 * <p>The physical clock a start attempt should be judged against. Ticks measure
	 * how long a player has been waiting; this measures how much they have actually
	 * turned, and it means the same thing at 8 RPM as at 200 - which a tick count
	 * emphatically does not once a cylinder only fires every two revolutions.
	 */
	private float degreesSinceCombustion;

	public FourStrokeEngine(FourStrokeFiringOrder configuration) {
		this.configuration = configuration;
		this.cylinders = new FourStrokeCylinderTiming[configuration.cylinderCount()];
		for (int i = 0; i < cylinders.length; i++)
			cylinders[i] = new FourStrokeCylinderTiming(i, configuration);
		this.ticksSinceCombustion = new int[cylinders.length];
		java.util.Arrays.fill(ticksSinceCombustion, -1);
		this.burning = new boolean[cylinders.length];
		this.ignitions = new int[cylinders.length];
		this.sparkPlugMask = (1 << cylinders.length) - 1;
		this.pistonMask = (1 << cylinders.length) - 1;
		position.advance(0.0F);
	}

	/**
	 * Turns the crank by a signed amount and settles every cylinder.
	 *
	 * <p>One pass over the cylinders at one shared position, exactly as
	 * {@code EngineState#tickSimulation} does: advance, offer each cylinder its own
	 * firing opportunity, then decide which charges are still pushing.
	 *
	 * @param deltaDegrees signed crank travel. Negative turns the engine backwards
	 * @param fuelAvailable there is fuel to draw
	 * @return which cylinders ignited on this step, one bit each
	 */
	public int step(float deltaDegrees, boolean fuelAvailable) {
		position.advance(deltaDegrees);
		degreesSinceCombustion += Math.abs(deltaDegrees);

		int ignitedMask = 0;
		for (int i = 0; i < cylinders.length; i++) {
			if (ticksSinceCombustion[i] >= 0 && ticksSinceCombustion[i] < Integer.MAX_VALUE)
				ticksSinceCombustion[i]++;

			FourStrokeCylinderTiming.Event event = cylinders[i].advance(position, canIgnite(i) && fuelAvailable);
			switch (event) {
				case IGNITED -> {
					ignitedMask |= 1 << i;
					ignitions[i]++;
					ticksSinceCombustion[i] = 0;
					burning[i] = true;
					opportunities++;
					missedOpportunities = 0;
					degreesSinceCombustion = 0.0F;
				}
				case MISFIRED -> {
					burning[i] = false;
					opportunities++;
					missedOpportunities++;
				}
				default -> {
				}
			}

			// A charge pushes until the power stroke ends or the crank stops going
			// forwards - the production rule, with the stroke now identified from the
			// cycle angle rather than from a 360-degree window.
			if (burning[i] && (deltaDegrees <= 0.0F || cylinders[i].phase() != FourStrokePhase.POWER))
				burning[i] = false;
		}
		return ignitedMask;
	}

	/**
	 * Whether cylinder {@code i} could light a fresh charge if one were drawn.
	 *
	 * <p>Everything except fuel, and the split is the production one: the coil is
	 * wired to the crank, not to the fuel system, so a plug sparks whether or not
	 * there is gasoline to light.
	 */
	public boolean canIgnite(int cylinder) {
		return structureValid && ignitionEnabled && (sparkPlugMask & (1 << cylinder)) != 0
			&& (pistonMask & (1 << cylinder)) != 0;
	}

	/**
	 * Whether cylinder {@code i} is physically capable of contributing at all.
	 *
	 * <p><b>The immediate half of the active-cylinder rule.</b> Pulling a Spark Plug
	 * or a Piston Assembly makes this false on the very tick it happens, with no
	 * reference to how long ago the cylinder last fired - which is what stops the
	 * capacity readout claiming a dead cylinder is healthy for several seconds
	 * because its last combustion happened recently.
	 */
	public boolean structurallyViable(int cylinder) {
		return canIgnite(cylinder);
	}

	// ------------------------------------------------------------------------
	// Torque
	// ------------------------------------------------------------------------

	/**
	 * Net torque on the crank right now: every cylinder's combustion, gas spring and
	 * pumping, summed.
	 *
	 * <p><b>Where smoothness comes from, and the only place.</b> Nothing here says an
	 * inline-4 is smoother than a single; it falls out of adding four impulses spaced
	 * 180 degrees apart instead of one every 720. The per-cylinder combustion peak is
	 * divided by the cylinder count, matching the production
	 * {@code peakCombustionTorqueFor(target, count)}, so configurations are compared
	 * at equal average power and only their <i>ripple</i> differs.
	 */
	public float netTorque(float combustionPeak, float compressionPeak, float pumpingPeak) {
		float perCylinder = combustionPeak / cylinders.length;
		float total = 0.0F;
		for (int i = 0; i < cylinders.length; i++)
			total += cylinders[i].torque(burning[i], perCylinder, compressionPeak, pumpingPeak);
		return total;
	}

	/** Combustion torque alone, for separating the three effects in a study. */
	public float combustionTorque(float combustionPeak) {
		float perCylinder = combustionPeak / cylinders.length;
		float total = 0.0F;
		for (int i = 0; i < cylinders.length; i++)
			if (burning[i])
				total += FourStrokeCycle.combustionTorque(cylinders[i].cycleAngle()) * perCylinder;
		return total;
	}

	/** Gas spring torque alone. */
	public float compressionTorque(float compressionPeak) {
		float total = 0.0F;
		for (FourStrokeCylinderTiming cylinder : cylinders)
			total += FourStrokeCycle.gasSpringTorque(cylinder.cycleAngle()) * compressionPeak;
		return total;
	}

	// ------------------------------------------------------------------------
	// Active cylinders
	// ------------------------------------------------------------------------

	/**
	 * Which cylinders count towards generated capacity - the four-stroke form of
	 * {@code EngineState#deriveActiveCylinderMask}.
	 *
	 * <p><b>Two conditions, and they answer different questions.</b>
	 * <ul>
	 * <li><b>structural viability</b> - immediate. A plug or piston pulled out clears
	 * the bit on the tick it happens;</li>
	 * <li><b>combustion age</b> - tolerant. A healthy four-stroke cylinder waits a
	 * whole cycle between bangs, and must stay active across that wait.</li>
	 * </ul>
	 * The allowance is stated in <b>firing intervals</b> rather than revolutions, so
	 * the tolerance constant keeps its meaning for any stroke count.
	 *
	 * @param rpm       current crankshaft speed
	 * @param tolerance firing intervals of grace
	 */
	public int activeCylinderMask(float rpm, float tolerance) {
		int allowance = generationAllowanceTicks(rpm, tolerance);
		int mask = 0;
		for (int i = 0; i < cylinders.length; i++)
			if (structurallyViable(i) && ticksSinceCombustion[i] >= 0 && ticksSinceCombustion[i] <= allowance)
				mask |= 1 << i;
		return mask;
	}

	/**
	 * How many ticks a cylinder may go without firing and still count as active.
	 *
	 * <pre>
	 * ticks per cycle = 720 deg / (rpm * 360 deg/rev / 1200 ticks/min) = 2400 / rpm
	 * allowance       = tolerance * 2400 / rpm + 2
	 * </pre>
	 *
	 * <p>The {@code +2} is the production slack for a firing that lands on a tick
	 * boundary. There is deliberately no hard ceiling: the production
	 * {@code GENERATION_COMBUSTION_LIMIT_TICKS} of 60 is <i>shorter than the firing
	 * interval</i> below about 48 RPM under four-stroke, and would make a perfectly
	 * healthy cylinder blink out between its own bangs.
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

	public CyclePosition position() {
		return position;
	}

	public float cycleAngle() {
		return position.angle();
	}

	public long cycleIndex() {
		return position.cycleIndex();
	}

	/** The physical crank angle: what is saved today, and what the renderers use. */
	public float physicalAngle() {
		return position.physicalAngle();
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

	public int ignitionCount(int index) {
		return ignitions[index];
	}

	public int totalIgnitions() {
		int total = 0;
		for (int count : ignitions)
			total += count;
		return total;
	}

	/** Firing opportunities passed since the last successful combustion. */
	public int missedOpportunities() {
		return missedOpportunities;
	}

	/** Crank degrees turned since the last successful combustion, either direction. */
	public float degreesSinceCombustion() {
		return degreesSinceCombustion;
	}

	/** The same, in engine cycles - the unit a start attempt should be judged in. */
	public float cyclesSinceCombustion() {
		return degreesSinceCombustion / FourStrokeCycle.CYCLE_DEGREES;
	}

	/**
	 * Whether a start attempt should be abandoned on physical grounds.
	 *
	 * <p><b>The rule that replaces nothing, and adds what four-stroke makes newly
	 * possible.</b> The production start machine is already driven by physical
	 * quantities - a start ends when the crank comes to rest, when the speed falls
	 * below {@code START_RPM}, or when there is no longer fuel or a spark - and its
	 * one wall-clock number, {@code START_ATTEMPT_TIMEOUT_TICKS}, counts ticks since
	 * the engine could ignite <i>at all</i> rather than ticks since it last caught,
	 * so it never runs while a player is cranking a fuelled engine.
	 *
	 * <p>What four-stroke adds is a way to turn a great deal and catch nothing while
	 * still being able to ignite on paper: a crank rocked back and forth across
	 * compression clears the charge every time, so the engine keeps offering
	 * opportunities and keeps declining them. This is the rule for that, and it is
	 * expressed in crank travel so it means the same at any speed.
	 *
	 * @param lapseCycles complete engine cycles of travel without a combustion
	 */
	public boolean startAttemptLapsed(float lapseCycles) {
		return cyclesSinceCombustion() >= lapseCycles;
	}

	/** Firing opportunities offered since construction, taken or not. */
	public int opportunities() {
		return opportunities;
	}

	public int ticksSinceCombustion(int cylinder) {
		return ticksSinceCombustion[cylinder];
	}

	public boolean isBurning(int cylinder) {
		return burning[cylinder];
	}

	// ------------------------------------------------------------------------
	// World mutations the tests need
	// ------------------------------------------------------------------------

	public void removeSparkPlug(int cylinder) {
		sparkPlugMask &= ~(1 << cylinder);
	}

	public void removePiston(int cylinder) {
		pistonMask &= ~(1 << cylinder);
	}

	public void setIgnitionEnabled(boolean enabled) {
		this.ignitionEnabled = enabled;
	}

	public void setStructureValid(boolean valid) {
		this.structureValid = valid;
	}

	// ------------------------------------------------------------------------
	// Save / reload
	// ------------------------------------------------------------------------

	/**
	 * Everything a world save must carry, and nothing that can be re-derived.
	 *
	 * <p>The physical crank angle is absent because it is {@code angle % 360}; the
	 * per-cylinder angles are absent because they are the master shifted by a
	 * constant. What is here is the irreducible state: which cycle, where in it, and
	 * which cylinders are holding a charge.
	 *
	 * @param cycleIndex     which cycle - the counter half of the position
	 * @param cycleAngle     where in it, in {@code [0, 720)}
	 * @param armedMask      one bit per cylinder: a charge is inducted and unburnt
	 * @param lastFiredCycle per cylinder, the last opportunity taken - the event keys
	 */
	public record Save(long cycleIndex, float cycleAngle, int armedMask, long[] lastFiredCycle) {
	}

	public Save save() {
		int armedMask = 0;
		long[] fired = new long[cylinders.length];
		for (int i = 0; i < cylinders.length; i++) {
			if (cylinders[i].isArmed())
				armedMask |= 1 << i;
			fired[i] = cylinders[i].lastFiredCycle();
		}
		return new Save(position.cycleIndex(), position.angle(), armedMask, fired);
	}

	/**
	 * Restores a saved engine. Cylinder positions are re-derived from the master
	 * position rather than restored, which is why a reload cannot land them on a
	 * different stroke from the one they were saved on.
	 */
	public void restore(Save save) {
		position.set(new CyclePosition(save.cycleIndex(), save.cycleAngle()));
		for (int i = 0; i < cylinders.length; i++)
			cylinders[i].restore((save.armedMask() & (1 << i)) != 0, save.lastFiredCycle()[i]);
		position.advance(0.0F);
		for (FourStrokeCylinderTiming cylinder : cylinders)
			cylinder.advance(position, false);
	}

	/**
	 * Arms every cylinder, as an engine coming to rest would.
	 *
	 * <p>A stopped engine has been sitting with mixture in the bore, so a cylinder
	 * that is cranked over gets one free induction. Physically honest, and it bounds
	 * the crank travel to the first bang - see the design document's starting
	 * section.
	 */
	public void armAsIfRested() {
		for (FourStrokeCylinderTiming cylinder : cylinders)
			cylinder.restore(true, cylinder.lastFiredCycle());
	}
}
