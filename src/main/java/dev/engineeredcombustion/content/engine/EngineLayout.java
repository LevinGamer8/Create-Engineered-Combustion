package dev.engineeredcombustion.content.engine;

/**
 * The geometry of a crankshaft run, with the world abstracted away.
 *
 * <p><b>The single source of truth for engine layout.</b> {@code EngineComponents}
 * has two entry points - a cheap one that only needs block states and a full one
 * that resolves block entities - and both derive their answer from exactly one call
 * to {@link #scan}. That is what makes it impossible for the two to disagree about
 * the same run of crankcases, which is the failure this class was extracted to end:
 * they used to walk the run separately, with different bounds, and reported
 * different controllers, indices and counts for every run longer than
 * {@link EngineTuning#MAX_CYLINDERS}.
 *
 * <p>Free of any Minecraft, NeoForge or Create type, so the layout rules can be
 * driven through every run length on both axes by a plain JDK test - which is
 * exactly what {@code EngineLayoutTests} does.
 *
 * <h2>What the scan guarantees</h2>
 * <ul>
 * <li><b>bounded cost</b> - at most {@code 2 * (MAX_CYLINDERS + 1)} probes for any
 * run of any length, so a player who lays a hundred crankcases in a line costs ten
 * probes per section and never a world scan;</li>
 * <li><b>safe indices</b> - {@link Result#index()} is always a valid index into a
 * {@code MAX_CYLINDERS}-long array and {@link Result#count()} is always in
 * {@code [1, MAX_CYLINDERS]}, whatever the world looks like, so no caller can walk
 * off the end of the simulation's per-cylinder arrays;</li>
 * <li><b>run-global answers</b> - the length is measured across the whole run
 * rather than from the query position outwards, so every section of a run agrees
 * about whether that run is an engine.</li>
 * </ul>
 */
public final class EngineLayout {

	private EngineLayout() {
	}

	/**
	 * How far either walk may travel.
	 *
	 * <p>One more than an engine may have sections, and that one extra step is the
	 * whole of how an over-long run is detected: a walk still on crankcase after
	 * {@code MAX_CYLINDERS + 1} steps has already proven the run too long, so the
	 * scan can stop there instead of measuring how much too long it is.
	 *
	 * <p>That bound is also why the answer is trustworthy. A walk can only be cut
	 * short by the bound if it has already travelled {@code MAX_CYLINDERS + 1}
	 * sections, which by itself makes the total exceed {@code MAX_CYLINDERS}; so a
	 * result reported as {@link EngineAssemblyStatus#COMPLETE} is one where both
	 * walks ended for a real reason.
	 */
	public static final int SCAN_LIMIT = EngineTuning.MAX_CYLINDERS + 1;

	/** What the world says is at one candidate section position. */
	public enum Section {

		/** A Crankshaft section lined up with the run's axis. */
		CRANKSHAFT,

		/** Something else, or nothing - a genuine end of the run. */
		ABSENT,

		/**
		 * The chunk is not loaded, so this position has no answer.
		 *
		 * <p>Emphatically <b>not</b> the same as {@link #ABSENT}. Treating an unloaded
		 * chunk as the end of a run is how an inline-4 across a chunk border used to
		 * come back as an inline-2, or as two engines each inventing a controller.
		 */
		UNLOADED
	}

	/**
	 * Answers what lies a given number of steps along the run's axis from the
	 * section being asked about.
	 */
	@FunctionalInterface
	public interface Probe {

		/**
		 * @param offset signed steps along the axis from the queried section: negative
		 *               towards the negative end of the run, positive towards the
		 *               other. Never 0 - the queried section is a Crankshaft by
		 *               construction
		 */
		Section at(int offset);
	}

	/**
	 * Where one section sits in its run, and whether that run is an engine.
	 *
	 * @param index        the section's 0-based place from the negative end, clamped
	 *                     into {@code [0, MAX_CYLINDERS - 1]}
	 * @param count        how many sections the engine has, clamped into
	 *                     {@code [1, MAX_CYLINDERS]}
	 * @param stepsBack    unclamped steps to the negative end of the visible run,
	 *                     which is what turns a relative answer into a position
	 * @param status       whether this layout may be acted on at all
	 */
	public record Result(int index, int count, int stepsBack, EngineAssemblyStatus status) {

		/**
		 * Whether this section is the one block entity that runs the engine.
		 *
		 * <p>Two conditions, and the second is what stops a long run from being chopped
		 * into several working engines:
		 * <ul>
		 * <li><b>index 0</b> - the scan found no equally-aligned Crankshaft on this
		 * section's negative side, so this really is the negative end of the run. The
		 * index comes from that walk, so this <i>is</i> the negative-end rule rather
		 * than a second opinion about it;</li>
		 * <li><b>the layout is usable</b> - an oversized run has no controller at all,
		 * and neither has one the scan could not see the ends of. A section that merely
		 * happens to sit at index 0 of a partially visible or over-long run therefore
		 * never promotes itself into the controller of a sub-engine.</li>
		 * </ul>
		 */
		public boolean isController() {
			return index == 0 && status.isUsable();
		}

		/** The run is longer than {@link EngineTuning#MAX_CYLINDERS} and is not an engine. */
		public boolean oversized() {
			return status == EngineAssemblyStatus.OVERSIZED;
		}

		/** Whether the whole run was visible, so this layout may be acted on. */
		public boolean isComplete() {
			return status.isUsable();
		}
	}

	/**
	 * Walks the run both ways from one section and reports what it found.
	 *
	 * <p>Three questions answered from one pair of numbers, so no two callers can
	 * ever hold different views of the same run:
	 * <ul>
	 * <li><b>how long</b> - {@code stepsBack + stepsForward + 1}, measured across the
	 * whole run rather than from the query position outwards. That is what makes a
	 * five-section run read as oversized from its last section as readily as from its
	 * first, which the old per-caller walks did not;</li>
	 * <li><b>where the negative end is</b> - where the backwards walk stopped;</li>
	 * <li><b>whether the answer can be trusted</b> - a walk that stopped at an
	 * unloaded chunk did not find an end of the run, it merely ran out of world.</li>
	 * </ul>
	 */
	public static Result scan(Probe probe) {
		int stepsBack = 0;
		boolean sawUnloaded = false;
		while (stepsBack < SCAN_LIMIT) {
			Section section = probe.at(-(stepsBack + 1));
			if (section == Section.UNLOADED) {
				sawUnloaded = true;
				break;
			}
			if (section != Section.CRANKSHAFT)
				break;
			stepsBack++;
		}

		int stepsForward = 0;
		while (stepsForward < SCAN_LIMIT) {
			Section section = probe.at(stepsForward + 1);
			if (section == Section.UNLOADED) {
				sawUnloaded = true;
				break;
			}
			if (section != Section.CRANKSHAFT)
				break;
			stepsForward++;
		}

		int length = stepsBack + stepsForward + 1;
		// Oversize is decided first and deliberately outranks an unloaded neighbour: a
		// run already known to be too long cannot become short enough by loading more
		// of it, so this is a stable answer rather than one that flickers with chunk
		// traffic. Both are fail-closed, so the order only decides which explanation
		// the player is shown.
		EngineAssemblyStatus status = length > EngineTuning.MAX_CYLINDERS ? EngineAssemblyStatus.OVERSIZED
			: sawUnloaded ? EngineAssemblyStatus.INCOMPLETE_CHUNKS
				: EngineAssemblyStatus.COMPLETE;

		// Clamped so that a caller which reads these before checking the status gets a
		// harmless number rather than an exception. They are not meaningful for a run
		// that is not COMPLETE - nothing may run such a layout - but they are safe.
		int index = Math.min(stepsBack, EngineTuning.MAX_CYLINDERS - 1);
		int count = Math.min(Math.max(length, 1), EngineTuning.MAX_CYLINDERS);
		return new Result(index, count, stepsBack, status);
	}
}
