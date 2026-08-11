package dev.engineeredcombustion.content.engine;

/**
 * The engine's run state.
 *
 * <pre>
 *   STOPPED  --(rotated by anything)--------------------------> CRANKING
 *   CRANKING --(first valid pre-start firing)-----------------> STARTING
 *   CRANKING --(rotation stops)-------------------------------> STOPPED
 *   STARTING --(enough firing cycles accumulated)-------------> RUNNING
 *   STARTING --(fuel/ignition/structure lost, or attempt times out)--> CRANKING
 *   RUNNING  --(ignition off, structure broken or out of fuel)--> COASTING
 *   RUNNING  --(speed &lt; STALL_RPM, e.g. network overstressed)--> STOPPED
 *   COASTING --(conditions restored and still &gt;= START_RPM)----> RUNNING
 *   COASTING --(crankshaft comes to rest)---------------------> STOPPED
 * </pre>
 *
 * <h2>The phase is necessary for generation, never sufficient</h2>
 * {@link #RUNNING} is the <i>only</i> phase in which the engine may be a kinetic
 * source at all, but being in it does not by itself make the engine one. The
 * authoritative answer is {@link EngineState#isActivelyGenerating()}, which also
 * demands live ignition, a plug, fuel and combustion that is genuinely still
 * happening. Everything else - generated speed, stress capacity, the HUD - reads
 * that one predicate and never re-derives its own version of it.
 *
 * <p>{@link #COASTING} deliberately does <b>not</b> generate. A flywheel that has
 * stopped burning fuel is still turning, and it may well still be turning because
 * some other engine on the network is spinning it - which is precisely how a dead
 * engine used to hand out a full engine's worth of Stress Capacity for free.
 */
public enum EnginePhase {

	STOPPED("stopped"),
	/** Being turned by something else, not yet attempting to fire. */
	CRANKING("cranking"),
	/** Firing, but the engine has not caught yet - start cycles are accumulating. */
	STARTING("starting"),
	/** Combustion is firing once per revolution and the engine sustains itself. */
	RUNNING("running"),
	/** No more combustion, but the flywheel is still spinning down. */
	COASTING("coasting");

	private final String id;

	EnginePhase(String id) {
		this.id = id;
	}

	/**
	 * Whether this phase permits the engine to be a kinetic source <i>at all</i>.
	 *
	 * <p>A necessary condition, never a sufficient one:
	 * {@link EngineState#isActivelyGenerating()} is the question everything else
	 * actually asks. Being RUNNING means the engine caught and has not lost
	 * combustion since; it does not mean a charge burned this revolution.
	 */
	public boolean mayGenerate() {
		return this == RUNNING;
	}

	/**
	 * True while combustion is delivering torque of the engine's own.
	 *
	 * <p>{@link #STARTING} is included because a pre-start firing kick is real
	 * torque: it is what lets the engine briefly out-run the hand crank turning it,
	 * which is what stops that crank from pinning the engine to its own speed. It
	 * deliberately does <i>not</i> imply generation.
	 */
	public boolean isFiring() {
		return this == RUNNING || this == STARTING;
	}

	/**
	 * True in the two phases the engine reaches by having actually run: RUNNING and
	 * the spin-down that follows it. What separates "this engine died" from "this
	 * engine was never started", which is the difference between a stall sound and
	 * silence.
	 */
	public boolean hasCaught() {
		return this == RUNNING || this == COASTING;
	}

	/**
	 * Key root only. Catnip's LangBuilder prepends the mod id, so this must NOT
	 * contain it - that double-prefixing was the raw-key regression.
	 */
	public String translationKey() {
		return "gui.phase." + id;
	}

	public String getId() {
		return id;
	}

	public static EnginePhase byId(String id) {
		for (EnginePhase phase : values())
			if (phase.id.equals(id))
				return phase;
		return STOPPED;
	}
}
