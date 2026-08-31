package dev.engineeredcombustion.content.engine;

/**
 * A worn part, described the way a mechanic would describe it.
 *
 * <p>The engine stores wear as a float in {@code [0, 1]} because the physics
 * needs a number, but a float is a terrible thing to show a player. "Wear:
 * 0.3847261" says nothing that "Used" does not, and it invites exactly the
 * mindset this milestone exists to avoid - reading an engine as a percentage
 * bar rather than as a machine with worn parts in it.
 *
 * <p>So every player-facing readout goes through this enum, and the numeric
 * thresholds behind it live in exactly one place - {@link EngineTuning} - rather
 * than being written out again at each call site.
 *
 * <h2>Ordering</h2>
 * The constants run best to worst, so {@code ordinal()} is a usable severity and
 * {@link #worst(WearCondition, WearCondition)} is a comparison rather than a
 * table. That matters for the engine's overall condition, which is deliberately
 * the <i>worst</i> of its parts and never their average: an inline-4 with one
 * critical cylinder is not a "slightly used" engine, and averaging would say it
 * was.
 *
 * <p>Free of any Minecraft, NeoForge or Create type, like the rest of the pure
 * simulation layer.
 */
public enum WearCondition {

	/** Fresh out of the crate, or near enough that nothing is measurably different. */
	PRISTINE("pristine", 0.0F),

	/** Run in, and still to specification. */
	GOOD("good", EngineTuning.CONDITION_GOOD_WEAR),

	/** Visibly used. Nothing to act on yet. */
	USED("used", EngineTuning.CONDITION_USED_WEAR),

	/** Past its best, and starting to cost the engine something measurable. */
	WORN("worn", EngineTuning.CONDITION_WORN_WEAR),

	/** Bad enough that the player should be planning a replacement. */
	POOR("poor", EngineTuning.CONDITION_POOR_WEAR),

	/** At the service limit. Still recoverable by replacing the part - see the milestone. */
	CRITICAL("critical", EngineTuning.CONDITION_CRITICAL_WEAR);

	private final String id;
	private final float lowerBound;

	WearCondition(String id, float lowerBound) {
		this.id = id;
		this.lowerBound = lowerBound;
	}

	/**
	 * The band a wear value falls into.
	 *
	 * <p>Walked from the worst band down, so the bounds read as "at least this
	 * worn" and there is no arithmetic anywhere that could put a value in two
	 * bands. Values outside {@code [0, 1]} are clamped rather than rejected: wear
	 * arriving from an old world, a command or a hand-edited item is data, not a
	 * programming error.
	 */
	public static WearCondition of(float wear) {
		float clamped = EngineWearMath.clampWear(wear);
		WearCondition[] bands = values();
		for (int index = bands.length - 1; index > 0; index--)
			if (clamped >= bands[index].lowerBound)
				return bands[index];
		return PRISTINE;
	}

	/** Lowest wear value that still reads as this condition. */
	public float lowerBound() {
		return lowerBound;
	}

	/** The worse of two conditions - the engine's aggregate rule. */
	public static WearCondition worst(WearCondition first, WearCondition second) {
		return first.ordinal() >= second.ordinal() ? first : second;
	}

	/** Whether this condition is at least as bad as the given one. */
	public boolean isAtLeast(WearCondition other) {
		return ordinal() >= other.ordinal();
	}

	/**
	 * Whether a part in this condition is worth telling the player about
	 * unprompted. Everything up to {@link #USED} is a normal, healthy engine.
	 */
	public boolean isWarning() {
		return isAtLeast(WORN);
	}

	/**
	 * Whether this is worth printing at all on an item tooltip. A pristine part is
	 * the normal case and saying so on every freshly crafted item would be noise -
	 * see the milestone's tooltip rule.
	 */
	public boolean isWorthReporting() {
		return this != PRISTINE;
	}

	/** Key root only; the mod id is prepended by the lang builder. */
	public String translationKey() {
		return "gui.condition." + id;
	}

	public String getId() {
		return id;
	}

	public static WearCondition byId(String id) {
		for (WearCondition condition : values())
			if (condition.id.equals(id))
				return condition;
		return PRISTINE;
	}
}
