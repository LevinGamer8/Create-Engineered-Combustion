package dev.engineeredcombustion.content.engine;

/**
 * One {@link EngineEvent} and everything an advancement might want to ask about
 * it.
 *
 * <p>Immutable, and free of any Minecraft type - which is what lets
 * {@link EngineEventTracker} be tested without a game. The block entity is the
 * only thing that ever turns one of these into a criterion for a real player.
 *
 * <p>The payload is deliberately flat and mostly optional. Twenty-three
 * advancements share one criterion, so the record has to carry the union of what
 * any of them cares about: how big the engine is, how much of it is alight, what
 * condition it is in, which part reached that condition, and which flavour of
 * mistreatment it is being subjected to. A field that does not apply to an event
 * is simply null or -1, and the criterion only ever tests what its JSON asked
 * for - so an absent field is "no opinion", never "no match".
 *
 * <p>The optional components are documented as nullable rather than annotated,
 * because this package compiles with no classpath at all - see the simulation
 * source set in build.gradle - and therefore has no annotation to reach for.
 *
 * @param event            what happened
 * @param cylinderCount    how many cylinders the engine has, or -1
 * @param activeCylinders  how many of them were firing, or -1
 * @param condition        the condition the event is about - for a
 *                         {@link EngineEvent#CONDITION_REACHED} the band just
 *                         reached, for {@link EngineEvent#ENGINE_STARTED} the
 *                         condition the engine was in <i>before</i> it started,
 *                         and otherwise the engine's overall condition
 * @param conditionKind    which part reached it, for a condition event
 * @param abuseKind        which sustained mistreatment, for an abuse event
 * @param conditionBefore  for maintenance, what it was
 * @param conditionAfter   for maintenance, what it became
 * @param invalidLayout    for a refused build, what the player tried
 */
public record EngineEventRecord(EngineEvent event, int cylinderCount, int activeCylinders,
	WearCondition condition, EngineEventTracker.ConditionKind conditionKind,
	EngineEventTracker.AbuseKind abuseKind, WearCondition conditionBefore,
	WearCondition conditionAfter, InvalidLayout invalidLayout) {

	public static EngineEventRecord of(EngineEvent event, int cylinderCount, int activeCylinders,
		WearCondition condition) {
		return new EngineEventRecord(event, cylinderCount, activeCylinders, condition, null, null, null, null, null);
	}

	public static EngineEventRecord condition(EngineEventTracker.ConditionKind kind, WearCondition reached,
		int cylinderCount, int activeCylinders, WearCondition overall) {
		return new EngineEventRecord(EngineEvent.CONDITION_REACHED, cylinderCount, activeCylinders, reached, kind,
			null, null, null, null);
	}

	public static EngineEventRecord abuse(EngineEventTracker.AbuseKind kind, int cylinderCount, int activeCylinders,
		WearCondition overall) {
		return new EngineEventRecord(EngineEvent.ABUSE_STATE, cylinderCount, activeCylinders, overall, null, kind,
			null, null, null);
	}

	public static EngineEventRecord maintenance(WearCondition before, WearCondition after, int cylinderCount,
		int activeCylinders) {
		return new EngineEventRecord(EngineEvent.MAINTENANCE_COMPLETED, cylinderCount, activeCylinders, after, null,
			null, before, after, null);
	}

	public static EngineEventRecord invalidLayout(InvalidLayout reason) {
		return new EngineEventRecord(EngineEvent.INVALID_LAYOUT_ATTEMPT, -1, -1, WearCondition.PRISTINE, null, null,
			null, null, reason);
	}

	/**
	 * Why a layout was refused - the two jokes built on hitting a limit.
	 *
	 * <p>Fired at the moment of refusal, so the layout stays invalid. These reward
	 * a player for finding an edge, and must never become a way past one.
	 */
	public enum InvalidLayout {
		/** A second Flywheel, on an engine that already has one. */
		SECOND_FLYWHEEL("second_flywheel"),
		/** A fifth crankshaft section, past the inline limit. */
		TOO_MANY_CYLINDERS("too_many_cylinders");

		private final String id;

		InvalidLayout(String id) {
			this.id = id;
		}

		public String getId() {
			return id;
		}

		public static InvalidLayout byId(String id) {
			for (InvalidLayout reason : values())
				if (reason.id.equals(id))
					return reason;
			return null;
		}
	}
}
