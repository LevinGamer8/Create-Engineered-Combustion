package dev.engineeredcombustion.prototype.fourstroke;

/**
 * One cylinder's place in the engine cycle, and the two independent things that
 * decide whether it may burn a charge.
 *
 * <p><b>Prototype. Nothing in the mod depends on this.</b>
 *
 * <h2>Three questions, kept apart</h2>
 * The architecture must be able to answer each of these for every cylinder without
 * deriving it from either of the others:
 * <ol>
 * <li><b>Where is the piston?</b> {@link #physicalAngle()} - a function of the crank
 * throw, repeating every 360 degrees;</li>
 * <li><b>Which stroke is it on?</b> {@link #phase()} - a function of the cycle
 * angle, repeating every 720;</li>
 * <li><b>May it fire?</b> {@link #isArmed()} and the event key - neither of which is
 * a function of position at all.</li>
 * </ol>
 *
 * <h2>Two guards, and why both</h2>
 * <dl>
 * <dt>The arming latch - <i>is there a charge?</i></dt>
 * <dd>Set when the cylinder forward-crosses the start of its intake stroke,
 * consumed at compression top dead centre whether or not it lights. It is the
 * physical statement that a cylinder cannot burn what it has not inhaled, and it is
 * what makes a misfire cost a whole cycle.</dd>
 *
 * <dt>The event key - <i>has this opportunity already been taken?</i></dt>
 * <dd>{@code (cylinderIndex, firingCycleIndex)} names one firing opportunity
 * uniquely. A cylinder may take each key once. This is the guard that makes a
 * duplicate <i>detectable</i> rather than merely improbable, and it is what holds
 * when a crank is rocked, reversed, saved mid-stroke or driven at absurd speed.</dd>
 * </dl>
 * Either alone closes the rocking exploit. Both are kept because they fail in
 * different directions: the latch is physics and could be relaxed for gameplay, the
 * key is bookkeeping and must never be. Neither allocates - the key is compared as
 * two primitives against a {@code long} field.
 */
public final class FourStrokeCylinderTiming {

	/** No opportunity has been taken yet. Below any real cycle index. */
	public static final long NO_EVENT = Long.MIN_VALUE;

	private final int index;
	private final FourStrokeFiringOrder configuration;

	/** This cylinder's own view of the engine's position. Reused, never reallocated. */
	private final CyclePosition position = new CyclePosition();

	/** A charge has been drawn and not yet burned. */
	private boolean armed;

	/** The cycle index of the last opportunity this cylinder actually took. */
	private long lastFiredCycle = NO_EVENT;

	/** What happened to this cylinder during one step. */
	public enum Event {

		/** Nothing of note. */
		NONE,

		/** The intake stroke began: a fresh charge is now available to burn. */
		ARMED,

		/** The cylinder passed compression top dead centre holding a charge, and lit it. */
		IGNITED,

		/**
		 * The cylinder reached compression top dead centre but could not light: no
		 * spark, no fuel, no charge drawn, or this opportunity was already taken. The
		 * charge is pushed back out, so a misfire costs a whole cycle.
		 */
		MISFIRED
	}

	public FourStrokeCylinderTiming(int index, FourStrokeFiringOrder configuration) {
		this.index = index;
		this.configuration = configuration;
	}

	/**
	 * Advances this cylinder to the engine's new position and reports what happened.
	 *
	 * <p><b>At most one ignition per call, at any speed.</b> A step longer than a
	 * whole cycle crosses several of this cylinder's firing opportunities; only the
	 * last is taken, and the earlier ones are lost rather than accumulated. That is
	 * a deliberate fail-closed choice - see
	 * {@code docs/milestone-15-four-stroke-design.md} section 15A.1 - and it needs
	 * more than 2400 RPM to become reachable at all.
	 *
	 * @param master    the engine's position after the step
	 * @param canIgnite spark plug present, ignition live and fuel available. Exactly
	 *                  the production {@code canIgniteNewCharge} gate
	 */
	public Event advance(CyclePosition master, boolean canIgnite) {
		master.shiftedBy(configuration.cyclePhaseOffsetDegrees(index), position);

		// BACKWARDS FIRST. A crank wound backwards past either timing point throws the
		// charge away: the intake stroke it was drawn on has been undone, or the piston
		// has been dragged back down through top dead centre. This is fidelity rather
		// than protection - the event key below is what makes the rocking exploit
		// impossible - but it is what stops a cylinder hoarding a charge through a
		// hand-crank wobble and firing the instant it is nudged into a fresh cycle.
		if (position.crossedBackward(FourStrokeCycle.ARMING_ANGLE_DEGREES)
			|| position.crossedBackward(FourStrokeCycle.IGNITION_ANGLE_DEGREES))
			armed = false;

		Event event = Event.NONE;
		if (position.crossedForward(FourStrokeCycle.ARMING_ANGLE_DEGREES)) {
			armed = true;
			event = Event.ARMED;
		}

		if (position.crossedForward(FourStrokeCycle.IGNITION_ANGLE_DEGREES)) {
			long opportunity = position.crossingCycleIndex(FourStrokeCycle.IGNITION_ANGLE_DEGREES);
			boolean fresh = opportunity != lastFiredCycle;
			boolean lit = armed && canIgnite && fresh;
			armed = false;
			if (lit)
				lastFiredCycle = opportunity;
			event = lit ? Event.IGNITED : Event.MISFIRED;
		}
		return event;
	}

	// ------------------------------------------------------------------------
	// Reads
	// ------------------------------------------------------------------------

	/** This cylinder's own cycle angle, in {@code [0, 720)}. */
	public float cycleAngle() {
		return position.angle();
	}

	/** Which cycle this cylinder is in. */
	public long cycleIndex() {
		return position.cycleIndex();
	}

	/**
	 * This cylinder's physical crank angle, in {@code [0, 360)}: where its piston is.
	 *
	 * <p>What the renderers want. Folded out of the cycle angle rather than tracked
	 * separately, so the piston can never disagree with the stroke.
	 */
	public float physicalAngle() {
		return position.physicalAngle();
	}

	/** Which of the four strokes this cylinder is on. */
	public FourStrokePhase phase() {
		return position.phase();
	}

	/** Whether a charge is inducted and not yet burned. */
	public boolean isArmed() {
		return armed;
	}

	/** The cycle index of the last opportunity taken, or {@link #NO_EVENT}. */
	public long lastFiredCycle() {
		return lastFiredCycle;
	}

	public int index() {
		return index;
	}

	/**
	 * Total torque this cylinder puts on the crank.
	 *
	 * <p>Three separate physical effects, each nonzero on a different part of the
	 * cycle - which is the whole content of "this is a four-stroke".
	 */
	public float torque(boolean burning, float combustionPeak, float compressionPeak, float pumpingPeak) {
		float angle = position.angle();
		float total = FourStrokeCycle.gasSpringTorque(angle) * compressionPeak
			+ FourStrokeCycle.pumpingTorque(angle) * pumpingPeak;
		if (burning)
			total += FourStrokeCycle.combustionTorque(angle) * combustionPeak;
		return total;
	}

	// ------------------------------------------------------------------------
	// Save / reload
	// ------------------------------------------------------------------------

	/**
	 * Restores the state a world save must carry for this cylinder.
	 *
	 * <p>The position is <b>not</b> among them: it is re-derived from the engine's
	 * one saved position on the next step, exactly as it is derived on every other
	 * step. Persisting it per cylinder would put one fact on disk four times, which
	 * is how the copies come back disagreeing.
	 */
	public void restore(boolean armed, long lastFiredCycle) {
		this.armed = armed;
		this.lastFiredCycle = lastFiredCycle;
	}
}
