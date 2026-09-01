package dev.engineeredcombustion.content.engine.fourstroke;

/**
 * Where an engine is in its four-stroke cycle: <b>which</b> cycle, and <b>where</b>
 * in it.
 *
 * <h2>Why two fields rather than one</h2>
 * Three representations were considered, and this is the one that survives every
 * question asked of it.
 *
 * <dl>
 * <dt>A - a wrapped angle in {@code [0, 720)} alone</dt>
 * <dd>Bounded and precise for ever, and crossing detection works. But it cannot
 * name an event: "cylinder 3 crossed 180" is not "cylinder 3's opportunity in cycle
 * 1842", and without a cycle number the only way to stop a rocked crank re-firing
 * one charge is a latch, with no independent check behind it.</dd>
 *
 * <dt>B - one unwrapped position that grows for ever</dt>
 * <dd>Names events implicitly and makes crossing trivial, but the magnitude is
 * unbounded, so the resolution of the <i>increment</i> decays as the number grows.
 * In {@code float} that is fatal within weeks of uptime - the engine measurably
 * ceases to turn at all.</dd>
 *
 * <dt>C - a cycle counter and a bounded angle (this class)</dt>
 * <dd>The angle never grows, so its precision is fixed for ever; the counter is an
 * exact integer naming the cycle; and the pair reads as "cycle 1842, 473.2 degrees,
 * EXHAUST". It is also the only one of the three that answers <i>both</i> questions
 * the architecture needs kept apart: cycle number and cycle position.</dd>
 * </dl>
 *
 * <h2>Invariants</h2>
 * <ul>
 * <li>{@link #angle()} is always in {@code [0, 720)}, whatever is thrown at
 * {@link #advance};</li>
 * <li>{@link #cycleIndex()} increments on a forward wrap and <b>decrements</b> on a
 * backward one, so an engine rocked across the wrap accumulates nothing;</li>
 * <li>the two together are the position - neither alone is.</li>
 * </ul>
 */
public final class CyclePosition {

	/** One complete four-stroke cycle, in crankshaft degrees. */
	public static final float CYCLE_DEGREES = FourStrokeCycle.CYCLE_DEGREES;

	private long cycleIndex;
	private float angleDegrees;

	/**
	 * The signed travel of the last {@link #advance}.
	 *
	 * <p>Held here rather than passed to each crossing query, so a caller cannot ask
	 * "did I cross X" with a different delta from the one that actually happened -
	 * a whole class of bug that simply cannot be written.
	 */
	private float lastDeltaDegrees;

	public CyclePosition() {
	}

	public CyclePosition(long cycleIndex, float angleDegrees) {
		this.cycleIndex = cycleIndex;
		this.angleDegrees = angleDegrees;
		normalize();
	}

	/**
	 * Turns the crank by a signed amount.
	 *
	 * <p>The wrap count comes from one {@code floor} of the exact sum rather than
	 * from a loop or a chain of comparisons, so a single step of any size - a tick
	 * that jumps ten cycles under an absurd external speed, or a hundredth of a
	 * degree - lands on the right cycle with the same three lines.
	 *
	 * <p>The sum is taken in {@code double} deliberately. Both operands are bounded
	 * (the angle by construction, the delta by the caller's speed), so this is not
	 * the unbounded arithmetic of representation B - it is one wide intermediate to
	 * keep the wrap count exact before the result is folded back into a bounded
	 * float.
	 */
	public void advance(float deltaDegrees) {
		lastDeltaDegrees = deltaDegrees;
		if (deltaDegrees == 0.0F)
			return;
		double exact = angleDegrees + (double) deltaDegrees;
		long wraps = (long) Math.floor(exact / CYCLE_DEGREES);
		cycleIndex += wraps;
		angleDegrees = (float) (exact - wraps * (double) CYCLE_DEGREES);
		normalize();
	}

	/**
	 * Belt and braces after the fold: {@code (float)} rounding of a value just under
	 * 720 can land exactly on it, and an angle of 720 would put every phase lookup
	 * one stroke out.
	 */
	private void normalize() {
		if (angleDegrees >= CYCLE_DEGREES) {
			angleDegrees -= CYCLE_DEGREES;
			cycleIndex++;
		} else if (angleDegrees < 0.0F) {
			angleDegrees += CYCLE_DEGREES;
			cycleIndex--;
		}
		// Collapses negative zero, which would otherwise reach a debug overlay.
		angleDegrees += 0.0F;
	}

	/** Where in the cycle, in {@code [0, 720)}. */
	public float angle() {
		return angleDegrees;
	}

	/** Which cycle. Signed, and decremented by a backward wrap. */
	public long cycleIndex() {
		return cycleIndex;
	}

	/** The signed travel of the last {@link #advance}. */
	public float lastDelta() {
		return lastDeltaDegrees;
	}

	/** Replaces the position outright, for persistence and for phase corrections. */
	public void set(long cycleIndex, float angleDegrees) {
		this.cycleIndex = cycleIndex;
		this.angleDegrees = FourStrokeCycle.normalizeCycle(angleDegrees);
		normalize();
	}

	/** Copies another position into this one. */
	public void set(CyclePosition other) {
		this.cycleIndex = other.cycleIndex;
		this.angleDegrees = other.angleDegrees;
		this.lastDeltaDegrees = other.lastDeltaDegrees;
	}

	/** Forces the last-step travel, for a side that adopts a position rather than turning to it. */
	public void setLastDelta(float lastDeltaDegrees) {
		this.lastDeltaDegrees = lastDeltaDegrees;
	}

	/**
	 * The physical crank angle, in {@code [0, 360)}: where the piston actually is.
	 *
	 * <p>Derived, never stored. A piston at top dead centre is at cycle angle 180 or
	 * 540 and this cannot tell those apart - which is the entire reason the cycle
	 * angle exists.
	 */
	public float physicalAngle() {
		return FourStrokeCycle.normalizeRevolution(angleDegrees);
	}

	/** Which of the four strokes this position is on. */
	public FourStrokePhase phase() {
		return FourStrokePhase.at(angleDegrees);
	}

	// ------------------------------------------------------------------------
	// Crossing
	// ------------------------------------------------------------------------

	/** Whether the last advance passed {@code target} turning forwards. */
	public boolean crossedForward(float target) {
		return FourStrokeCycle.crossedForward(angleDegrees, lastDeltaDegrees, target);
	}

	/** Whether the last advance passed {@code target} turning backwards. */
	public boolean crossedBackward(float target) {
		return FourStrokeCycle.crossedBackward(angleDegrees, lastDeltaDegrees, target);
	}

	/**
	 * The cycle a crossing of {@code target} belongs to.
	 *
	 * <p><b>The event identity, and the reason this class carries a counter.</b>
	 * Paired with a cylinder index it names one firing opportunity uniquely and for
	 * ever: <i>cylinder 3, cycle 1842</i>. That pair is what makes a duplicate
	 * detectable rather than merely unlikely.
	 *
	 * <p>The rule is a comparison rather than arithmetic on the counter, and that is
	 * what keeps it exact. If the position is at or past the target, the crossing was
	 * in this cycle; if it is short of it, the crossing was in the one before, because
	 * the wrap happened after it. That covers a step which wraps <i>and</i> crosses in
	 * the same tick, without ever multiplying the counter by 720 - which would
	 * reintroduce exactly the unbounded magnitude representation C exists to avoid.
	 */
	public long crossingCycleIndex(float target) {
		return angleDegrees >= target ? cycleIndex : cycleIndex - 1;
	}

	// ------------------------------------------------------------------------
	// Derived positions
	// ------------------------------------------------------------------------

	/**
	 * This position shifted by a cylinder's phase offset, without allocating.
	 *
	 * <p>Writes into {@code into} so a per-tick loop over four cylinders costs no
	 * garbage. The engine holds <b>one</b> position and every cylinder is a view of
	 * it, which is what makes four cylinders mechanically synchronised by
	 * construction rather than by four counters happening to agree.
	 *
	 * @param offsetDegrees the cylinder's cycle phase offset, in {@code [0, 720)}
	 */
	public void shiftedBy(float offsetDegrees, CyclePosition into) {
		float raw = angleDegrees + offsetDegrees;
		boolean wrapped = raw >= CYCLE_DEGREES;
		into.angleDegrees = wrapped ? raw - CYCLE_DEGREES : raw;
		into.cycleIndex = wrapped ? cycleIndex + 1 : cycleIndex;
		into.lastDeltaDegrees = lastDeltaDegrees;
		into.normalize();
	}

	@Override
	public String toString() {
		return String.format("cycle %d, %.2f deg, %s", cycleIndex, angleDegrees, phase());
	}
}
