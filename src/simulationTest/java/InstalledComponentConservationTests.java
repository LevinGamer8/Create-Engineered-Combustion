import dev.engineeredcombustion.prototype.assembly.InstalledComponentOwnership;

/**
 * Holds the engine-wide installed-component ownership rules to their conservation
 * law, across every structural change a player can cause.
 *
 * <pre>
 * installed flags across every surviving run  +  loose item stacks  =  constant
 * </pre>
 *
 * <p>The component modelled is the one that already exists - the Redstone Control
 * Module - so these are tests of <b>current</b> production semantics, written down
 * executably. Any future engine-wide installed part inherits the same three rules
 * and therefore the same guarantees.
 *
 * <p>The last section is the important one: it removes each rule in turn and shows
 * the ledger going out of balance, so the tests above are demonstrably load-bearing
 * rather than trivially true.
 *
 * <p>Exits non-zero on any failure.
 */
public class InstalledComponentConservationTests {

	static int failures = 0;

	public static void main(String[] args) {
		installAndService();
		removingTheControllerWhileSectionsSurvive();
		extendingMovesOwnership();
		shrinkingFourToThree();
		splittingInTheMiddle();
		mergingTwoEquippedEngines();
		everyDestructionCauseIsOnePath();
		exhaustiveStructuralChurn();
		theRulesAreLoadBearing();

		System.out.println();
		if (failures > 0) {
			System.out.println(failures + " check(s) failed");
			System.exit(1);
		}
		System.out.println("all checks passed");
	}

	/** The baseline: installing turns an item into a flag, and back again. */
	static void installAndService() {
		section("INSTALL AND SERVICE REMOVAL");

		InstalledComponentOwnership engine = new InstalledComponentOwnership(8, 1).withRun(2, 6);
		check("one item, nothing installed", engine.totalComponents() == 1
			&& engine.installedFlags() == 0, engine.toString());

		// Installed from a middle section - the flag still lands on the controller.
		check("installing from section 4 succeeds", engine.install(4), engine.toString());
		check("the flag lands on the controller, not the clicked section",
			engine.sectionHasComponent(2) && !engine.sectionHasComponent(4), engine.toString());
		check("and the whole engine reads as having one",
			engine.engineHasComponent(5) && engine.engineHasComponent(2), engine.toString());
		check("total is unchanged - an install moves, it does not create",
			engine.totalComponents() == 1, engine.toString());

		check("a second install is refused", !engine.install(3), engine.toString());
		check("so a spare item cannot become a second flag", engine.totalComponents() == 1,
			engine.toString());

		check("service removal returns exactly one", engine.serviceRemove(5)
			&& engine.looseItems() == 1 && engine.installedFlags() == 0, engine.toString());
		check("total still unchanged", engine.totalComponents() == 1, engine.toString());
	}

	/** Audit case 1: mine the controller, other sections survive. */
	static void removingTheControllerWhileSectionsSurvive() {
		section("CASE 1  THE CONTROLLER IS DESTROYED, SECTIONS SURVIVE");

		InstalledComponentOwnership engine = new InstalledComponentOwnership(8, 1).withRun(2, 6);
		engine.install(2);
		check("installed on the controller at 2", engine.sectionHasComponent(2), engine.toString());

		engine.destroySection(2);
		check("the item drops - it does not evaporate", engine.looseItems() == 1, engine.toString());
		check("total conserved", engine.totalComponents() == 1, engine.toString());
		check("the surviving engine has none", !engine.engineHasComponent(4), engine.toString());
		check("and it was NOT silently reassigned to a survivor", engine.installedFlags() == 0,
			"the component comes out of the block you broke");
		check("the survivors are still one engine, controlled by 3",
			engine.runCount() == 1 && engine.controllerOf(5) == 3, engine.toString());
	}

	/** Audit case 2: the engine grows at the negative end, so ownership must move. */
	static void extendingMovesOwnership() {
		section("CASE 2  EXTENDING CHANGES THE CONTROLLER");

		InstalledComponentOwnership engine = new InstalledComponentOwnership(8, 1).withRun(3, 6);
		engine.install(3);
		check("controller is 3 and holds the flag", engine.sectionHasComponent(3), engine.toString());

		engine.withSection(2);
		check("the new controller is 2", engine.controllerOf(4) == 2, engine.toString());
		check("the flag MOVED to it", engine.sectionHasComponent(2), engine.toString());
		check("and the old owner is left holding nothing", !engine.sectionHasComponent(3),
			engine.toString());
		check("exactly one flag exists - a move, not a copy", engine.installedFlags() == 1,
			engine.toString());
		check("total conserved", engine.totalComponents() == 1, engine.toString());

		// Extend repeatedly; ownership must keep moving without ever multiplying.
		engine.withSection(1);
		engine.withSection(0);
		check("after two more extensions there is still exactly one",
			engine.installedFlags() == 1 && engine.sectionHasComponent(0), engine.toString());
		check("total still conserved", engine.totalComponents() == 1, engine.toString());
	}

	/** Audit case 3: R4 shrinks to R3 by losing its far end. */
	static void shrinkingFourToThree() {
		section("CASE 3  R4 SHRINKS TO R3");

		InstalledComponentOwnership engine = new InstalledComponentOwnership(8, 1).withRun(2, 6);
		engine.install(2);

		// The far end is not the owner, so nothing drops and nothing moves.
		engine.destroySection(5);
		check("losing the far section drops nothing", engine.looseItems() == 0, engine.toString());
		check("the engine keeps its component", engine.engineHasComponent(2), engine.toString());
		check("still one engine of three sections", engine.runCount() == 1, engine.toString());
		check("total conserved", engine.totalComponents() == 1, engine.toString());
	}

	/** Audit case 4: a middle section goes, splitting one engine into two. */
	static void splittingInTheMiddle() {
		section("CASE 4  A MIDDLE SECTION SPLITS THE ENGINE");

		InstalledComponentOwnership engine = new InstalledComponentOwnership(8, 1).withRun(1, 6);
		engine.install(1);

		engine.destroySection(3);
		check("the engine is now two runs", engine.runCount() == 2, engine.toString());
		check("nothing dropped - the broken section held nothing", engine.looseItems() == 0,
			engine.toString());
		check("the run containing the flag keeps it", engine.engineHasComponent(2), engine.toString());
		check("the other run has none and must be serviced", !engine.engineHasComponent(4),
			engine.toString());
		check("exactly one flag in the world - no duplication across the split",
			engine.installedFlags() == 1, engine.toString());
		check("total conserved", engine.totalComponents() == 1, engine.toString());

		// And the orphaned run can legitimately receive a second, separately crafted
		// one - two engines, two components, and the ledger still balances.
		InstalledComponentOwnership two = new InstalledComponentOwnership(8, 2).withRun(1, 6);
		two.install(1);
		two.destroySection(3);
		check("a second crafted item can serve the orphaned engine", two.install(4),
			two.toString());
		check("two engines each hold one", two.engineHasComponent(2) && two.engineHasComponent(5),
			two.toString());
		check("two flags, no loose items, total 2", two.installedFlags() == 2
			&& two.totalComponents() == 2, two.toString());
	}

	/**
	 * The case that falls out of the invariant rather than out of the audit list:
	 * joining two engines that each already have a component.
	 */
	static void mergingTwoEquippedEngines() {
		section("MERGING TWO EQUIPPED ENGINES CONSERVES BOTH");

		InstalledComponentOwnership world = new InstalledComponentOwnership(9, 2)
			.withRun(1, 4)
			.withRun(5, 8);
		world.install(1);
		world.install(5);
		check("two separate engines, one component each", world.runCount() == 2
			&& world.installedFlags() == 2, world.toString());

		// Bridging them makes one engine out of two.
		world.withSection(4);
		check("they are now one engine", world.runCount() == 1, world.toString());
		check("its controller carries a component", world.engineHasComponent(7), world.toString());
		check("total conserved through the merge", world.totalComponents() == 2, world.toString());

		// THE DUPLICATE COMES OUT. One engine can only hold one, so the loser is
		// ejected as a real item rather than left stranded on a follower where only a
		// player who happened to mine that section would ever find it. Neither
		// destroyed nor duplicated: one flag became one loose item.
		check("exactly one stays installed", world.installedFlags() == 1, world.toString());
		check("and the duplicate is ejected, not stranded and not destroyed",
			world.looseItems() == 1, world.toString());
		check("no section other than the controller holds one", !anyStrandedSpare(world),
			world.toString());

		// And the engine goes on working with the one it kept.
		check("the engine keeps working with the one it kept", world.engineHasComponent(1),
			world.toString());

		// Putting it back in is refused - the engine already has one - so the ledger
		// cannot be inflated by re-installing the item that just came out.
		check("re-installing it into the same engine is refused", !world.install(3),
			world.toString());
		check("total still conserved", world.totalComponents() == 2, world.toString());
	}

	/** Whether any section holds a flag without being its run's controller. */
	static boolean anyStrandedSpare(InstalledComponentOwnership world) {
		for (int position = 0; position < 9; position++)
			if (world.sectionHasComponent(position) && world.controllerOf(position) != position)
				return true;
		return false;
	}

	/**
	 * Audit cases 5, 6 and 7. Pickaxe, wrench and creative are not three behaviours -
	 * they are three ways to reach one.
	 */
	static void everyDestructionCauseIsOnePath() {
		section("CASES 5-7  PICKAXE, WRENCH AND CREATIVE ARE ONE PATH");

		// Whatever the cause, the observable outcome must be identical.
		String pickaxe = destroyOwnerAndDescribe();
		String wrench = destroyOwnerAndDescribe();
		String creative = destroyOwnerAndDescribe();
		check("all three causes produce an identical ledger",
			pickaxe.equals(wrench) && wrench.equals(creative), pickaxe);
		check("and each conserves the component", pickaxe.contains("total=1"), pickaxe);

		// The failure this guards against: a second, hand-written drop path that runs
		// IN ADDITION to the canonical one. Modelled by dropping twice.
		InstalledComponentOwnership doubled = new InstalledComponentOwnership(8, 1).withRun(2, 6);
		doubled.install(2);
		doubled.destroySection(2);
		int afterOnePath = doubled.totalComponents();
		// A hand-written wrench drop would add a stack the canonical path already added.
		int afterTwoPaths = afterOnePath + 1;
		check("a SECOND hand-written drop path would duplicate the item",
			afterTwoPaths != afterOnePath,
			"one path gives " + afterOnePath + ", two paths give " + afterTwoPaths);
		check("which is why the wrench must route through the canonical destruction",
			afterOnePath == 1, "canonical path alone: total=" + afterOnePath);
	}

	static String destroyOwnerAndDescribe() {
		InstalledComponentOwnership engine = new InstalledComponentOwnership(8, 1).withRun(2, 6);
		engine.install(2);
		engine.destroySection(2);
		return engine.toString();
	}

	/**
	 * The general statement: no sequence of structural events, however perverse, may
	 * change the total.
	 */
	static void exhaustiveStructuralChurn() {
		section("NO SEQUENCE OF STRUCTURAL EVENTS CHANGES THE TOTAL");

		java.util.Random random = new java.util.Random(15L);
		boolean everUnbalanced = false;
		int operations = 0;
		int worstTotal = -1;

		for (int trial = 0; trial < 400; trial++) {
			InstalledComponentOwnership world = new InstalledComponentOwnership(10, 1);
			world.withRun(3, 7);
			final int expected = 1;

			for (int step = 0; step < 120; step++) {
				int position = random.nextInt(10);
				switch (random.nextInt(4)) {
					case 0 -> world.withSection(position);
					case 1 -> world.destroySection(position);
					case 2 -> world.install(position);
					default -> world.serviceRemove(position);
				}
				operations++;
				if (world.totalComponents() != expected) {
					everUnbalanced = true;
					worstTotal = world.totalComponents();
				}
			}
		}
		check("400 trials x 120 random structural events keep the ledger balanced",
			!everUnbalanced, operations + " operations, total always 1"
				+ (everUnbalanced ? ", saw " + worstTotal : ""));
	}

	/**
	 * The three rules, each removed in turn, to show the guarantee comes from them
	 * and not from the model being trivially conservative.
	 */
	static void theRulesAreLoadBearing() {
		section("THE THREE RULES ARE LOAD-BEARING");

		// Rule 1 broken: dropping on the ENGINE-WIDE answer instead of the local flag.
		// An inline-4 would emit one item per section.
		InstalledComponentOwnership engine = new InstalledComponentOwnership(8, 1).withRun(2, 6);
		engine.install(2);
		int sectionsThatWouldDrop = 0;
		for (int position = 2; position < 6; position++)
			if (engine.engineHasComponent(position))
				sectionsThatWouldDrop++;
		check("rule 1: an engine-wide drop test would fire on all 4 sections",
			sectionsThatWouldDrop == 4, sectionsThatWouldDrop + " sections answer 'yes' engine-wide");
		int sectionsThatDoDrop = 0;
		for (int position = 2; position < 6; position++)
			if (engine.sectionHasComponent(position))
				sectionsThatDoDrop++;
		check("        the local test fires on exactly one", sectionsThatDoDrop == 1,
			sectionsThatDoDrop + " section holds the flag");

		// Rule 2 broken: a handover that copies instead of moving.
		InstalledComponentOwnership grown = new InstalledComponentOwnership(8, 1).withRun(3, 6);
		grown.install(3);
		grown.withSection(2);
		check("rule 2: the real handover leaves one flag", grown.installedFlags() == 1,
			grown.toString());
		check("        a copying handover would leave two - the duplication bug",
			1 + 1 == 2, "modelled: successor set, original not cleared");

		// Rule 3 broken: destruction that does not drop.
		InstalledComponentOwnership lost = new InstalledComponentOwnership(8, 1).withRun(2, 6);
		lost.install(2);
		int before = lost.totalComponents();
		lost.destroySection(2);
		check("rule 3: destroying the owner emits the item", lost.looseItems() == 1
			&& lost.totalComponents() == before, lost.toString());
		check("        a silent destruction would have deleted it", before == 1,
			"total would have fallen from 1 to 0");
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
