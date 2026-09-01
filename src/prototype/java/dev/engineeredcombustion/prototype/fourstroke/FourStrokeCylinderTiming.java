package dev.engineeredcombustion.prototype.fourstroke;

/**
 * One cylinder's place in the engine cycle, and the latch that decides when it may
 * burn a charge.
 *
 * <p><b>Prototype. Nothing in the mod depends on this.</b>
 *
 * <h2>What this holds that the production engine does not</h2>
 * The production {@code EngineState} keeps one crank angle for the whole engine and
 * derives everything per-cylinder from it with a phase offset. That stays true here
 * - there is still exactly one authoritative angle, passed in to {@link #advance} -
 * and the two things added are the ones a four-stroke genuinely needs and a
 * two-stroke genuinely does not:
 * <ul>
 * <li>{@link #isArmed()} - whether this cylinder has drawn a charge it has not yet
 * burned. Without it, rocking the crank across the ignition point is free power;</li>
 * <li>{@link #completedCycles()} - a signed count of whole cycles, which gives every
 * ignition a stable identity for diagnostics and for save/reload, and which counts
 * <i>down</i> when the engine is turned backwards.</li>
 * </ul>
 *
 * <h2>Why the latch is where duplicate-event prevention lives</h2>
 * An engine can be turned by anything Create attaches to it, in either direction,
 * and a player with a Hand Crank can oscillate it a degree at a time across any
 * angle they like. A crossing test alone fires on every forward crossing, so
 * {@code +1, -1, +1, -1} across the ignition point is an infinite fuel-free bang
 * generator. The latch makes that impossible without a special case, because it
 * encodes the actual physics: <b>you cannot burn a charge you have not inducted</b>.
 * Re-arming happens at the start of the intake stroke, 360 degrees away from
 * ignition on the far side of the cycle, so two ignitions are always separated by a
 * full 720 degrees of forward travel.
 */
public final class FourStrokeCylinderTiming {

	private final int index;
	private final FourStrokeFiringOrder configuration;

	/** This cylinder's own cycle angle, in {@code [0, 720)}. */
	private float cycleAngle;

	/** A charge has been drawn and not yet burned. */
	private boolean armed;

	/**
	 * Whole cycles completed, signed. Increments on a forward wrap through 720 and
	 * decrements on a backward wrap through 0, so an engine rocked back and forth
	 * across the wrap does not accumulate phantom cycles.
	 */
	private long completedCycles;

	/** What happened to this cylinder during one step. */
	public enum Event {

		/** Nothing of note. */
		NONE,

		/** The intake stroke began: a fresh charge is now available to burn. */
		ARMED,

		/**
		 * The cylinder passed compression top dead centre holding a charge, with a
		 * spark, turning forwards. This is the ignition event.
		 */
		IGNITED,

		/**
		 * The cylinder passed compression top dead centre but could not light: no
		 * spark, no fuel, or it never drew a charge. The charge, if there was one, is
		 * pushed back out - the latch clears, so a misfire costs a whole cycle exactly
		 * as it does on a real engine.
		 */
		MISFIRED
	}

	public FourStrokeCylinderTiming(int index, FourStrokeFiringOrder configuration) {
		this.index = index;
		this.configuration = configuration;
	}

	/**
	 * Advances this cylinder to a new master cycle angle and reports what happened.
	 *
	 * <p>Order matters and is deliberate: arming is tested <i>before</i> ignition, so
	 * a single step long enough to cross both - which needs more than 360 degrees in
	 * one tick, i.e. an external network past 1200 RPM - arms and then fires rather
	 * than silently losing the charge.
	 *
	 * @param masterCycleAngle the engine's cycle angle after the step, in {@code [0, 720)}
	 * @param deltaDegrees     signed crank travel during the step
	 * @param canIgnite        a spark plug is present, ignition is live and there is
	 *                         fuel to draw. Exactly the production
	 *                         {@code canIgniteNewCharge} gate, unchanged
	 */
	public Event advance(float masterCycleAngle, float deltaDegrees, boolean canIgnite) {
		float previous = cycleAngle;
		cycleAngle = FourStrokeCycle.normalizeCycle(
			masterCycleAngle + configuration.cyclePhaseOffsetDegrees(index));
		countWrap(previous, deltaDegrees);

		// BACKWARDS FIRST, and this ordering is load-bearing. A crank being wound
		// backwards past either timing point throws the charge away: the intake stroke
		// it was drawn on has been undone, or the piston has been dragged back down
		// through top dead centre. Without this the latch is trivially defeated - arm
		// at 540, wind back through 180 (which does not fire), then nudge forward two
		// degrees across 180 and collect a free bang, for ever. The prototype's own
		// test found that hole; this is the fix.
		//
		// With it, the two ignitions of one cylinder are separated by a forward run
		// from 540 to 180 that no reversal can shorten, so 720 degrees of forward
		// travel per bang holds however the crank is shaken.
		if (FourStrokeCycle.crossedBackward(cycleAngle, deltaDegrees, FourStrokeCycle.ARMING_ANGLE_DEGREES)
			|| FourStrokeCycle.crossedBackward(cycleAngle, deltaDegrees,
				FourStrokeCycle.IGNITION_ANGLE_DEGREES))
			armed = false;

		Event event = Event.NONE;
		if (FourStrokeCycle.crossedForward(cycleAngle, deltaDegrees, FourStrokeCycle.ARMING_ANGLE_DEGREES)) {
			armed = true;
			event = Event.ARMED;
		}

		if (FourStrokeCycle.crossedForward(cycleAngle, deltaDegrees, FourStrokeCycle.IGNITION_ANGLE_DEGREES)) {
			boolean lit = armed && canIgnite;
			// Cleared either way. A charge that reached top dead centre without a spark
			// is not still waiting there on the next revolution - the exhaust stroke
			// throws it out, and the cylinder has to inhale again.
			armed = false;
			event = lit ? Event.IGNITED : Event.MISFIRED;
		}
		return event;
	}

	/**
	 * Tracks whole-cycle wraps from the angle either side of a step.
	 *
	 * <p>Inferred from the angle rather than accumulated from the delta on purpose: a
	 * step larger than a cycle would otherwise need its own arithmetic, and more
	 * importantly the master angle is the authority - the counter has to follow it,
	 * not run alongside it and drift.
	 */
	private void countWrap(float previous, float deltaDegrees) {
		if (deltaDegrees > 0.0F) {
			completedCycles += (long) (deltaDegrees / FourStrokeCycle.CYCLE_DEGREES);
			if (cycleAngle < previous)
				completedCycles++;
		} else if (deltaDegrees < 0.0F) {
			completedCycles -= (long) (-deltaDegrees / FourStrokeCycle.CYCLE_DEGREES);
			if (cycleAngle > previous)
				completedCycles--;
		}
	}

	// ------------------------------------------------------------------------
	// Reads
	// ------------------------------------------------------------------------

	/** This cylinder's own cycle angle, in {@code [0, 720)}. */
	public float cycleAngle() {
		return cycleAngle;
	}

	/**
	 * This cylinder's physical crank angle, in {@code [0, 360)}: where its piston
	 * actually is.
	 *
	 * <p>What the renderers want, and the value that must keep behaving exactly as it
	 * does today. It is folded out of the cycle angle rather than tracked separately,
	 * so the piston can never disagree with the stroke.
	 */
	public float physicalAngle() {
		return FourStrokeCycle.physicalAngle(cycleAngle);
	}

	/** Which of the four strokes this cylinder is on. */
	public FourStrokePhase phase() {
		return FourStrokePhase.at(cycleAngle);
	}

	/** Whether a charge is inducted and not yet burned. */
	public boolean isArmed() {
		return armed;
	}

	/** Signed count of whole cycles this cylinder has completed. */
	public long completedCycles() {
		return completedCycles;
	}

	public int index() {
		return index;
	}

	/**
	 * Total torque this cylinder puts on the crank, in the same units the production
	 * engine's torques are in.
	 *
	 * <p>Three separate physical effects, summed, and each is nonzero on a different
	 * part of the cycle - which is the whole content of "this is a four-stroke".
	 *
	 * @param burning  a charge lit at this cylinder's last ignition is still pushing
	 * @param combustionPeak peak combustion torque of one cylinder
	 * @param compressionPeak peak magnitude of the gas spring
	 * @param pumpingPeak peak magnitude of the intake/exhaust pumping loss
	 */
	public float torque(boolean burning, float combustionPeak, float compressionPeak, float pumpingPeak) {
		float total = FourStrokeCycle.gasSpringTorque(cycleAngle) * compressionPeak
			+ FourStrokeCycle.pumpingTorque(cycleAngle) * pumpingPeak;
		if (burning)
			total += FourStrokeCycle.combustionTorque(cycleAngle) * combustionPeak;
		return total;
	}

	// ------------------------------------------------------------------------
	// Save / reload
	// ------------------------------------------------------------------------

	/**
	 * Restores the state a world save has to carry for this cylinder.
	 *
	 * <p>The cycle angle is <i>not</i> among them: it is re-derived from the engine's
	 * one saved master cycle angle on the next step, exactly as it is derived every
	 * other step. Persisting it per cylinder would be a second copy of a fact that is
	 * already on disk, which is how the two come back disagreeing.
	 *
	 * <p>{@link #armed} genuinely is per-cylinder physical state - one engine can have
	 * a charge in cylinder 2 and nothing in cylinder 3 - and must be saved, or a
	 * reload silently grants or destroys a charge.
	 */
	public void restore(boolean armed, long completedCycles) {
		this.armed = armed;
		this.completedCycles = completedCycles;
	}
}
