package dev.engineeredcombustion.ponder;

import static dev.engineeredcombustion.ponder.PonderEngine.centre;

import dev.engineeredcombustion.registry.ECItems;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/**
 * How an engine is started, grown and controlled.
 *
 * <p>The three scenes that teach the mod's actual verbs. Between them they carry
 * the two ideas a player has to hold on to for anything else to make sense:
 * <b>an engine has to be cranked before it can run</b>, and <b>rotation is not
 * generation</b>.
 *
 * <h2>Starting is scripted, not faked</h2>
 * A real start is several cycles long and partly random - a healthy engine
 * usually catches quickly, a tired one may take many attempts. That is good
 * gameplay and terrible tutorial, so the scene <i>scripts its presentation</i>:
 * it shows cranking, then firing attempts, then a catch, at a pace a person can
 * follow. It does not reach into the simulation and make real starts
 * deterministic, which would be changing the game to suit the tutorial.
 *
 * <h2>What each step points at</h2>
 * The engine's positions come from {@link PonderEngine} rather than from
 * constants written out here, and each step outlines the component its sentence
 * is about: the ignition lines mark the crankcase that carries the switch, the
 * throttle lines mark the Carburetor, and the coasting line marks the Flywheel.
 * The two blocks that are not part of an engine - Create's Hand Crank and the
 * redstone line - are the only positions named directly, and they are named once.
 */
public class EngineOperationScenes {

	/** The inline-1 the starting and controls scenes are staged on. */
	private static final PonderEngine ENGINE = PonderEngine.of(3, 2, 2, 1);

	/**
	 * The inline scene's engine: four sections, with its Carburetor and Oil Sump
	 * on the LAST one.
	 *
	 * <p>That end rather than the near one because the scene reveals it first and
	 * grows away from it - so the very first thing shown is a complete, runnable
	 * inline-1 rather than a crankshaft with no fuel or oil. See the structure
	 * generator's note on {@code accessories_on}.
	 */
	private static final PonderEngine INLINE = PonderEngine.endLoaded(2, 2, 2, 4);

	/** Create's Hand Crank, beyond the far end of the shaft. */
	private static final BlockPos HAND_CRANK = new BlockPos(2, 2, 2);

	/** The redstone line the controls scene switches, and the lever driving it. */
	private static final BlockPos REDSTONE = new BlockPos(5, 1, 2);
	private static final BlockPos LEVER = new BlockPos(6, 1, 2);

	/** The fifth crankshaft section the inline scene has refused. */
	private static final BlockPos FIFTH_SECTION = new BlockPos(1, 2, 2);

	/** B5. Starting an Engine. */
	public static void startingAnEngine(SceneBuilder scene, SceneBuildingUtil util) {
		scene.title("starting_an_engine", "Starting an Engine");
		scene.configureBasePlate(0, 0, 5);
		scene.scaleSceneView(0.9F);
		scene.showBasePlate();
		scene.idle(10);
		scene.world()
			.showSection(util.select()
				.layersFrom(1), Direction.DOWN);
		scene.idle(20);

		// Three claims, three components: there is fuel in the Carburetor, oil in
		// the Sump, and the ignition on the crankcase is already switched on.
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "fuelled", util.select()
				.position(ENGINE.carburetor()), 80);
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "lubricated", util.select()
				.position(ENGINE.oilSump()), 80);
		scene.overlay()
			.showText(80)
			.text("A fuelled and lubricated engine, with its Ignition already on.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.ignition());
		scene.idle(90);

		// The switch itself, which is a real part of the crankcase model and the
		// one place on the engine a player can read the ignition without goggles.
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "ignition", util.select()
				.position(ENGINE.crankshaft(0)), 80);
		scene.overlay()
			.showText(80)
			.text("New engines have their Ignition switched on by default.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.ignition());
		scene.idle(90);

		// THE CENTRAL POINT of the scene.
		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "ignition_alone", util.select()
				.position(ENGINE.crankshaft(0)), 90);
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.MEDIUM)
			.text("Ignition alone does not start the engine.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.ignition());
		scene.idle(100);

		// Create's own Hand Crank, held down, which is how it is really done.
		scene.overlay()
			.showControls(centre(HAND_CRANK), Pointing.DOWN, 60)
			.rightClick();
		scene.idle(20);
		// Before the crank, the one part whose absence looks exactly like a fault:
		// an engine with no Camshaft cranks perfectly and never catches, which is
		// the single most confusing thing a correctly built engine can do.
		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "needs_camshaft", util.select()
				.position(ENGINE.crankshaft(0)), 90);
		scene.overlay()
			.showText(90)
			.text("An engine with no Camshaft will crank for ever and never catch.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.crankshaft(0)));
		scene.idle(100);

		scene.overlay()
			.showOutline(PonderPalette.WHITE, "hand_crank", util.select()
				.position(HAND_CRANK), 90);
		scene.overlay()
			.showText(90)
			.text("Combustion engines must be cranked before they can run on their own.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(HAND_CRANK));
		scene.idle(100);

		// Several revolutions, with firing attempts that do not yet carry it.
		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "attempt", util.select()
				.position(ENGINE.cylinder(0)), 30);
		scene.idle(35);
		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "attempt", util.select()
				.position(ENGINE.cylinder(0)), 30);
		scene.idle(35);
		scene.overlay()
			.showText(70)
			.text("Cylinders begin firing, but the engine has not caught yet.")
			.placeNearTarget()
			.pointAt(ENGINE.bore(0));
		scene.idle(80);

		// The catch, which is a relationship - the burn in the bore turning the
		// crank - so both parts are marked, separately.
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "caught_bore", util.select()
				.position(ENGINE.cylinder(0)), 60);
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "caught_crank", util.select()
				.position(ENGINE.crankshaft(0)), 60);
		scene.idle(20);
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.GREEN)
			.text("Once it catches, combustion keeps the crankshaft turning by itself.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.crankshaft(0)));
		scene.idle(100);

		// Throttle, on the Carburetor, with the real scroll interaction - offered
		// at the throttle lever, which is the part that moves when it is set.
		scene.overlay()
			.showControls(ENGINE.throttle(), Pointing.RIGHT, 50)
			.scroll();
		scene.idle(20);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "throttle", util.select()
				.position(ENGINE.carburetor()), 90);
		scene.overlay()
			.showText(90)
			.text("Scroll on the Carburetor to set the throttle, from 0% to 100%.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.throttle());
		scene.idle(100);

		// Carefully worded: throttle does NOT set RPM.
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "throttle_effect", util.select()
				.position(ENGINE.carburetor()), 100);
		scene.overlay()
			.showText(100)
			.text("More throttle increases available torque and the governed operating speed.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.throttle());
		scene.idle(110);

		// Ignition off, and the distinction that matters.
		scene.overlay()
			.showControls(ENGINE.ignition(), Pointing.DOWN, 40)
			.rightClick();
		scene.idle(20);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "ignition_off", util.select()
				.position(ENGINE.crankshaft(0)), 80);
		scene.overlay()
			.showText(80)
			.text("Ignition stops combustion.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.ignition());
		scene.idle(90);

		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "coasting", util.select()
				.position(ENGINE.flywheel()), 100);
		scene.overlay()
			.showText(100)
			.colored(PonderPalette.MEDIUM)
			.text("The engine keeps turning while it coasts, but is no longer producing power.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.flywheel()));
		scene.idle(110);
		scene.markAsFinished();
	}

	/** B6. Inline Engines - one crankshaft, up to four cylinders. */
	public static void inlineEngines(SceneBuilder scene, SceneBuildingUtil util) {
		scene.title("inline_engines", "Inline Engines");
		scene.configureBasePlate(0, 0, 7);
		scene.scaleSceneView(0.75F);
		scene.showBasePlate();
		scene.idle(10);

		// Start with a COMPLETE inline-1 at the far end and grow towards the camera.
		//
		// Complete matters: the structure puts this engine's Oil Sump, Carburetor
		// and Flywheel on the last section, so the first thing the player sees is a
		// whole valid engine rather than a bare crankshaft that could not run. The
		// growth steps below then add only crankshaft sections and cylinders, which
		// is exactly what growing an inline engine really is.
		//
		// Nothing is hidden to get here. This used to hideSection(layersFrom(1))
		// first, which erased from the base world section before any showSection had
		// merged into it - the null the client crashed on.
		int last = INLINE.sections() - 1;
		scene.world()
			.showSection(util.select()
				.fromTo(INLINE.oilSumpSeat(last), INLINE.carburetorSeat(last)), Direction.DOWN);
		scene.world()
			.showSection(util.select()
				.position(INLINE.flywheel()), Direction.DOWN);
		scene.idle(20);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "one_section", util.select()
				.position(INLINE.lastCrankshaft()), 70);
		scene.overlay()
			.showText(70)
			.text("One Crankshaft section is an inline-1.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(INLINE.lastCrankshaft()));
		scene.idle(80);

		// Grow to R4, one section at a time. The whole column, so that anything the
		// structure hangs off a section comes with it rather than being stranded
		// invisible - the old loop revealed only the crank and its cylinder and left
		// this engine's Carburetor and Oil Sump permanently unshown.
		for (int section = last - 1; section >= 0; section--) {
			scene.world()
				.showSection(util.select()
					.fromTo(INLINE.oilSumpSeat(section), INLINE.carburetorSeat(section)), Direction.EAST);
			scene.idle(20);
		}

		// One crankshaft, so one outline across the whole run: this is a step whose
		// subject really is every section at once.
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "one_crankshaft", util.select()
				.fromTo(INLINE.crankshaft(0), INLINE.lastCrankshaft()), 90);
		scene.overlay()
			.showText(90)
			.text("Adjacent Crankshaft sections form one shared engine, not several.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(INLINE.crankshaft(1)));
		scene.idle(100);

		// The line names four single components and one plural, so it marks exactly
		// those five things: the crank run, the Carburetor, the Oil Sump, the
		// Flywheel, and the row of bores.
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "shared_crank", util.select()
				.fromTo(INLINE.crankshaft(0), INLINE.lastCrankshaft()), 100);
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "shared_carburetor", util.select()
				.position(INLINE.carburetor()), 100);
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "shared_sump", util.select()
				.position(INLINE.oilSump()), 100);
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "shared_flywheel", util.select()
				.position(INLINE.flywheel()), 100);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "several_cylinders", util.select()
				.fromTo(INLINE.cylinder(0), INLINE.lastCylinder()), 100);
		scene.overlay()
			.showText(100)
			.text("One crankshaft, one Carburetor, one Oil Sump, one Flywheel - and several cylinders.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(INLINE.carburetor()));
		scene.idle(110);

		// Firing order - each cylinder at its own point in the rotation, so each
		// cylinder gets its own outline rather than one box around the row.
		for (int index = 0; index < INLINE.sections(); index++)
			scene.overlay()
				.showOutline(PonderPalette.MEDIUM, "firing_" + index, util.select()
					.position(INLINE.cylinder(index)), 90);
		scene.overlay()
			.showText(90)
			.text("Each cylinder fires at a different point in the crankshaft's rotation.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(INLINE.bore(1));
		scene.idle(100);

		// More power and more fuel: the bores make the power, the one Carburetor
		// pays for it.
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "more_power", util.select()
				.fromTo(INLINE.cylinder(0), INLINE.lastCylinder()), 80);
		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "more_fuel", util.select()
				.position(INLINE.carburetor()), 80);
		scene.overlay()
			.showText(80)
			.text("More cylinders provide more power, and consume more fuel.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(INLINE.cylinder(1)));
		scene.idle(90);

		// ACTIVE versus PRESENT. Pulling a plug stops a cylinder contributing.
		scene.overlay()
			.showControls(INLINE.sparkPlug(0), Pointing.RIGHT, 40)
			.withItem(new ItemStack(ECItems.SPARK_PLUG.get()))
			.rightClick()
			.whileSneaking();
		scene.idle(25);
		scene.overlay()
			.showOutline(PonderPalette.RED, "inactive", util.select()
				.position(INLINE.cylinder(0)), 70);
		scene.overlay()
			.showText(100)
			.text("Remove a Spark Plug and the goggles read: Active Cylinders 3 / 4.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(INLINE.sparkPlug(0));
		scene.idle(110);

		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "still_moving", util.select()
				.position(INLINE.cylinder(0)), 110);
		scene.overlay()
			.showText(110)
			.text("The piston still moves, but that cylinder no longer produces combustion power.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(INLINE.bore(0));
		scene.idle(120);

		// The fifth section, refused.
		scene.world()
			.showSection(util.select()
				.position(FIFTH_SECTION), Direction.EAST);
		scene.idle(15);
		scene.overlay()
			.showOutline(PonderPalette.RED, "fifth", util.select()
				.position(FIFTH_SECTION), 80);
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.RED)
			.text("Inline engines currently support up to four cylinders.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(FIFTH_SECTION));
		scene.idle(100);
		scene.markAsFinished();
	}

	/** B7. Engine Controls - manual first, redstone second. */
	public static void engineControls(SceneBuilder scene, SceneBuildingUtil util) {
		scene.title("engine_controls", "Engine Controls");
		scene.configureBasePlate(0, 0, 5);
		scene.scaleSceneView(0.9F);
		scene.showBasePlate();
		scene.idle(10);
		// Manual control is taught first and taught as sufficient, so the redstone
		// half of the scene simply is not shown yet.
		//
		// It is NOT hidden. This used to hide the redstone and then show
		// layersFrom(1), which was wrong twice over: hideSection erases from the
		// base world section, which is still empty this early and crashed; and
		// layersFrom(1) contains the redstone anyway, so the show would have put it
		// straight back. Revealing only the engine says the same thing and is true.
		scene.world()
			.showSection(util.select()
				.fromTo(ENGINE.oilSump(), ENGINE.carburetor()), Direction.DOWN);
		scene.world()
			.showSection(util.select()
				.position(ENGINE.flywheel()), Direction.DOWN);
		scene.idle(20);

		scene.overlay()
			.showControls(ENGINE.ignition(), Pointing.DOWN, 40)
			.rightClick();
		scene.idle(20);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "ignition", util.select()
				.position(ENGINE.crankshaft(0)), 80);
		scene.overlay()
			.showText(80)
			.text("Right-click the Crankshaft with an empty hand to work the Ignition.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.ignition());
		scene.idle(90);

		scene.overlay()
			.showControls(ENGINE.throttle(), Pointing.RIGHT, 40)
			.scroll();
		scene.idle(20);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "throttle", util.select()
				.position(ENGINE.carburetor()), 80);
		scene.overlay()
			.showText(80)
			.text("Scroll on the Carburetor to set the throttle.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.throttle());
		scene.idle(90);

		// SAID PLAINLY, because a player who thinks redstone is required will
		// build a redstone system before ever starting an engine. The two controls
		// that are sufficient on their own are the two that are marked.
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "manual_ignition", util.select()
				.position(ENGINE.crankshaft(0)), 90);
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "manual_throttle", util.select()
				.position(ENGINE.carburetor()), 90);
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.GREEN)
			.text("Redstone is not required to run an engine.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.ignition());
		scene.idle(100);

		// Now the optional module, which plugs into the crankcase.
		scene.overlay()
			.showControls(centre(ENGINE.crankshaft(0)), Pointing.DOWN, 40)
			.withItem(new ItemStack(ECItems.REDSTONE_CONTROL_MODULE.get()))
			.rightClick();
		scene.idle(25);
		scene.world()
			.showSection(util.select()
				.fromTo(REDSTONE, LEVER), Direction.DOWN);
		scene.idle(20);
		// What the module drives: the ignition on the crankcase and the throttle on
		// the Carburetor. The redstone line is marked too, because the sentence is
		// about redstone reaching those two.
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "module", util.select()
				.position(ENGINE.crankshaft(0)), 100);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "module_throttle", util.select()
				.position(ENGINE.carburetor()), 100);
		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "module_signal", util.select()
				.fromTo(REDSTONE, LEVER), 100);
		scene.overlay()
			.showText(100)
			.text("The Redstone Control Module lets Redstone drive the ignition, the throttle, or both.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.crankshaft(0)));
		scene.idle(110);

		scene.world()
			.toggleRedstonePower(util.select()
				.fromTo(REDSTONE, LEVER));
		scene.idle(20);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "wrench", util.select()
				.position(ENGINE.crankshaft(0)), 100);
		scene.overlay()
			.showText(100)
			.text("Its mode is set with a wrench: Manual, Ignition, Throttle, or both.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.crankshaft(0)));
		scene.idle(110);

		scene.overlay()
			.showOutline(PonderPalette.WHITE, "manual_again", util.select()
				.position(ENGINE.crankshaft(0)), 90);
		scene.overlay()
			.showText(90)
			.text("Removing the module returns the engine to manual control.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.crankshaft(0)));
		scene.idle(100);
		scene.markAsFinished();
	}
}
