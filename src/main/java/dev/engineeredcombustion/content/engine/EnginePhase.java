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
 *   COASTING --(speed &lt; STALL_RPM)----------------------------> STOPPED
 * </pre>
 *
 * Only {@link #RUNNING} and {@link #COASTING} generate rotation for Create; in
 * every other phase the engine is a passive kinetic block that other Create
 * sources may turn.
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

	/** True while the engine is the thing producing rotation for Create. */
	public boolean generatesPower() {
		return this == RUNNING || this == COASTING;
	}

	/**
	 * True while the simulation - rather than whatever Create is doing to the
	 * shaft - decides the engine's own speed.
	 *
	 * <p>{@link #STARTING} is included so that pre-start firing kicks actually
	 * move the simulated speed. It deliberately does <i>not</i> generate power, so
	 * including it here cannot disturb Create's source handoff.
	 */
	public boolean simulationOwnsSpeed() {
		return this == RUNNING || this == STARTING;
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
