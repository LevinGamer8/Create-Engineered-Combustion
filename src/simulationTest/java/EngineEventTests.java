import dev.engineeredcombustion.content.engine.*;
import java.util.List;

/**
 * The advancement event layer, exercised without Minecraft, players or an
 * advancement API.
 *
 * <p>Every rule about when an achievement may be granted lives in
 * {@link EngineEventTracker}, and every one of them is checked here. That is the
 * whole point of keeping the layer pure: "a chunk load must not re-award It
 * Really Started!" is a claim that can be tested in a millisecond rather than
 * one that can only be checked by reloading a world and watching the corner of
 * the screen.
 *
 * <p>Exits non-zero on any failure.
 */
public class EngineEventTests {

	static int failures = 0;

	/** A tracker already living in a settled world, so transitions mean something. */
	static EngineEventTracker settled(EnginePhase phase, boolean generating) {
		EngineEventTracker tracker = new EngineEventTracker();
		tracker.primeTo(phase, generating, true, WearCondition.PRISTINE, WearCondition.PRISTINE,
			WearCondition.PRISTINE);
		return tracker;
	}

	/** One ordinary tick of a healthy running engine. */
	static List<EngineEventRecord> run(EngineEventTracker tracker, EnginePhase phase, boolean generating,
		int cylinders, int active) {
		return tracker.tick(phase, generating, true, cylinders, active, LubricationState.NORMAL, 192.0F, 0.5F,
			false, WearCondition.PRISTINE, WearCondition.PRISTINE, WearCondition.PRISTINE);
	}

	static boolean has(List<EngineEventRecord> events, EngineEvent event) {
		return events.stream().anyMatch(record -> record.event() == event);
	}

	static EngineEventRecord find(List<EngineEventRecord> events, EngineEvent event) {
		return events.stream().filter(record -> record.event() == event).findFirst().orElse(null);
	}

	public static void main(String[] args) {
		anUnprimedTrackerReportsNothing();
		reloadsDoNotReAwardAnything();
		startingIsTheOnlyPathToStarted();
		crankingIsItsOwnEvent();
		generationIsNotRotation();
		assemblyFiresOnceWhenItBecomesValid();
		sustainedRunningNeedsToBeSustained();
		dryRunningNeedsToBeSustained();
		allOutAbuseNeedsEverythingAtOnce();
		conditionsOnlyCountWhenTheEngineActuallyWore();
		maintenanceIsAnImprovementOrItIsNothing();
		theStartedEventCarriesTheConditionItStartedIn();

		System.out.println();
		if (failures > 0) {
			System.out.println(failures + " check(s) failed");
			System.exit(1);
		}
		System.out.println("all checks passed");
	}

	/** A. A tracker that has never seen this engine adopts it silently. */
	static void anUnprimedTrackerReportsNothing() {
		section("A  A FRESH TRACKER ADOPTS, IT DOES NOT REPORT");

		EngineEventTracker tracker = new EngineEventTracker();
		check("it starts unprimed", !tracker.isPrimed(), "nothing seen yet");

		// An engine that is already running, generating and valid - the state a
		// chunk load hands over. Not one event of it may be news.
		List<EngineEventRecord> events = run(tracker, EnginePhase.RUNNING, true, 4, 4);
		check("adopting a running engine reports nothing", events.isEmpty(), events.size() + " event(s)");
		check("and it is primed afterwards", tracker.isPrimed(), "primed");
	}

	/** B. THE RELOAD RULE. Restoring a phase is not passing through one. */
	static void reloadsDoNotReAwardAnything() {
		section("B  A RELOAD RE-AWARDS NOTHING");

		// The exact shape of a chunk load: a controller wakes up on an engine that
		// was already running and generating, and keeps ticking.
		EngineEventTracker tracker = new EngineEventTracker();
		run(tracker, EnginePhase.RUNNING, true, 4, 4);
		boolean quiet = true;
		for (int tick = 0; tick < 200; tick++)
			quiet &= run(tracker, EnginePhase.RUNNING, true, 4, 4).isEmpty()
				|| tick >= EngineEventTracker.STEADY_RUNNING_WINDOW_TICKS - 2;
		check("a restored RUNNING engine never fires ENGINE_STARTED", quiet,
			"only the sustained running event, which is a state and not a transition");

		// And explicitly: over the whole of that, not one start.
		EngineEventTracker second = new EngineEventTracker();
		boolean started = false;
		for (int tick = 0; tick < 200; tick++)
			started |= has(run(second, EnginePhase.RUNNING, true, 4, 4), EngineEvent.ENGINE_STARTED);
		check("nor at any point in two hundred ticks of it", !started, "no ENGINE_STARTED");

		// Priming again mid-life - a controller handing over to another - is equally
		// silent.
		EngineEventTracker handover = settled(EnginePhase.RUNNING, true);
		handover.primeTo(EnginePhase.RUNNING, true, true, WearCondition.WORN, WearCondition.WORN,
			WearCondition.WORN);
		check("re-priming reports nothing either",
			run(handover, EnginePhase.RUNNING, true, 4, 4).isEmpty(), "silent handover");
	}

	/** C. Only STARTING -> RUNNING is a start. */
	static void startingIsTheOnlyPathToStarted() {
		section("C  ONLY STARTING -> RUNNING IS A START");

		EngineEventTracker caught = settled(EnginePhase.STARTING, false);
		check("STARTING -> RUNNING is the catch",
			has(run(caught, EnginePhase.RUNNING, false, 1, 1), EngineEvent.ENGINE_STARTED),
			"ENGINE_STARTED fired");

		// A coasting engine picking its fuel back up was already a running engine.
		EngineEventTracker refuelled = settled(EnginePhase.COASTING, false);
		check("COASTING -> RUNNING is not",
			!has(run(refuelled, EnginePhase.RUNNING, false, 1, 1), EngineEvent.ENGINE_STARTED),
			"an engine picking its fuel back up had already started");

		// And cranking alone is emphatically not starting.
		EngineEventTracker cranking = settled(EnginePhase.STOPPED, false);
		check("CRANKING alone is not a start",
			!has(run(cranking, EnginePhase.CRANKING, false, 1, 0), EngineEvent.ENGINE_STARTED),
			"turning it over is not starting it");

		// Nor is being motored by a stronger network while already running.
		EngineEventTracker motored = settled(EnginePhase.RUNNING, false);
		check("being motored while running is not a start",
			!has(run(motored, EnginePhase.RUNNING, false, 1, 1), EngineEvent.ENGINE_STARTED),
			"no transition, no event");
	}

	/** D. Cranking is its own moment. */
	static void crankingIsItsOwnEvent() {
		section("D  CRANKING IS ITS OWN MOMENT");

		EngineEventTracker tracker = settled(EnginePhase.STOPPED, false);
		check("STOPPED -> CRANKING fires it",
			has(run(tracker, EnginePhase.CRANKING, false, 1, 0), EngineEvent.CRANKING_STARTED), "fired");
		check("and it does not fire again while cranking continues",
			!has(run(tracker, EnginePhase.CRANKING, false, 1, 0), EngineEvent.CRANKING_STARTED),
			"one event per attempt");

		EngineEventTracker already = settled(EnginePhase.RUNNING, true);
		check("a running engine never fires it",
			!has(run(already, EnginePhase.RUNNING, true, 1, 1), EngineEvent.CRANKING_STARTED), "not cranking");
	}

	/** E. ROTATION IS NOT GENERATION. */
	static void generationIsNotRotation() {
		section("E  ROTATION IS NOT GENERATION");

		// An engine turning fast, in a valid structure, with cylinders - and not
		// generating. Exactly the externally-motored dead engine the mod teaches.
		EngineEventTracker motored = settled(EnginePhase.CRANKING, false);
		boolean fired = false;
		for (int tick = 0; tick < 400; tick++)
			fired |= has(motored.tick(EnginePhase.CRANKING, false, true, 4, 0, LubricationState.NORMAL, 220.0F,
				0.5F, false, WearCondition.PRISTINE, WearCondition.PRISTINE, WearCondition.PRISTINE),
				EngineEvent.GENERATION_STARTED);
		check("a motored engine that generates nothing never fires GENERATION_STARTED", !fired,
			"twenty seconds at 220 RPM, no generation");

		EngineEventTracker real = settled(EnginePhase.RUNNING, false);
		check("but an engine that starts generating does",
			has(run(real, EnginePhase.RUNNING, true, 1, 1), EngineEvent.GENERATION_STARTED), "fired");
		check("and only on the tick it began",
			!has(run(real, EnginePhase.RUNNING, true, 1, 1), EngineEvent.GENERATION_STARTED), "once");
	}

	/** F. Assembly is the structure becoming valid, not a part being crafted. */
	static void assemblyFiresOnceWhenItBecomesValid() {
		section("F  ASSEMBLY IS THE STRUCTURE BECOMING VALID");

		EngineEventTracker tracker = new EngineEventTracker();
		tracker.primeTo(EnginePhase.STOPPED, false, false, WearCondition.PRISTINE, WearCondition.PRISTINE,
			WearCondition.PRISTINE);
		List<EngineEventRecord> events = tracker.tick(EnginePhase.STOPPED, false, true, 1, 0,
			LubricationState.NORMAL, 0.0F, 0.0F, false, WearCondition.PRISTINE, WearCondition.PRISTINE,
			WearCondition.PRISTINE);
		check("an invalid structure becoming valid fires ASSEMBLED", has(events, EngineEvent.ASSEMBLED), "fired");
		check("and it carries the cylinder count",
			find(events, EngineEvent.ASSEMBLED).cylinderCount() == 1, "inline-1");
		check("staying valid does not fire it again",
			!has(run(tracker, EnginePhase.STOPPED, false, 1, 0), EngineEvent.ASSEMBLED), "once per assembly");
	}

	/** G. A cylinder count has to hold before it is a fact. */
	static void sustainedRunningNeedsToBeSustained() {
		section("G  A CYLINDER COUNT MUST HOLD TO COUNT");

		EngineEventTracker tracker = settled(EnginePhase.RUNNING, true);
		int fireTick = -1;
		for (int tick = 0; tick < EngineEventTracker.STEADY_RUNNING_WINDOW_TICKS + 20; tick++)
			if (has(run(tracker, EnginePhase.RUNNING, true, 4, 4), EngineEvent.INLINE_RUNNING) && fireTick < 0)
				fireTick = tick;
		check("an inline-4 running steadily eventually reports itself", fireTick > 0,
			"fired after " + fireTick + " tick(s)");
		check("but not immediately", fireTick >= EngineEventTracker.STEADY_RUNNING_WINDOW_TICKS - 1,
			"window is " + EngineEventTracker.STEADY_RUNNING_WINDOW_TICKS + " ticks");

		// A count that keeps changing never settles, which is what stops a start
		// sequence from reporting every cylinder count on its way up.
		EngineEventTracker flickering = settled(EnginePhase.RUNNING, true);
		boolean fired = false;
		for (int tick = 0; tick < 400; tick++)
			fired |= has(run(flickering, EnginePhase.RUNNING, true, 4, tick % 2 == 0 ? 4 : 3),
				EngineEvent.INLINE_RUNNING);
		check("a flickering cylinder count never settles", !fired, "twenty seconds of alternating, no event");

		// THREE OF FOUR. The payload has to distinguish it from a healthy four.
		EngineEventTracker limping = settled(EnginePhase.RUNNING, true);
		EngineEventRecord record = null;
		for (int tick = 0; tick < 400 && record == null; tick++)
			record = find(run(limping, EnginePhase.RUNNING, true, 4, 3), EngineEvent.INLINE_RUNNING);
		check("an inline-4 on three reports 4 cylinders and 3 active",
			record != null && record.cylinderCount() == 4 && record.activeCylinders() == 3,
			record == null ? "never fired" : record.cylinderCount() + " cylinders, " + record.activeCylinders()
				+ " active");
	}

	/** H. Fifteen dry seconds is a decision; one dry tick is a mistake. */
	static void dryRunningNeedsToBeSustained() {
		section("H  RUNNING DRY MUST BE SUSTAINED");

		EngineEventTracker tracker = settled(EnginePhase.RUNNING, true);
		boolean early = false;
		for (int tick = 0; tick < EngineEventTracker.DRY_RUNNING_WINDOW_TICKS - 1; tick++)
			early |= has(dryTick(tracker, 192.0F, 0.0F), EngineEvent.ABUSE_STATE);
		check("nothing fires before the window elapses", !early,
			(EngineEventTracker.DRY_RUNNING_WINDOW_TICKS / 20.0F) + " seconds of it");
		check("and it fires once the window elapses",
			has(dryTick(tracker, 192.0F, 0.0F), EngineEvent.ABUSE_STATE), "fired");
		check("but only once", !has(dryTick(tracker, 192.0F, 0.0F), EngineEvent.ABUSE_STATE), "latched");

		// THE MISTAKE RULE. A dry tick here and there must never accumulate.
		EngineEventTracker interrupted = settled(EnginePhase.RUNNING, true);
		boolean fired = false;
		for (int tick = 0; tick < 2000; tick++)
			fired |= tick % 2 == 0
				? has(dryTick(interrupted, 192.0F, 0.0F), EngineEvent.ABUSE_STATE)
				: has(run(interrupted, EnginePhase.RUNNING, true, 1, 1), EngineEvent.ABUSE_STATE);
		check("a hundred seconds of alternating dry and wet never gets there", !fired,
			"the counter resets whenever the oil comes back");

		// A stationary dry engine is not being abused - it is just parked.
		EngineEventTracker parked = settled(EnginePhase.STOPPED, false);
		boolean parkedFired = false;
		for (int tick = 0; tick < 2000; tick++)
			parkedFired |= has(parked.tick(EnginePhase.STOPPED, false, true, 1, 0, LubricationState.DRY, 0.0F,
				0.0F, false, WearCondition.PRISTINE, WearCondition.PRISTINE, WearCondition.PRISTINE),
				EngineEvent.ABUSE_STATE);
		check("a stopped dry engine is parked, not abused", !parkedFired, "no rotation, no event");
	}

	static List<EngineEventRecord> dryTick(EngineEventTracker tracker, float rpm, float load) {
		return tracker.tick(EnginePhase.RUNNING, true, true, 1, 1, LubricationState.DRY, rpm, load, false,
			WearCondition.PRISTINE, WearCondition.PRISTINE, WearCondition.PRISTINE);
	}

	/** I. All-out abuse needs every part of it, at once, for long enough. */
	static void allOutAbuseNeedsEverythingAtOnce() {
		section("I  ALL-OUT ABUSE NEEDS EVERYTHING AT ONCE");

		// Dry and oversped and loaded. Overspeed is 20 % over the rating, load is
		// past the heavy-load threshold.
		float fast = EngineTuning.RATED_CONTINUOUS_RPM * 1.3F;
		EngineEventTracker tracker = settled(EnginePhase.RUNNING, true);
		int allOutTick = -1;
		for (int tick = 0; tick < EngineEventTracker.DRY_RUNNING_WINDOW_TICKS + 40; tick++) {
			List<EngineEventRecord> events = dryTick(tracker, fast, 1.0F);
			EngineEventRecord abuse = find(events, EngineEvent.ABUSE_STATE);
			if (abuse != null && abuse.abuseKind() == EngineEventTracker.AbuseKind.ALL_OUT && allOutTick < 0)
				allOutTick = tick;
		}
		check("everything at once eventually fires the all-out event", allOutTick > 0,
			"fired after " + allOutTick + " tick(s)");
		check("after its own, shorter window",
			allOutTick >= EngineEventTracker.ALL_OUT_ABUSE_WINDOW_TICKS - 1
				&& allOutTick < EngineEventTracker.DRY_RUNNING_WINDOW_TICKS,
			"window is " + EngineEventTracker.ALL_OUT_ABUSE_WINDOW_TICKS + " ticks, sooner than the dry window");

		// Each single factor alone must not produce it.
		check("dry alone does not", !firesAllOut(LubricationState.DRY, 192.0F, 0.0F), "no overspeed, no load");
		check("overspeed alone does not", !firesAllOut(LubricationState.NORMAL, 260.0F, 1.0F), "oil is fine");
		check("load alone does not", !firesAllOut(LubricationState.NORMAL, 192.0F, 1.0F), "oil is fine");
		check("dry and fast but unloaded does not", !firesAllOut(LubricationState.DRY, 260.0F, 0.0F),
			"nothing is being asked of it");
		check("dry and loaded but at its rated speed does not",
			!firesAllOut(LubricationState.DRY, 192.0F, 1.0F), "not oversped");
	}

	static boolean firesAllOut(LubricationState oil, float rpm, float load) {
		EngineEventTracker tracker = settled(EnginePhase.RUNNING, true);
		for (int tick = 0; tick < EngineEventTracker.DRY_RUNNING_WINDOW_TICKS + 100; tick++) {
			EngineEventRecord abuse = find(tracker.tick(EnginePhase.RUNNING, true, true, 1, 1, oil, rpm, load,
				false, WearCondition.PRISTINE, WearCondition.PRISTINE, WearCondition.PRISTINE),
				EngineEvent.ABUSE_STATE);
			if (abuse != null && abuse.abuseKind() == EngineEventTracker.AbuseKind.ALL_OUT)
				return true;
		}
		return false;
	}

	/** J. THE DEBUG RULE. Wear that was not earned cannot award anything. */
	static void conditionsOnlyCountWhenTheEngineActuallyWore() {
		section("J  A CONDITION ONLY COUNTS IF THE ENGINE WORE ITS WAY THERE");

		// Wear appearing out of nowhere - loaded from disk, carried in on an item,
		// set by any future command - with wornThisTick false.
		EngineEventTracker injected = settled(EnginePhase.RUNNING, true);
		boolean fired = false;
		for (int tick = 0; tick < 100; tick++)
			fired |= has(injected.tick(EnginePhase.RUNNING, true, true, 1, 1, LubricationState.NORMAL, 192.0F,
				0.5F, false, WearCondition.CRITICAL, WearCondition.CRITICAL, WearCondition.CRITICAL),
				EngineEvent.CONDITION_REACHED);
		check("wear that simply appeared awards nothing", !fired,
			"a hundred ticks at CRITICAL without wearing, no event");

		// The same wear, arrived at by running.
		EngineEventTracker earned = settled(EnginePhase.RUNNING, true);
		List<EngineEventRecord> events = earned.tick(EnginePhase.RUNNING, true, true, 1, 1,
			LubricationState.DRY, 192.0F, 0.5F, true, WearCondition.CRITICAL, WearCondition.CRITICAL,
			WearCondition.CRITICAL);
		check("wear the engine actually accumulated does", has(events, EngineEvent.CONDITION_REACHED), "fired");

		// It climbs a ladder: each band reports once, and never backwards.
		EngineEventTracker ladder = settled(EnginePhase.RUNNING, true);
		int reports = 0;
		for (WearCondition condition : new WearCondition[] { WearCondition.GOOD, WearCondition.GOOD,
			WearCondition.USED, WearCondition.USED, WearCondition.WORN, WearCondition.USED, WearCondition.WORN })
			reports += ladder.tick(EnginePhase.RUNNING, true, true, 1, 1, LubricationState.NORMAL, 192.0F, 0.5F,
				true, condition, WearCondition.PRISTINE, condition).size();
		check("each band reports once on the way up and never on the way back down", reports == 6,
			reports + " event(s) for GOOD, GOOD, USED, USED, WORN, USED, WORN - three bands, two kinds each");
	}

	/** K. Putting the same worn part back is not a repair. */
	static void maintenanceIsAnImprovementOrItIsNothing() {
		section("K  MAINTENANCE IS AN IMPROVEMENT OR IT IS NOTHING");

		EngineEventTracker tracker = settled(EnginePhase.STOPPED, false);
		check("a worn part replaced by a fresh one is maintenance",
			tracker.maintenance(WearCondition.POOR, WearCondition.PRISTINE, 4, 0) != null, "POOR -> PRISTINE");
		check("the same worn part put back is not",
			tracker.maintenance(WearCondition.POOR, WearCondition.POOR, 4, 0) == null, "POOR -> POOR");
		check("nor is fitting a worse one",
			tracker.maintenance(WearCondition.GOOD, WearCondition.CRITICAL, 4, 0) == null, "GOOD -> CRITICAL");

		EngineEventRecord record = tracker.maintenance(WearCondition.CRITICAL, WearCondition.GOOD, 4, 0);
		check("and the record carries both ends of the repair",
			record != null && record.conditionBefore() == WearCondition.CRITICAL
				&& record.conditionAfter() == WearCondition.GOOD,
			record == null ? "no record" : record.conditionBefore() + " -> " + record.conditionAfter());
	}

	/** L. "Still Runs!" needs the condition BEFORE the start, not after. */
	static void theStartedEventCarriesTheConditionItStartedIn() {
		section("L  A START CARRIES THE CONDITION IT STARTED IN");

		EngineEventTracker tracker = new EngineEventTracker();
		tracker.primeTo(EnginePhase.STARTING, false, true, WearCondition.CRITICAL, WearCondition.CRITICAL,
			WearCondition.CRITICAL);
		List<EngineEventRecord> events = tracker.tick(EnginePhase.RUNNING, false, true, 1, 1,
			LubricationState.NORMAL, 64.0F, 0.0F, false, WearCondition.CRITICAL, WearCondition.CRITICAL,
			WearCondition.CRITICAL);
		EngineEventRecord started = find(events, EngineEvent.ENGINE_STARTED);
		check("a critical engine that catches reports that it was critical",
			started != null && started.condition() == WearCondition.CRITICAL,
			started == null ? "never fired" : started.condition().toString());

		EngineEventTracker healthy = new EngineEventTracker();
		healthy.primeTo(EnginePhase.STARTING, false, true, WearCondition.PRISTINE, WearCondition.PRISTINE,
			WearCondition.PRISTINE);
		EngineEventRecord fresh = find(healthy.tick(EnginePhase.RUNNING, false, true, 1, 1,
			LubricationState.NORMAL, 64.0F, 0.0F, false, WearCondition.PRISTINE, WearCondition.PRISTINE,
			WearCondition.PRISTINE), EngineEvent.ENGINE_STARTED);
		check("and a healthy one reports that it was healthy",
			fresh != null && fresh.condition() == WearCondition.PRISTINE,
			fresh == null ? "never fired" : fresh.condition().toString());
	}

	// ---------------------------------------------------------------- harness

	static void section(String title) {
		System.out.println();
		System.out.println("-- " + title);
	}

	static void check(String name, boolean passed, String detail) {
		if (!passed)
			failures++;
		System.out.printf("%s %-62s %s%n", passed ? "PASS" : "FAIL", name, detail);
	}
}
