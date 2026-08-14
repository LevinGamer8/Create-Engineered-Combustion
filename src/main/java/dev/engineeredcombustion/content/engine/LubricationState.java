package dev.engineeredcombustion.content.engine;

/**
 * How well the engine is lubricated, and what that costs it mechanically.
 *
 * <p>Lubrication is deliberately <i>not</i> a precondition for the engine to
 * turn. An engine with no oil still cranks, still moves its piston, and can
 * still be started - it simply fights far more friction doing it. Making oil a
 * hard gate would be a switch, not a system, and would tell the player nothing
 * about what oil is for.
 *
 * <p>The multiplier feeds the engine's existing friction term rather than any
 * separate slowdown mechanism, so a dry engine settles at a lower speed by the
 * same physics that makes a healthy one settle at idle. Low oil trims speed and
 * reserve torque; a dry engine runs much rougher and coasts down much sooner,
 * while an external source can still turn it normally.
 */
public enum LubricationState {

	/** Comfortably above the warning threshold. */
	NORMAL("normal", EngineTuning.FRICTION_MULTIPLIER_NORMAL),
	/** Below the warning threshold but not empty. */
	LOW("low", EngineTuning.FRICTION_MULTIPLIER_LOW),
	/** No oil sump at all, or an empty one. */
	DRY("dry", EngineTuning.FRICTION_MULTIPLIER_DRY);

	private final String id;
	private final float frictionMultiplier;

	LubricationState(String id, float frictionMultiplier) {
		this.id = id;
		this.frictionMultiplier = frictionMultiplier;
	}

	/** Scales the engine's normal friction torque. Always >= 1. */
	public float frictionMultiplier() {
		return frictionMultiplier;
	}

	/** Whether the player should be warned about this state on the overlay. */
	public boolean isWarning() {
		return this != NORMAL;
	}

	/**
	 * Classifies an oil quantity. A missing sump is the same as an empty one -
	 * both mean no oil is reaching the bearings.
	 */
	public static LubricationState forAmount(int millibuckets) {
		if (millibuckets <= 0)
			return DRY;
		return millibuckets < EngineTuning.LOW_OIL_THRESHOLD_MB ? LOW : NORMAL;
	}

	/** Key root only; the mod id is prepended by the lang builder. */
	public String translationKey() {
		return "gui.lubrication." + id;
	}

	public String getId() {
		return id;
	}

	public static LubricationState byId(String id) {
		for (LubricationState state : values())
			if (state.id.equals(id))
				return state;
		return DRY;
	}
}
