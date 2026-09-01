package dev.engineeredcombustion.prototype.assembly;

/**
 * A pure model of who owns an <b>engine-wide installed component</b>, and of the
 * conservation law that every structural change must obey.
 *
 * <p><b>Prototype. Nothing in the mod depends on this.</b> It lives in
 * {@code src/prototype/java}, which only the {@code simulationTest} source set
 * compiles.
 *
 * <p>It models a component that already exists: the <b>Redstone Control Module</b>,
 * one per engine, installed through a Crankshaft section, owned by the controller
 * and moved on handover. It is deliberately generic - it names no particular item -
 * because the point is the <i>pattern</i>, and any future engine-wide installed part
 * inherits it unchanged.
 *
 * <h2>The law</h2>
 * <pre>
 * installed flags across every surviving run  +  loose item stacks  =  constant
 * </pre>
 * That total may only change when a player crafts or consumes an item. It must be
 * invariant across <b>every</b> structural event: mining, wrenching, extending,
 * shrinking, splitting and controller handover.
 *
 * <p>Note that even installing does not move it. Installing turns a loose item into
 * a flag, so the sum is unchanged - which is exactly why the sum, rather than either
 * half of it, is the thing to check.
 *
 * <h2>Three rules produce it</h2>
 * <ol>
 * <li><b>Local flag, controller read.</b> The flag is a field on one section.
 * "Does this engine have one" resolves through the controller;
 * "should this block drop one" reads the section's own field. Production says why:
 * <i>"an engine-wide answer there would have every section of an inline-4 drop a
 * module the player only ever crafted one of."</i></li>
 * <li><b>Handover moves, never copies.</b> Controller migration sets the successor's
 * flag <i>and clears its own</i>.</li>
 * <li><b>Destruction drops from the holder.</b> Exactly the section whose flag is
 * set emits the item, whatever destroyed it.</li>
 * </ol>
 * Break any one of the three and this model's ledger goes out of balance, which is
 * what the tests demonstrate.
 */
public final class InstalledComponentOwnership {

	/** Absent from the world entirely. */
	private static final int NO_SECTION = -1;

	/**
	 * One straight line of candidate positions. {@code true} where a Crankshaft
	 * section stands.
	 */
	private final boolean[] present;

	/**
	 * Which sections carry an installed flag.
	 *
	 * <p>Per section rather than per world, because a world holds many engines and
	 * the conservation law is about all of them at once. It also lets the model
	 * express the one state a merge can produce - see {@link #resolveControllers}.
	 */
	private final boolean[] flag;

	/** Item stacks loose in the world or in a player's inventory. */
	private int looseItems;

	public InstalledComponentOwnership(int length, int looseItems) {
		this.present = new boolean[length];
		this.flag = new boolean[length];
		this.looseItems = looseItems;
	}

	/** Places a section at {@code position}, then re-resolves controllers. */
	public InstalledComponentOwnership withSection(int position) {
		present[position] = true;
		resolveControllers();
		return this;
	}

	/** Places a contiguous run, the ordinary way an engine is built. */
	public InstalledComponentOwnership withRun(int from, int toExclusive) {
		for (int position = from; position < toExclusive; position++)
			present[position] = true;
		resolveControllers();
		return this;
	}

	// ------------------------------------------------------------------ runs

	/**
	 * The controller of the run containing {@code position}: its lowest-indexed
	 * section, which is the production rule - the negative-most section of a run of
	 * adjacent sections sharing an axis.
	 */
	public int controllerOf(int position) {
		if (position < 0 || position >= present.length || !present[position])
			return NO_SECTION;
		int controller = position;
		while (controller > 0 && present[controller - 1])
			controller--;
		return controller;
	}

	/** Whether {@code position} holds a section at all. */
	public boolean hasSection(int position) {
		return position >= 0 && position < present.length && present[position];
	}

	/**
	 * Whether the engine containing {@code position} has the component installed -
	 * the engine-wide read, which resolves through the controller.
	 */
	public boolean engineHasComponent(int position) {
		int controller = controllerOf(position);
		return controller != NO_SECTION && flag[controller];
	}

	/**
	 * Whether <i>this section</i> carries the flag - the local read, and the one a
	 * drop must use.
	 */
	public boolean sectionHasComponent(int position) {
		return position >= 0 && position < flag.length && flag[position];
	}

	// ------------------------------------------------------------ operations

	/**
	 * Installs one loose item into the engine containing {@code position}.
	 *
	 * <p>Allowed from <b>any</b> section of the engine, matching how the Carburetor
	 * serves an engine from above any one cylinder; the flag lands on the controller
	 * regardless of which section was clicked. Refused if the engine already has one,
	 * which is what stops a second item becoming a second flag.
	 *
	 * @return whether the install happened
	 */
	public boolean install(int position) {
		int controller = controllerOf(position);
		if (controller == NO_SECTION || looseItems <= 0 || runHoldsAny(controller))
			return false;
		looseItems--;
		flag[controller] = true;
		return true;
	}

	/**
	 * The deliberate service removal: the player takes the component back out.
	 *
	 * @return whether anything was removed
	 */
	public boolean serviceRemove(int position) {
		if (!engineHasComponent(position))
			return false;
		flag[controllerOf(position)] = false;
		looseItems++;
		return true;
	}

	/**
	 * Destroys the section at {@code position}, by any cause at all.
	 *
	 * <p><b>This is the single canonical destruction path</b>, and modelling it as
	 * one method is the point. A pickaxe, a creative click, an explosion, a piston
	 * and a wrench are five ways to reach it, not five behaviours: each must end here
	 * so that the drop happens exactly once and exactly when the block ceases to
	 * exist.
	 *
	 * <p>The drop reads the <b>local</b> flag. Reading an engine-wide answer here is
	 * the classic duplication bug - every section of an inline-4 would drop one.
	 */
	public void destroySection(int position) {
		if (!hasSection(position))
			return;
		if (flag[position]) {
			flag[position] = false;
			looseItems++;
		}
		present[position] = false;
		resolveControllers();
	}

	/**
	 * Re-resolves controllers after any shape change, moving the flag if the run it
	 * belongs to now answers to a different section.
	 *
	 * <p>The model of {@code migrateControllerConfigurationTo}, and the whole of rule
	 * 2: it <b>moves</b>. There is one assignment and the old owner is left holding
	 * nothing, because {@code flagAt} is a single value - which is itself the point.
	 * Production achieves the same thing with two statements, and the comment there
	 * says why: <i>"One module, one owner. Clearing it here is what makes the
	 * transfer a move rather than a duplication."</i>
	 */
	private void resolveControllers() {
		for (int position = 0; position < present.length; position++) {
			if (!present[position] || !flag[position])
				continue;
			int controller = controllerOf(position);
			if (controller == position || flag[controller])
				// Either it is already the owner, or the run's controller has one of its
				// own. The second case is what merging two equipped engines produces: the
				// spare stays where it is rather than being destroyed, and it comes back
				// the moment that section is broken. Untidy, and conservative, which is
				// the correct order of priorities.
				continue;
			flag[position] = false;
			flag[controller] = true;
		}
	}

	/** Whether any section of this run carries a flag, owner or stranded spare. */
	private boolean runHoldsAny(int position) {
		int controller = controllerOf(position);
		if (controller == NO_SECTION)
			return false;
		for (int section = controller; section < present.length && present[section]; section++)
			if (flag[section])
				return true;
		return false;
	}

	// --------------------------------------------------------------- ledger

	/** Flags currently held by sections anywhere in the world. */
	public int installedFlags() {
		int total = 0;
		for (boolean held : flag)
			if (held)
				total++;
		return total;
	}

	/** Item stacks loose in the world or in an inventory. */
	public int looseItems() {
		return looseItems;
	}

	/**
	 * The conserved quantity: everything the player owns, wherever it currently is.
	 *
	 * <p>Nothing but crafting or consuming an item may change this number.
	 */
	public int totalComponents() {
		return installedFlags() + looseItems;
	}

	/** How many separate engines currently stand, for the split cases. */
	public int runCount() {
		int runs = 0;
		for (int position = 0; position < present.length; position++)
			if (present[position] && (position == 0 || !present[position - 1]))
				runs++;
		return runs;
	}

	@Override
	public String toString() {
		StringBuilder layout = new StringBuilder();
		for (int position = 0; position < present.length; position++)
			layout.append(!present[position] ? '.' : flag[position] ? 'C' : '#');
		return layout + "  flags=" + installedFlags() + " loose=" + looseItems + " total="
			+ totalComponents();
	}
}
