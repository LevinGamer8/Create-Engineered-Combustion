package dev.engineeredcombustion.content.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an engine's tick-by-tick state into the handful of {@link EngineEvent}s
 * worth telling a player about.
 *
 * <p>One of these lives on each engine controller. It is the only thing in the
 * mod that decides whether something "happened", and it is deliberately pure -
 * no Minecraft, no players, no advancement API - so that every rule below is a
 * unit test rather than something that can only be checked by playing.
 *
 * <h2>Two kinds of event, two mechanisms</h2>
 * <dl>
 * <dt>Transitions</dt>
 * <dd>An engine caught, a structure became valid, generation began. Detected by
 * remembering last tick's value and comparing - which is precisely why a reload
 * cannot fake one. A freshly loaded controller starts with
 * {@link #primeTo} called, so its "previous" state is whatever it was restored
 * to, and a restored RUNNING engine has therefore never been seen to
 * <i>become</i> RUNNING.</dd>
 * <dt>Sustained states</dt>
 * <dd>Running dry, running an engine into the ground, an inline-4 limping along
 * on three cylinders. Detected by counting consecutive ticks and firing once a
 * window elapses. A single bad tick is a mistake and must never be rewarded as
 * though it were a decision; the counter resets the moment the condition breaks,
 * so only genuinely continuous abuse gets there.</dd>
 * </dl>
 *
 * <h2>Latching</h2>
 * Sustained events latch when they fire and unlatch when the condition breaks,
 * so holding an engine in an abusive state produces one event rather than one
 * per tick for as long as a player can stand to watch. Advancements are granted
 * once anyway; the latch is about not doing the work, and about not flooding a
 * server with criterion checks.
 */
public final class EngineEventTracker {

	// --- windows -------------------------------------------------------------
	//
	// All quoted in ticks at 20 TPS. Each is a judgement about the difference
	// between an accident and an intention, and each is at the generous end of the
	// range the milestone allows, because being slow to reward is a much smaller
	// failure than rewarding someone who did not mean it.

	/** Running dry, continuously, before it counts as a choice. 15 seconds. */
	public static final int DRY_RUNNING_WINDOW_TICKS = 20 * 15;

	/**
	 * Dry AND oversped AND heavily loaded, continuously. 10 seconds.
	 *
	 * <p>Shorter than the dry window on purpose: three simultaneous abuses cannot
	 * happen by accident the way an empty sump can, so the bar for believing it was
	 * deliberate is lower.
	 */
	public static final int ALL_OUT_ABUSE_WINDOW_TICKS = 20 * 10;

	/**
	 * A running engine holding a steady cylinder count. 5 seconds.
	 *
	 * <p>Long enough that a cylinder dropping out for one revolution during a start
	 * does not read as "running on three", short enough to feel immediate.
	 */
	public static final int STEADY_RUNNING_WINDOW_TICKS = 20 * 5;

	/** Load at or above this counts as "heavily loaded" for the abuse window. */
	public static final float ABUSE_LOAD_THRESHOLD = EngineTuning.HEAVY_LOAD_WARNING_FACTOR;

	// --- transition memory ----------------------------------------------------

	private EnginePhase previousPhase = EnginePhase.STOPPED;
	private boolean previouslyGenerating;
	private boolean previouslyValid;
	private boolean primed;

	// --- sustained counters ---------------------------------------------------

	private int dryRunningTicks;
	private int allOutAbuseTicks;
	private int steadyRunningTicks;
	private int steadyCylinders = -1;
	private int steadyActiveCylinders = -1;

	private boolean dryLatched;
	private boolean allOutLatched;
	private boolean steadyLatched;

	/** The worst condition this engine has been <i>seen to reach</i> while operating. */
	private WearCondition reportedMechanical = WearCondition.PRISTINE;
	private WearCondition reportedCompression = WearCondition.PRISTINE;
	private WearCondition reportedOverall = WearCondition.PRISTINE;

	/**
	 * Adopts a state without treating any of it as having just happened.
	 *
	 * <p><b>The rule that makes reloads safe.</b> Called when a controller is
	 * loaded from disk or takes over an engine, so that everything the world was
	 * already in the middle of is the baseline rather than a fresh event. Without
	 * it, every chunk load would re-award "It Really Started!" to whoever walked
	 * past, which is exactly the failure the milestone calls out.
	 */
	public void primeTo(EnginePhase phase, boolean generating, boolean structureValid, WearCondition mechanical,
		WearCondition compression, WearCondition overall) {
		previousPhase = phase;
		previouslyGenerating = generating;
		previouslyValid = structureValid;
		reportedMechanical = mechanical;
		reportedCompression = compression;
		reportedOverall = overall;
		dryRunningTicks = 0;
		allOutAbuseTicks = 0;
		steadyRunningTicks = 0;
		steadyCylinders = -1;
		steadyActiveCylinders = -1;
		dryLatched = false;
		allOutLatched = false;
		steadyLatched = false;
		primed = true;
	}

	public boolean isPrimed() {
		return primed;
	}

	/**
	 * One tick of an engine, in, and everything that just became true, out.
	 *
	 * <p>Returns an empty list on the overwhelming majority of ticks, which is the
	 * design: an engine running happily is not news.
	 *
	 * @param phase            the engine's phase after this tick
	 * @param generating       whether it is contributing Stress Capacity right now
	 * @param structureValid   whether it is a COMPLETE engine: mechanically valid
	 *                         AND carrying the Camshaft it needs to run. The two are
	 *                         deliberately combined by the caller rather than split
	 *                         here - see {@code CrankshaftBlockEntity}
	 * @param cylinderCount    how many cylinders the engine has
	 * @param activeCylinders  how many of them are actually firing
	 * @param lubrication      the sump's state
	 * @param mechanicalRpm    real shaft speed, whatever is turning it
	 * @param loadFactor       normalised network load, {@code [0, 1]}
	 * @param wornThisTick     whether the parts actually accumulated wear this tick.
	 *                         Condition events are gated on this so that wear which
	 *                         arrived any other way cannot produce one
	 * @param mechanical       bearing condition now
	 * @param compression      the worst cylinder's compression condition now
	 * @param overall          the engine's overall condition now
	 */
	public List<EngineEventRecord> tick(EnginePhase phase, boolean generating, boolean structureValid,
		int cylinderCount, int activeCylinders, LubricationState lubrication, float mechanicalRpm, float loadFactor,
		boolean wornThisTick, WearCondition mechanical, WearCondition compression, WearCondition overall) {

		if (!primed) {
			// Never seen before: adopt, report nothing. Anything else would make the
			// first tick of every engine's life a shower of events.
			primeTo(phase, generating, structureValid, mechanical, compression, overall);
			return List.of();
		}

		List<EngineEventRecord> events = new ArrayList<>(0);

		// --- transitions ------------------------------------------------------

		// "You have built an engine", and since Milestone 15B that means a COMPLETE
		// one. An engine with no Camshaft is mechanically valid - it turns, it is
		// assembled correctly, nothing about it is broken - and it can never fire.
		// Awarding "Some Assembly Required" for one would congratulate the player at
		// exactly the moment they are about to spend twenty minutes cranking a machine
		// that was never going to catch.
		if (structureValid && !previouslyValid)
			events.add(EngineEventRecord.of(EngineEvent.ASSEMBLED, cylinderCount, activeCylinders, overall));

		if (previousPhase == EnginePhase.STOPPED && phase == EnginePhase.CRANKING)
			events.add(EngineEventRecord.of(EngineEvent.CRANKING_STARTED, cylinderCount, activeCylinders, overall));

		// The one transition that means "it caught". See EngineEvent.ENGINE_STARTED
		// for why COASTING -> RUNNING is not this.
		if (previousPhase == EnginePhase.STARTING && phase == EnginePhase.RUNNING)
			events.add(EngineEventRecord.of(EngineEvent.ENGINE_STARTED, cylinderCount, activeCylinders,
				// The condition it started IN, which is what "Still Runs!" needs -
				// an engine that was already critical before anyone turned it over.
				reportedOverall));

		if (generating && !previouslyGenerating)
			events.add(EngineEventRecord.of(EngineEvent.GENERATION_STARTED, cylinderCount, activeCylinders, overall));

		// --- sustained: an engine settled at a cylinder count -----------------

		boolean runningSteadily = phase == EnginePhase.RUNNING && generating && structureValid;
		if (runningSteadily && cylinderCount == steadyCylinders && activeCylinders == steadyActiveCylinders) {
			steadyRunningTicks++;
			if (steadyRunningTicks >= STEADY_RUNNING_WINDOW_TICKS && !steadyLatched) {
				steadyLatched = true;
				events.add(
					EngineEventRecord.of(EngineEvent.INLINE_RUNNING, cylinderCount, activeCylinders, overall));
			}
		} else {
			steadyRunningTicks = runningSteadily ? 1 : 0;
			steadyCylinders = runningSteadily ? cylinderCount : -1;
			steadyActiveCylinders = runningSteadily ? activeCylinders : -1;
			steadyLatched = false;
		}

		// --- sustained: abuse -------------------------------------------------
		//
		// "Moving" rather than "running", deliberately. An engine being motored dry
		// by someone else's network is being destroyed exactly as fast as one doing
		// it to itself, and the player who set that up has earned the same joke.

		boolean moving = Math.abs(mechanicalRpm) > 0.0F && structureValid;
		boolean dry = moving && lubrication == LubricationState.DRY;
		if (dry) {
			dryRunningTicks++;
			if (dryRunningTicks >= DRY_RUNNING_WINDOW_TICKS && !dryLatched) {
				dryLatched = true;
				events.add(EngineEventRecord.abuse(AbuseKind.DRY, cylinderCount, activeCylinders, overall));
			}
		} else {
			dryRunningTicks = 0;
			dryLatched = false;
		}

		boolean allOut = dry && EngineWearMath.isOverspeed(mechanicalRpm)
			&& loadFactor >= ABUSE_LOAD_THRESHOLD;
		if (allOut) {
			allOutAbuseTicks++;
			if (allOutAbuseTicks >= ALL_OUT_ABUSE_WINDOW_TICKS && !allOutLatched) {
				allOutLatched = true;
				events.add(EngineEventRecord.abuse(AbuseKind.ALL_OUT, cylinderCount, activeCylinders, overall));
			}
		} else {
			allOutAbuseTicks = 0;
			allOutLatched = false;
		}

		// --- conditions reached through operation -----------------------------
		//
		// Gated on the engine having actually worn this tick, which is what keeps
		// this honest: wear loaded from disk, carried in on an item or set by any
		// future command changes the number without ever passing through here.

		if (wornThisTick) {
			if (mechanical.isAtLeast(reportedMechanical) && mechanical != reportedMechanical) {
				reportedMechanical = mechanical;
				events.add(EngineEventRecord.condition(ConditionKind.MECHANICAL, mechanical, cylinderCount,
					activeCylinders, overall));
			}
			if (compression.isAtLeast(reportedCompression) && compression != reportedCompression) {
				reportedCompression = compression;
				events.add(EngineEventRecord.condition(ConditionKind.COMPRESSION, compression, cylinderCount,
					activeCylinders, overall));
			}
			if (overall.isAtLeast(reportedOverall) && overall != reportedOverall) {
				reportedOverall = overall;
				events.add(EngineEventRecord.condition(ConditionKind.OVERALL, overall, cylinderCount,
					activeCylinders, overall));
			}
		}

		previousPhase = phase;
		previouslyGenerating = generating;
		previouslyValid = structureValid;
		return events;
	}

	/**
	 * Records that maintenance improved this engine, and by how much.
	 *
	 * <p>Called from the interaction that replaced the part rather than from the
	 * tick, because "someone fitted a better piston" is not something a state
	 * comparison can distinguish from "someone fitted a worse one" after the fact.
	 * The before-and-after conditions come from the item stacks themselves.
	 */
	public EngineEventRecord maintenance(WearCondition before, WearCondition after, int cylinderCount,
		int activeCylinders) {
		// Only an actual improvement is maintenance. Putting the same worn part back
		// is not a repair, and must not read as one.
		if (!before.isAtLeast(after) || before == after)
			return null;
		reportedMechanical = WearCondition.PRISTINE;
		reportedCompression = WearCondition.PRISTINE;
		reportedOverall = after;
		return EngineEventRecord.maintenance(before, after, cylinderCount, activeCylinders);
	}

	/** Resets the "worst seen" ladder after a rebuild, so a re-worn part reports again. */
	public void forgetReportedConditions(WearCondition mechanical, WearCondition compression,
		WearCondition overall) {
		reportedMechanical = mechanical;
		reportedCompression = compression;
		reportedOverall = overall;
	}

	// --- payload types --------------------------------------------------------

	/** Which part of the engine a {@link EngineEvent#CONDITION_REACHED} is about. */
	public enum ConditionKind {
		MECHANICAL("mechanical"),
		COMPRESSION("compression"),
		OVERALL("overall");

		private final String id;

		ConditionKind(String id) {
			this.id = id;
		}

		public String getId() {
			return id;
		}

		public static ConditionKind byId(String id) {
			for (ConditionKind kind : values())
				if (kind.id.equals(id))
					return kind;
			return null;
		}
	}

	/** Which flavour of mistreatment an {@link EngineEvent#ABUSE_STATE} is about. */
	public enum AbuseKind {
		/** Turning, with nothing in the sump, for a sustained period. */
		DRY("dry"),
		/** Dry and oversped and heavily loaded, all at once, for a sustained period. */
		ALL_OUT("all_out");

		private final String id;

		AbuseKind(String id) {
			this.id = id;
		}

		public String getId() {
			return id;
		}

		public static AbuseKind byId(String id) {
			for (AbuseKind kind : values())
				if (kind.id.equals(id))
					return kind;
			return null;
		}
	}
}
