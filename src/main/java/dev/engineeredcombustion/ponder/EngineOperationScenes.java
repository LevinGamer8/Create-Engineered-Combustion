package dev.engineeredcombustion.ponder;

import static dev.engineeredcombustion.ponder.EngineAssemblyScenes.centre;

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
 */
public class EngineOperationScenes {

	private static final BlockPos CRANK = new BlockPos(3, 2, 2);
	private static final BlockPos CYLINDER = new BlockPos(3, 3, 2);
	private static final BlockPos CARBURETOR = new BlockPos(3, 4, 2);
	private static final BlockPos FLYWHEEL = new BlockPos(4, 2, 2);
	private static final BlockPos HAND_CRANK = new BlockPos(2, 2, 2);

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

		scene.overlay()
			.showText(80)
			.text("A fuelled and lubricated engine, with its Ignition already on.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CRANK));
		scene.idle(90);

		scene.overlay()
			.showText(80)
			.text("New engines have their Ignition switched on by default.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CRANK));
		scene.idle(90);

		// THE CENTRAL POINT of the scene.
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.MEDIUM)
			.text("Ignition alone does not start the engine.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CRANK));
		scene.idle(100);

		// Create's own Hand Crank, held down, which is how it is really done.
		scene.overlay()
			.showControls(centre(HAND_CRANK), Pointing.DOWN, 60)
			.rightClick();
		scene.idle(20);
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
				.position(CYLINDER), 30);
		scene.idle(35);
		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "attempt", util.select()
				.position(CYLINDER), 30);
		scene.idle(35);
		scene.overlay()
			.showText(70)
			.text("Cylinders begin firing, but the engine has not caught yet.")
			.placeNearTarget()
			.pointAt(centre(CYLINDER));
		scene.idle(80);

		// The catch.
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "caught", util.select()
				.fromTo(CRANK, CYLINDER), 60);
		scene.idle(20);
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.GREEN)
			.text("Once it catches, combustion keeps the crankshaft turning by itself.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CRANK));
		scene.idle(100);

		// Throttle, on the Carburetor, with the real scroll interaction.
		scene.overlay()
			.showControls(centre(CARBURETOR), Pointing.RIGHT, 50)
			.scroll();
		scene.idle(20);
		scene.overlay()
			.showText(90)
			.text("Scroll on the Carburetor to set the throttle, from 0% to 100%.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CARBURETOR));
		scene.idle(100);

		// Carefully worded: throttle does NOT set RPM.
		scene.overlay()
			.showText(100)
			.text("More throttle increases available torque and the governed operating speed.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CARBURETOR));
		scene.idle(110);

		// Ignition off, and the distinction that matters.
		scene.overlay()
			.showControls(centre(CRANK), Pointing.DOWN, 40)
			.rightClick();
		scene.idle(20);
		scene.overlay()
			.showText(80)
			.text("Ignition stops combustion.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CRANK));
		scene.idle(90);

		scene.overlay()
			.showText(100)
			.colored(PonderPalette.MEDIUM)
			.text("The engine keeps turning while it coasts, but is no longer producing power.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(FLYWHEEL));
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

		// Start with the R1 at the far end and grow towards the camera.
		BlockPos first = new BlockPos(5, 2, 2);
		scene.world()
			.hideSection(util.select()
				.layersFrom(1), Direction.UP);
		scene.idle(5);
		scene.world()
			.showSection(util.select()
				.fromTo(new BlockPos(5, 1, 2), new BlockPos(6, 4, 2)), Direction.DOWN);
		scene.idle(20);
		scene.overlay()
			.showText(70)
			.text("One Crankshaft section is an inline-1.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(first));
		scene.idle(80);

		// Grow to R4, one section at a time.
		for (int section = 1; section <= 3; section++) {
			int x = 5 - section;
			scene.world()
				.showSection(util.select()
					.fromTo(new BlockPos(x, 2, 2), new BlockPos(x, 3, 2)), Direction.EAST);
			scene.idle(20);
		}
		scene.overlay()
			.showText(90)
			.text("Adjacent Crankshaft sections form one shared engine, not several.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(new BlockPos(3, 2, 2)));
		scene.idle(100);

		scene.overlay()
			.showText(100)
			.text("One crankshaft, one Carburetor, one Oil Sump, one Flywheel - and several cylinders.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(new BlockPos(5, 4, 2)));
		scene.idle(110);

		// Firing order - each cylinder at its own point in the rotation.
		scene.overlay()
			.showText(90)
			.text("Each cylinder fires at a different point in the crankshaft's rotation.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(new BlockPos(4, 3, 2)));
		scene.idle(100);

		scene.overlay()
			.showText(80)
			.text("More cylinders provide more power, and consume more fuel.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(new BlockPos(3, 3, 2)));
		scene.idle(90);

		// ACTIVE versus PRESENT. Pulling a plug stops a cylinder contributing.
		BlockPos pluglessCylinder = new BlockPos(2, 3, 2);
		scene.overlay()
			.showControls(centre(pluglessCylinder), Pointing.RIGHT, 40)
			.withItem(new ItemStack(ECItems.SPARK_PLUG.get()))
			.rightClick()
			.whileSneaking();
		scene.idle(25);
		scene.overlay()
			.showOutline(PonderPalette.RED, "inactive", util.select()
				.position(pluglessCylinder), 70);
		scene.overlay()
			.showText(100)
			.text("Remove a Spark Plug and the goggles read: Active Cylinders 3 / 4.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(pluglessCylinder));
		scene.idle(110);

		scene.overlay()
			.showText(110)
			.text("The piston still moves, but that cylinder no longer produces combustion power.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(pluglessCylinder));
		scene.idle(120);

		// The fifth section, refused.
		scene.world()
			.showSection(util.select()
				.position(new BlockPos(1, 2, 2)), Direction.EAST);
		scene.idle(15);
		scene.overlay()
			.showOutline(PonderPalette.RED, "fifth", util.select()
				.position(new BlockPos(1, 2, 2)), 80);
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.RED)
			.text("Inline engines currently support up to four cylinders.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(new BlockPos(1, 2, 2)));
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
		// The redstone half is hidden to begin with: manual control is taught
		// first, and taught as sufficient.
		scene.world()
			.hideSection(util.select()
				.fromTo(new BlockPos(5, 1, 2), new BlockPos(6, 1, 2)), Direction.UP);
		scene.world()
			.showSection(util.select()
				.layersFrom(1), Direction.DOWN);
		scene.idle(20);

		scene.overlay()
			.showControls(centre(CRANK), Pointing.DOWN, 40)
			.rightClick();
		scene.idle(20);
		scene.overlay()
			.showText(80)
			.text("Right-click the Crankshaft with an empty hand to work the Ignition.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CRANK));
		scene.idle(90);

		scene.overlay()
			.showControls(centre(CARBURETOR), Pointing.RIGHT, 40)
			.scroll();
		scene.idle(20);
		scene.overlay()
			.showText(80)
			.text("Scroll on the Carburetor to set the throttle.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CARBURETOR));
		scene.idle(90);

		// SAID PLAINLY, because a player who thinks redstone is required will
		// build a redstone system before ever starting an engine.
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.GREEN)
			.text("Redstone is not required to run an engine.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CRANK));
		scene.idle(100);

		// Now the optional module.
		scene.overlay()
			.showControls(centre(CRANK), Pointing.DOWN, 40)
			.withItem(new ItemStack(ECItems.REDSTONE_CONTROL_MODULE.get()))
			.rightClick();
		scene.idle(25);
		scene.world()
			.showSection(util.select()
				.fromTo(new BlockPos(5, 1, 2), new BlockPos(6, 1, 2)), Direction.DOWN);
		scene.idle(20);
		scene.overlay()
			.showText(100)
			.text("The Redstone Control Module lets Redstone drive the ignition, the throttle, or both.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CRANK));
		scene.idle(110);

		scene.world()
			.toggleRedstonePower(util.select()
				.fromTo(new BlockPos(5, 1, 2), new BlockPos(6, 1, 2)));
		scene.idle(20);
		scene.overlay()
			.showText(100)
			.text("Its mode is set with a wrench: Manual, Ignition, Throttle, or both.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CRANK));
		scene.idle(110);

		scene.overlay()
			.showText(90)
			.text("Removing the module returns the engine to manual control.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CRANK));
		scene.idle(100);
		scene.markAsFinished();
	}
}
