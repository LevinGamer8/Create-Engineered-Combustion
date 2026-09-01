package dev.engineeredcombustion.content.engine.fourstroke;

/**
 * The two independent things that decide whether one cylinder may burn a charge,
 * and nothing else.
 *
 * <h2>Three questions, kept apart</h2>
 * The architecture must be able to answer each of these for every cylinder without
 * deriving it from either of the others:
 * <ol>
 * <li><b>Where is the piston?</b> A function of the crank throw, repeating every 360
 * degrees - {@link FourStrokeFiringOrder#geometricOffsetDegrees};</li>
 * <li><b>Which stroke is it on?</b> A function of the cycle angle, repeating every
 * 720 - {@link CyclePosition#phase()};</li>
 * <li><b>May it fire?</b> This class, which is not a function of position at all.</li>
 * </ol>
 *
 * <h2>Two guards, and why both</h2>
 * <dl>
 * <dt>The arming latch - <i>is there a charge?</i></dt>
 * <dd>Set when the cylinder forward-crosses the start of its intake stroke, consumed
 * at compression top dead centre whether or not it lights. It is the physical
 * statement that a cylinder cannot burn what it has not inhaled, and it is what makes
 * a misfire cost a whole cycle.</dd>
 *
 * <dt>The event key - <i>has this opportunity already been taken?</i></dt>
 * <dd>{@code (cylinderIndex, firingCycleIndex)} names one firing opportunity uniquely.
 * A cylinder may take each key once. This is the guard that makes a duplicate
 * <i>detectable</i> rather than merely improbable, and it is what holds when a crank is
 * rocked, reversed, saved mid-stroke or driven at absurd speed.</dd>
 * </dl>
 * Either alone closes the rocking exploit. Both are kept because they fail in different
 * directions: the latch is physics and could be relaxed for gameplay, the key is
 * bookkeeping and must never be. Neither allocates - the key is compared as two
 * primitives against a {@code long} field.
 *
 * <p><b>State only.</b> This holds no position: a cylinder's position is the engine's
 * one position shifted by its offset, derived on every step exactly as it is on every
 * other step. Persisting it per cylinder would put one fact on disk four times, which
 * is how the copies come back disagreeing.
 */
public final class CylinderCycleState {

	/** No opportunity has been taken yet. Below any real cycle index. */
	public static final long NO_EVENT = Long.MIN_VALUE;

	/** What happened to one cylinder during one step. */
	public enum Event {

		/** Nothing of note. */
		NONE,

		/** The intake stroke began: a fresh charge is now available to burn. */
		ARMED,

		/** The cylinder passed compression top dead centre holding a charge, and lit it. */
		IGNITED,

		/**
		 * The cylinder reached compression top dead centre but could not light: no
		 * spark, no fuel, no camshaft, no charge drawn, or this opportunity was already
		 * taken. The charge is pushed back out, so a misfire costs a whole cycle.
		 */
		MISFIRED
	}

	private CylinderCycleState() {
	}

	/**
	 * Advances one cylinder and reports what happened, without allocating.
	 *
	 * <p><b>At most one ignition per call, at any speed.</b> A step longer than a whole
	 * cycle crosses several of this cylinder's firing opportunities; only the last is
	 * taken and the earlier ones are lost rather than accumulated. That is a deliberate
	 * fail-closed choice - lost absurd-overspeed events are preferable to duplicated or
	 * free ones - and it needs more than 2400 RPM to become reachable at all.
	 *
	 * <p>Written as a static over a caller-owned {@code boolean[]} and {@code long[]}
	 * rather than as one object per cylinder, because this runs once per cylinder per
	 * tick for every engine in the world.
	 *
	 * @param local       this cylinder's own position after the step
	 * @param armed       the engine's per-cylinder arming latches
	 * @param lastFired   the engine's per-cylinder last-taken opportunity keys
	 * @param cylinder    which cylinder
	 * @param canIgnite   spark plug present, ignition live, camshaft installed and fuel
	 *                    available: the whole of the engine's permission to light a new
	 *                    charge
	 */
	public static Event advance(CyclePosition local, boolean[] armed, long[] lastFired, int cylinder,
		boolean canIgnite) {
		// BACKWARDS FIRST. A crank wound backwards past either timing point throws the
		// charge away: the intake stroke it was drawn on has been undone, or the piston
		// has been dragged back down through top dead centre. This is fidelity rather
		// than protection - the event key below is what makes the rocking exploit
		// impossible - but it is what stops a cylinder hoarding a charge through a
		// hand-crank wobble and firing the instant it is nudged into a fresh cycle.
		if (local.crossedBackward(FourStrokeCycle.ARMING_ANGLE_DEGREES)
			|| local.crossedBackward(FourStrokeCycle.IGNITION_ANGLE_DEGREES))
			armed[cylinder] = false;

		Event event = Event.NONE;
		if (local.crossedForward(FourStrokeCycle.ARMING_ANGLE_DEGREES)) {
			armed[cylinder] = true;
			event = Event.ARMED;
		}

		if (local.crossedForward(FourStrokeCycle.IGNITION_ANGLE_DEGREES)) {
			long opportunity = local.crossingCycleIndex(FourStrokeCycle.IGNITION_ANGLE_DEGREES);
			boolean fresh = opportunity != lastFired[cylinder];
			boolean lit = armed[cylinder] && canIgnite && fresh;
			armed[cylinder] = false;
			if (lit)
				lastFired[cylinder] = opportunity;
			event = lit ? Event.IGNITED : Event.MISFIRED;
		}
		return event;
	}
}
