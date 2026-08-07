package dev.engineeredcombustion.content.engine;

/**
 * The engine's run state.
 *
 * <pre>
 *   STOPPED --(rotated by anything)--> CRANKING
 *   CRANKING --(ignition on, forward, &gt;= START_RPM, crank passes firing angle)--> RUNNING
 *   CRANKING --(rotation stops)--> STOPPED
 *   RUNNING  --(ignition off or structure broken)--> COASTING
 *   RUNNING  --(speed &lt; STALL_RPM, e.g. network overstressed)--> STOPPED
 *   COASTING --(ignition back on and still &gt;= START_RPM)--> RUNNING
 *   COASTING --(speed &lt; STALL_RPM)--> STOPPED
 * </pre>
 *
 * Only {@link #RUNNING} and {@link #COASTING} generate rotation for Create; in
 * {@link #STOPPED} and {@link #CRANKING} the engine is a passive kinetic block
 * that other Create sources may turn.
 */
public enum EnginePhase {

	STOPPED("stopped"),
	/** Being turned by something else, not producing power. */
	CRANKING("cranking"),
	/** Combustion is firing once per revolution. */
	RUNNING("running"),
	/** No more combustion, but the flywheel is still spinning down. */
	COASTING("coasting");

	private final String id;

	EnginePhase(String id) {
		this.id = id;
	}

	/** True while the engine is the thing producing rotation. */
	public boolean generatesPower() {
		return this == RUNNING || this == COASTING;
	}

	public String translationKey() {
		return "gui.engineered_combustion.phase." + id;
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
