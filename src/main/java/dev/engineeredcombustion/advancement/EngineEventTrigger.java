package dev.engineeredcombustion.advancement;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.engineeredcombustion.content.engine.EngineEvent;
import dev.engineeredcombustion.content.engine.EngineEventRecord;
import dev.engineeredcombustion.content.engine.EngineEventTracker;
import dev.engineeredcombustion.content.engine.WearCondition;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;

/**
 * The single advancement criterion this mod has.
 *
 * <p>Every one of the mod's advancements is granted by this trigger with a
 * different set of optional filters, which is the whole design: adding an
 * advancement is a few lines of JSON rather than a new Java class that differs
 * from twenty others by one comparison. The milestone asks for exactly this -
 * "one configurable trigger is preferable to one almost-identical Java class per
 * advancement".
 *
 * <h2>Every filter is optional, and absent means "no opinion"</h2>
 * A criterion that names only an event matches every occurrence of it. One that
 * also names a cylinder count matches only engines of that size. Nothing here
 * ever fails a match because a field was not mentioned, so
 * {@code {"event": "engine_started"}} is a complete and useful criterion.
 *
 * <p>The counts are ranges rather than equalities because both shapes are
 * needed: "an inline-4 with all four alight" is
 * {@code active_cylinders: {min: 4}}, while "an inline-4 limping on three" is
 * {@code {min: 3, max: 3}}, and the difference between those two is the whole
 * point of the active-cylinder distinction.
 */
public class EngineEventTrigger extends SimpleCriterionTrigger<EngineEventTrigger.Instance> {

	@Override
	public Codec<Instance> codec() {
		return Instance.CODEC;
	}

	/**
	 * Offers one engine event to every advancement this player has that is
	 * listening for one.
	 *
	 * <p>Server-side only, and called from the engine controller rather than from
	 * anything that scans - see {@link EngineEventTracker}.
	 */
	public void fire(ServerPlayer player, EngineEventRecord record) {
		trigger(player, instance -> instance.matches(record));
	}

	/**
	 * A range over the cylinder counts, closed at both ends and open where a bound
	 * is absent.
	 *
	 * <p>Deliberately not {@code MinMaxBounds.Ints}: that codec is happy to accept
	 * a bare number as shorthand, which reads ambiguously in a file where
	 * {@code 3} could plausibly mean "three" or "at least three", and this
	 * distinction is exactly the one the milestone insists must not be blurred.
	 */
	public record Count(Optional<Integer> min, Optional<Integer> max) {

		public static final Codec<Count> CODEC = RecordCodecBuilder.create(instance -> instance
			.group(Codec.INT.optionalFieldOf("min").forGetter(Count::min),
				Codec.INT.optionalFieldOf("max").forGetter(Count::max))
			.apply(instance, Count::new));

		public boolean matches(int value) {
			if (value < 0)
				// The event does not carry this figure at all. A criterion that asked
				// about it cannot be satisfied by an event that has no answer.
				return min.isEmpty() && max.isEmpty();
			return (min.isEmpty() || value >= min.get()) && (max.isEmpty() || value <= max.get());
		}
	}

	/** {@link WearCondition} as something a codec can read out of JSON. */
	public enum ConditionValue implements StringRepresentable {
		PRISTINE(WearCondition.PRISTINE),
		GOOD(WearCondition.GOOD),
		USED(WearCondition.USED),
		WORN(WearCondition.WORN),
		POOR(WearCondition.POOR),
		CRITICAL(WearCondition.CRITICAL);

		public static final Codec<ConditionValue> CODEC = StringRepresentable.fromEnum(ConditionValue::values);

		private final WearCondition condition;

		ConditionValue(WearCondition condition) {
			this.condition = condition;
		}

		public WearCondition condition() {
			return condition;
		}

		@Override
		public String getSerializedName() {
			return condition.getId();
		}
	}

	/** {@link EngineEvent} as something a codec can read out of JSON. */
	public enum EventValue implements StringRepresentable {
		ASSEMBLED(EngineEvent.ASSEMBLED),
		CRANKING_STARTED(EngineEvent.CRANKING_STARTED),
		ENGINE_STARTED(EngineEvent.ENGINE_STARTED),
		GENERATION_STARTED(EngineEvent.GENERATION_STARTED),
		INLINE_RUNNING(EngineEvent.INLINE_RUNNING),
		CONDITION_REACHED(EngineEvent.CONDITION_REACHED),
		MAINTENANCE_COMPLETED(EngineEvent.MAINTENANCE_COMPLETED),
		ABUSE_STATE(EngineEvent.ABUSE_STATE),
		INVALID_LAYOUT_ATTEMPT(EngineEvent.INVALID_LAYOUT_ATTEMPT);

		public static final Codec<EventValue> CODEC = StringRepresentable.fromEnum(EventValue::values);

		private final EngineEvent event;

		EventValue(EngineEvent event) {
			this.event = event;
		}

		public EngineEvent event() {
			return event;
		}

		@Override
		public String getSerializedName() {
			return event.getId();
		}
	}

	/** Which part a condition event is about. */
	public enum ConditionKindValue implements StringRepresentable {
		MECHANICAL(EngineEventTracker.ConditionKind.MECHANICAL),
		COMPRESSION(EngineEventTracker.ConditionKind.COMPRESSION),
		OVERALL(EngineEventTracker.ConditionKind.OVERALL);

		public static final Codec<ConditionKindValue> CODEC =
			StringRepresentable.fromEnum(ConditionKindValue::values);

		private final EngineEventTracker.ConditionKind kind;

		ConditionKindValue(EngineEventTracker.ConditionKind kind) {
			this.kind = kind;
		}

		public EngineEventTracker.ConditionKind kind() {
			return kind;
		}

		@Override
		public String getSerializedName() {
			return kind.getId();
		}
	}

	/** Which sustained mistreatment an abuse event is about. */
	public enum AbuseKindValue implements StringRepresentable {
		DRY(EngineEventTracker.AbuseKind.DRY),
		ALL_OUT(EngineEventTracker.AbuseKind.ALL_OUT);

		public static final Codec<AbuseKindValue> CODEC = StringRepresentable.fromEnum(AbuseKindValue::values);

		private final EngineEventTracker.AbuseKind kind;

		AbuseKindValue(EngineEventTracker.AbuseKind kind) {
			this.kind = kind;
		}

		public EngineEventTracker.AbuseKind kind() {
			return kind;
		}

		@Override
		public String getSerializedName() {
			return kind.getId();
		}
	}

	/** Which refused build an invalid-layout event is about. */
	public enum LayoutValue implements StringRepresentable {
		SECOND_FLYWHEEL(EngineEventRecord.InvalidLayout.SECOND_FLYWHEEL),
		TOO_MANY_CYLINDERS(EngineEventRecord.InvalidLayout.TOO_MANY_CYLINDERS);

		public static final Codec<LayoutValue> CODEC = StringRepresentable.fromEnum(LayoutValue::values);

		private final EngineEventRecord.InvalidLayout layout;

		LayoutValue(EngineEventRecord.InvalidLayout layout) {
			this.layout = layout;
		}

		public EngineEventRecord.InvalidLayout layout() {
			return layout;
		}

		@Override
		public String getSerializedName() {
			return layout.getId();
		}
	}

	/**
	 * One advancement's worth of filter.
	 *
	 * @param player          the standard player predicate every criterion has
	 * @param event           which event, and the only required field
	 * @param cylinders       how many cylinders the engine must have
	 * @param activeCylinders how many of them must be firing
	 * @param minCondition    the engine must be at least this worn
	 * @param maxCondition    and at most this worn
	 * @param conditionKind   for a condition event, which part reached it
	 * @param abuseKind       for an abuse event, which mistreatment
	 * @param layout          for a refused build, which one
	 * @param improvedTo      for maintenance, the condition it must have reached
	 * @param improvedFrom    for maintenance, the condition it must have come from
	 */
	public record Instance(Optional<ContextAwarePredicate> player, EventValue event, Optional<Count> cylinders,
		Optional<Count> activeCylinders, Optional<ConditionValue> minCondition,
		Optional<ConditionValue> maxCondition, Optional<ConditionKindValue> conditionKind,
		Optional<AbuseKindValue> abuseKind, Optional<LayoutValue> layout, Optional<ConditionValue> improvedTo,
		Optional<ConditionValue> improvedFrom) implements SimpleCriterionTrigger.SimpleInstance {

		public static final Codec<Instance> CODEC = RecordCodecBuilder.create(instance -> instance
			.group(ContextAwarePredicate.CODEC.optionalFieldOf("player").forGetter(Instance::player),
				EventValue.CODEC.fieldOf("event").forGetter(Instance::event),
				Count.CODEC.optionalFieldOf("cylinders").forGetter(Instance::cylinders),
				Count.CODEC.optionalFieldOf("active_cylinders").forGetter(Instance::activeCylinders),
				ConditionValue.CODEC.optionalFieldOf("min_condition").forGetter(Instance::minCondition),
				ConditionValue.CODEC.optionalFieldOf("max_condition").forGetter(Instance::maxCondition),
				ConditionKindValue.CODEC.optionalFieldOf("condition_kind").forGetter(Instance::conditionKind),
				AbuseKindValue.CODEC.optionalFieldOf("abuse_kind").forGetter(Instance::abuseKind),
				LayoutValue.CODEC.optionalFieldOf("layout").forGetter(Instance::layout),
				ConditionValue.CODEC.optionalFieldOf("improved_to").forGetter(Instance::improvedTo),
				ConditionValue.CODEC.optionalFieldOf("improved_from").forGetter(Instance::improvedFrom))
			.apply(instance, Instance::new));

		/**
		 * Whether this event satisfies this filter.
		 *
		 * <p>Reads as a series of early rejections, and each one is skipped entirely
		 * when the filter did not ask about it.
		 */
		public boolean matches(EngineEventRecord record) {
			if (record.event() != event.event())
				return false;
			if (cylinders.isPresent() && !cylinders.get().matches(record.cylinderCount()))
				return false;
			if (activeCylinders.isPresent() && !activeCylinders.get().matches(record.activeCylinders()))
				return false;
			if (minCondition.isPresent() && !record.condition().isAtLeast(minCondition.get().condition()))
				return false;
			if (maxCondition.isPresent() && !record.condition().isAtMost(maxCondition.get().condition()))
				return false;
			if (conditionKind.isPresent() && record.conditionKind() != conditionKind.get().kind())
				return false;
			if (abuseKind.isPresent() && record.abuseKind() != abuseKind.get().kind())
				return false;
			if (layout.isPresent() && record.invalidLayout() != layout.get().layout())
				return false;
			// Maintenance is a pair, and both ends can be constrained: "Back in
			// Service" needs an engine that WAS at least worn and IS now good.
			if (improvedTo.isPresent()
				&& (record.conditionAfter() == null
					|| !record.conditionAfter().isAtMost(improvedTo.get().condition())))
				return false;
			if (improvedFrom.isPresent()
				&& (record.conditionBefore() == null
					|| !record.conditionBefore().isAtLeast(improvedFrom.get().condition())))
				return false;
			return true;
		}
	}
}
