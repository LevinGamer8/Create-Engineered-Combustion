import dev.engineeredcombustion.content.engine.*;

/**
 * Drives the real {@code EngineLayout} through every run length, from every
 * section, on both axes, with and without unloaded chunks.
 *
 * <p>A pure test: {@code EngineLayout} is deliberately free of Minecraft, NeoForge
 * and Create types - the world reaches it through a {@code Probe} - so the whole
 * layout matrix can be exhausted on a bare JDK instead of being spot-checked in
 * game.
 *
 * <h2>What this is guarding</h2>
 * {@code EngineComponents} has a cheap block-state-only entry point and a full one
 * that resolves block entities. They used to walk the crank run <i>separately</i>,
 * with different bounds, and disagreed about every run longer than
 * {@link EngineTuning#MAX_CYLINDERS}: a run of five reported no oversize flag and a
 * count of 5 when asked from its last section, named an inner section as a
 * controller, and handed out an index past the end of every per-cylinder array in
 * the simulation. Both now derive their answer from one call to
 * {@link EngineLayout#scan}, and this file is what holds that scan to its contract.
 *
 * <h2>The axis</h2>
 * The scan is expressed entirely in <i>signed offsets along the run</i>, never in
 * world directions, so an engine built along X and the same engine built along Z go
 * through identical code. The tests still run both, because "identical by
 * construction" is a claim worth checking rather than asserting.
 *
 * <p>Exits non-zero on any failure.
 */
public class EngineLayoutTests {

	static int failures = 0;

	/** Which world axis a simulated run lies on. Only affects how the probe is built. */
	enum Axis {
		X, Z
	}

	// ---------------------------------------------------------------- fixtures

	/**
	 * A straight run of crankcases in an otherwise empty, fully loaded world.
	 *
	 * <p>Sections are numbered 0..length-1 from the negative end. The probe is built
	 * for one queried section and answers in offsets relative to it, exactly as the
	 * real one does.
	 */
	static class Run {
		final int length;
		final Axis axis;
		/** Sections in this half-open range are in unloaded chunks. */
		int unloadedFrom = Integer.MAX_VALUE;
		int unloadedTo = Integer.MIN_VALUE;

		Run(int length, Axis axis) {
			this.length = length;
			this.axis = axis;
		}

		Run withUnloaded(int from, int to) {
			unloadedFrom = from;
			unloadedTo = to;
			return this;
		}

		boolean isUnloaded(int section) {
			return section >= unloadedFrom && section < unloadedTo;
		}

		/**
		 * The probe a section at {@code queried} would hand to the scan.
		 *
		 * <p>The axis does not appear in this arithmetic, and that is the point rather
		 * than an omission: {@code EngineLayout} works in signed offsets along the run,
		 * so X and Z go through identical code and {@code EngineComponents} supplies
		 * the only two lines that differ - which {@code Direction} each sign maps to.
		 * The tests still run both axes so that a future change which <i>does</i>
		 * introduce an asymmetry is caught here rather than in game.
		 */
		EngineLayout.Probe probeFrom(int queried) {
			return offset -> {
				int section = queried + offset;
				if (isUnloaded(section))
					return EngineLayout.Section.UNLOADED;
				if (section < 0 || section >= length)
					return EngineLayout.Section.ABSENT;
				return EngineLayout.Section.CRANKSHAFT;
			};
		}

		EngineLayout.Result scanFrom(int queried) {
			return EngineLayout.scan(probeFrom(queried));
		}
	}

	// ---------------------------------------------------------------- the tests

	public static void main(String[] args) {
		runLengths();
		everySectionAgrees();
		oversizedRunsHaveNoController();
		indicesAreAlwaysSafe();
		unloadedChunksNeverShortenARun();
		unloadedNeverInventsAController();
		oversizeOutranksUnloaded();

		System.out.println();
		if (failures > 0) {
			System.out.println(failures + " check(s) failed");
			System.exit(1);
		}
		System.out.println("all checks passed");
	}

	/** 1 to 4 sections are engines; 5, 6 and 10 are not, from every section. */
	static void runLengths() {
		section("RUN LENGTHS");

		for (Axis axis : Axis.values())
			for (int length : new int[] { 1, 2, 3, 4 }) {
				boolean allComplete = true;
				boolean allCounted = true;
				for (int queried = 0; queried < length; queried++) {
					EngineLayout.Result result = new Run(length, axis).scanFrom(queried);
					allComplete &= result.status() == EngineAssemblyStatus.COMPLETE;
					allCounted &= result.count() == length && result.index() == queried;
				}
				check(axis + " a run of " + length + " is a complete inline-" + length, allComplete && allCounted,
					"every section reports COMPLETE, count " + length + " and its own index");
			}

		for (Axis axis : Axis.values())
			for (int length : new int[] { 5, 6, 10 }) {
				boolean allOversized = true;
				for (int queried = 0; queried < length; queried++)
					allOversized &= new Run(length, axis).scanFrom(queried)
						.status() == EngineAssemblyStatus.OVERSIZED;
				check(axis + " a run of " + length + " is OVERSIZED from every section", allOversized,
					length + " section(s) checked");
			}
	}

	/**
	 * <b>THE REGRESSION.</b> Every section of one run must report the same shape.
	 * The old code did not: a five-section run called itself oversized when asked
	 * from section 0 and a perfectly good five-cylinder engine when asked from
	 * section 4.
	 */
	static void everySectionAgrees() {
		section("EVERY SECTION OF A RUN AGREES ABOUT IT");

		for (Axis axis : Axis.values())
			for (int length = 1; length <= 10; length++) {
				Run run = new Run(length, axis);
				EngineAssemblyStatus first = run.scanFrom(0)
					.status();
				int firstCount = run.scanFrom(0)
					.count();
				boolean agrees = true;
				for (int queried = 0; queried < length; queried++) {
					EngineLayout.Result result = run.scanFrom(queried);
					agrees &= result.status() == first && result.count() == firstCount;
				}
				check(axis + " run of " + length + ": all sections report one shape", agrees,
					first + ", count " + firstCount);
			}

		// Stated as the specific case the milestone named.
		Run five = new Run(5, Axis.X);
		check("a run of 5 is oversized when asked from its LAST section",
			five.scanFrom(4)
				.status() == EngineAssemblyStatus.OVERSIZED,
			five.scanFrom(4)
				.status()
				.toString());
		check("and its count is clamped to MAX_CYLINDERS, never 5",
			five.scanFrom(4)
				.count() == EngineTuning.MAX_CYLINDERS,
			"count " + five.scanFrom(4)
				.count());
	}

	/** No section of an over-long run may promote itself into a sub-engine's head. */
	static void oversizedRunsHaveNoController() {
		section("AN OVERSIZED RUN HAS NO CONTROLLER ANYWHERE");

		for (Axis axis : Axis.values())
			for (int length : new int[] { 5, 6, 10 }) {
				int controllers = 0;
				for (int queried = 0; queried < length; queried++)
					if (new Run(length, axis).scanFrom(queried)
						.isController())
						controllers++;
				check(axis + " a run of " + length + " nominates no controller", controllers == 0,
					controllers + " controller(s) among " + length + " sections");
			}

		// The complement: a valid run has exactly one, and it is at the negative end.
		for (Axis axis : Axis.values())
			for (int length = 1; length <= EngineTuning.MAX_CYLINDERS; length++) {
				int controllers = 0;
				int controllerSection = -1;
				for (int queried = 0; queried < length; queried++)
					if (new Run(length, axis).scanFrom(queried)
						.isController()) {
						controllers++;
						controllerSection = queried;
					}
				check(axis + " a run of " + length + " has exactly one, at the negative end",
					controllers == 1 && controllerSection == 0,
					controllers + " controller(s), at section " + controllerSection);
			}
	}

	/**
	 * Whatever the world looks like, the index must be a legal subscript into a
	 * {@code MAX_CYLINDERS}-long array and the count must be a legal cylinder count.
	 * This is what stops an over-long run from throwing out of the simulation.
	 */
	static void indicesAreAlwaysSafe() {
		section("INDEX AND COUNT ARE ALWAYS IN RANGE");

		boolean safe = true;
		String worst = "";
		for (Axis axis : Axis.values())
			for (int length = 1; length <= 24; length++)
				for (int queried = 0; queried < length; queried++)
					for (int unloadedFrom = -1; unloadedFrom < length; unloadedFrom++) {
						Run run = new Run(length, axis);
						if (unloadedFrom >= 0)
							run.withUnloaded(unloadedFrom, unloadedFrom + 2);
						EngineLayout.Result result = run.scanFrom(queried);
						boolean ok = result.index() >= 0 && result.index() < EngineTuning.MAX_CYLINDERS
							&& result.count() >= 1 && result.count() <= EngineTuning.MAX_CYLINDERS
							&& result.index() < result.count();
						if (!ok && worst.isEmpty())
							worst = axis + " length " + length + " from " + queried + " unloaded@" + unloadedFrom
								+ " -> index " + result.index() + ", count " + result.count();
						safe &= ok;
					}
		check("every scan over runs of 1-24, all sections, all unload windows", safe,
			worst.isEmpty() ? "index in [0, count) and count in [1, " + EngineTuning.MAX_CYLINDERS + "]" : worst);
	}

	/**
	 * An unloaded chunk is not an absent crankcase. A four-section run whose far end
	 * has unloaded must not read as a two-section engine.
	 */
	static void unloadedChunksNeverShortenARun() {
		section("AN UNLOADED CHUNK IS NOT THE END OF A RUN");

		for (Axis axis : Axis.values()) {
			// R4 with its two positive-end sections in an unloaded chunk, asked from
			// section 0. The visible part is two sections long; it must NOT say so.
			EngineLayout.Result cut = new Run(4, axis).withUnloaded(2, 4)
				.scanFrom(0);
			check(axis + " R4 with its far end unloaded is not an R2",
				cut.status() == EngineAssemblyStatus.INCOMPLETE_CHUNKS,
				cut.status() + ", count " + cut.count());
			check(axis + " and it is not usable as an engine", !cut.isComplete(), cut.status()
				.toString());

			// The same run from the other side.
			EngineLayout.Result fromFar = new Run(4, axis).withUnloaded(0, 2)
				.scanFrom(3);
			check(axis + " R4 with its near end unloaded is not an R2",
				fromFar.status() == EngineAssemblyStatus.INCOMPLETE_CHUNKS, fromFar.status()
					.toString());

			// And when the chunks come back, it is an R4 again - the suspension really
			// is transient rather than a state the engine gets stuck in.
			EngineLayout.Result restored = new Run(4, axis).scanFrom(0);
			check(axis + " and it is an R4 again once the chunks return",
				restored.status() == EngineAssemblyStatus.COMPLETE && restored.count() == 4,
				restored.status() + ", count " + restored.count());
		}
	}

	/**
	 * A follower whose controller is in an unloaded chunk must not become the
	 * controller itself - which is exactly what happens if an unloaded neighbour is
	 * read as "no crankcase there".
	 */
	static void unloadedNeverInventsAController() {
		section("A FOLLOWER NEVER PROMOTES ITSELF WHILE ITS CONTROLLER IS AWAY");

		for (Axis axis : Axis.values()) {
			// Section 2 of an R4, with sections 0 and 1 - including the real controller
			// - unloaded. Its backwards walk stops immediately, so its index is 0.
			EngineLayout.Result orphan = new Run(4, axis).withUnloaded(0, 2)
				.scanFrom(2);
			check(axis + " an orphaned follower reports index 0", orphan.index() == 0, "index " + orphan.index());
			check(axis + " but is NOT a controller", !orphan.isController(),
				orphan.status() + ", isController=" + orphan.isController());
		}
	}

	/**
	 * A run already proven too long stays too long however many more chunks load, so
	 * OVERSIZED must win over INCOMPLETE_CHUNKS. Both are fail-closed, so this only
	 * decides which explanation the player is shown - but a status that flickered
	 * with chunk traffic would be worse than either.
	 */
	static void oversizeOutranksUnloaded() {
		section("OVERSIZE OUTRANKS AN UNLOADED NEIGHBOUR");

		// A run of 10 whose sections 8-9 are unloaded is still unmistakably too long.
		EngineLayout.Result result = new Run(10, Axis.X).withUnloaded(8, 10)
			.scanFrom(3);
		check("a long run with an unloaded tail still reads OVERSIZED",
			result.status() == EngineAssemblyStatus.OVERSIZED, result.status()
				.toString());
		check("and still nominates no controller", !result.isController(),
			"isController=" + result.isController());
	}

	// ---------------------------------------------------------------- harness

	static void section(String title) {
		System.out.println();
		System.out.println("-- " + title);
	}

	static void check(String name, boolean passed, String detail) {
		if (!passed)
			failures++;
		System.out.printf("%-4s %-58s %s%n", passed ? "PASS" : "FAIL", name, detail);
	}
}
