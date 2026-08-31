package dev.engineeredcombustion.content.engine;

/**
 * Something an engine <i>did</i>, as opposed to something it <i>is</i>.
 *
 * <p>Every advancement in the mod is awarded from one of these and from nothing
 * else. That is deliberate, and it is the whole reason this enum exists rather
 * than a scan: awarding progress by walking every engine and every player each
 * tick would be both expensive and wrong, because most of what the milestone
 * wants to reward is a <b>transition</b> - an engine that caught, a part that was
 * replaced, a condition that was reached - and a transition is invisible to
 * anything that only ever samples the current state.
 *
 * <p>Free of any Minecraft, NeoForge or Create type, like the rest of this
 * package. {@link EngineEventTracker} decides which of these happened from
 * numbers alone; the block entity turns them into criteria for real players.
 *
 * <h2>Why one enum and not one class per advancement</h2>
 * The criterion that consumes these is configurable - it matches on the event
 * plus optional cylinder counts and conditions - so twenty-three advancements
 * share a single trigger. Adding an advancement is a line of JSON, not a Java
 * class.
 */
public enum EngineEvent {

	/**
	 * A valid engine structure came into existence where there was not one before.
	 *
	 * <p>The mechanical fact, not the crafting of any part: placing the last block
	 * that makes an assembly valid is what fires this, and taking one away and
	 * putting it back fires it again.
	 */
	ASSEMBLED("assembled"),

	/**
	 * A stopped engine started being turned. {@code STOPPED -> CRANKING}.
	 *
	 * <p>Rotation of any origin, because a hand crank and a windmill are the same
	 * mechanical event. What makes it a <i>player's</i> cranking is the attribution
	 * window, not this.
	 */
	CRANKING_STARTED("cranking_started"),

	/**
	 * An engine caught and now runs under its own combustion.
	 * {@code STARTING -> RUNNING}, and that transition alone.
	 *
	 * <p>Deliberately not {@code COASTING -> RUNNING}, which is an engine that was
	 * already running picking its fuel supply back up, and deliberately not
	 * anything a reload or a chunk load can produce - both of those restore a
	 * phase rather than passing through one, and a restored RUNNING was never
	 * STARTING on this side of the boundary.
	 */
	ENGINE_STARTED("engine_started"),

	/**
	 * An engine began contributing Stress Capacity to a Create network.
	 *
	 * <p>Rotation is not generation. An engine being motored by a stronger network
	 * turns without ever firing this, which is exactly the distinction the
	 * "Mechanical Power" advancement exists to teach.
	 */
	GENERATION_STARTED("generation_started"),

	/**
	 * An inline engine of some size is running and generating, sustained long
	 * enough to be a fact rather than a flicker.
	 *
	 * <p>Carries both the cylinder count and how many of them are actually firing,
	 * so "an inline-4 with all four alight" and "an inline-4 running on three" are
	 * the same event distinguished by its payload.
	 */
	INLINE_RUNNING("inline_running"),

	/**
	 * A part crossed into a worse condition band <b>through operation</b>.
	 *
	 * <p>Only ever fired from wear the engine actually accumulated while running.
	 * Wear that arrives any other way - loaded from disk, carried in on an item,
	 * set by a future command - changes the number without passing through here.
	 */
	CONDITION_REACHED("condition_reached"),

	/**
	 * A worn part was replaced with a better one, and the engine is measurably
	 * healthier for it.
	 *
	 * <p>The improvement is the event. Pulling a worn Piston Assembly out and
	 * putting the same one back does not fire this, because nothing improved -
	 * which is the joke the "Put It Back" advancement is built on and the rule
	 * "Fresh Internals" needs to be honest.
	 */
	MAINTENANCE_COMPLETED("maintenance_completed"),

	/**
	 * The engine has been mistreated continuously for long enough that it cannot
	 * be an accident.
	 *
	 * <p>Sustained by design - see {@link EngineEventTracker} for the windows.
	 * A single bad tick is a mistake; fifteen seconds of it is a decision.
	 */
	ABUSE_STATE("abuse_state"),

	/**
	 * A player tried to build something the engine does not support.
	 *
	 * <p>Fired at the moment of refusal, so the layout stays invalid - these are
	 * jokes about hitting a limit, never a way through it.
	 */
	INVALID_LAYOUT_ATTEMPT("invalid_layout_attempt");

	private final String id;

	EngineEvent(String id) {
		this.id = id;
	}

	public String getId() {
		return id;
	}

	public static EngineEvent byId(String id) {
		for (EngineEvent event : values())
			if (event.id.equals(id))
				return event;
		return null;
	}
}
