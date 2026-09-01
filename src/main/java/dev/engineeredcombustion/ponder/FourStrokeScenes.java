package dev.engineeredcombustion.ponder;

import static dev.engineeredcombustion.ponder.PonderEngine.centre;

import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;

/**
 * What actually happens inside a cylinder, once round.
 *
 * <h2>Why this scene exists at all</h2>
 * Milestone 15B made the engine a genuine four-stroke, and that is the one change
 * a player can watch and not understand. The crank goes round twice for every bang;
 * three of the four strokes make no power; a cylinder that has just fired will sit
 * there apparently doing nothing for a full revolution. Every one of those looks
 * like a fault if nobody has said otherwise, and the Goggles' per-cylinder stroke
 * readout is meaningless until somebody has.
 *
 * <h2>Taught on a single, and in the order the strokes happen</h2>
 * An inline-1, deliberately. Four strokes explained on an inline-4 is four
 * cylinders on four different strokes at once, which is the interesting thing to
 * understand <i>second</i> - the inline scene already covers it.
 *
 * <p>The cycle is walked in its own order - intake, compression, power, exhaust -
 * rather than in the order the engine's internal angle numbers them. The engine
 * counts from compression because that is where its crank angle already put top
 * dead centre; a player counts from the stroke that draws the charge in, because
 * that is where the fuel comes from.
 *
 * <h2>No terminology a beginner has not been given</h2>
 * "Top dead centre", "valve overlap", "duty cycle" and "ignition advance" are all
 * absent. What the scene says is: air and fuel go in, they are squeezed, they burn
 * and push, and the burnt gas is pushed out - twice round the crank for one push.
 * That is the whole of what a player needs to read their own engine.
 */
public class FourStrokeScenes {

	/** The inline-1 this is staged on. Must match ENGINES in the structure generator. */
	private static final PonderEngine ENGINE = PonderEngine.of(3, 2, 2, 1);

	public static void theFourStrokeCycle(SceneBuilder scene, SceneBuildingUtil util) {
		scene.title("the_four_stroke_cycle", "The Four-Stroke Cycle");
		scene.configureBasePlate(0, 0, 5);
		scene.scaleSceneView(0.95F);
		scene.showBasePlate();
		scene.idle(10);

		scene.world()
			.showSection(util.select()
				.everywhere(), Direction.UP);
		scene.idle(20);

		// The frame the whole scene hangs on, and the one number worth remembering.
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "whole_engine", util.select()
				.fromTo(ENGINE.oilSump(), ENGINE.carburetor()), 90);
		scene.overlay()
			.showText(90)
			.text("A running engine turns the Crankshaft twice for every combustion.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.crankshaft(0)));
		scene.idle(100);

		// STROKE 1 - INTAKE. The Camshaft is outlined with the Cylinder here and
		// nowhere else: this is the stroke it exists for, and the one a player who
		// has forgotten to fit one will never see happen.
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "intake_cam", util.select()
				.position(ENGINE.crankshaft(0)), 90);
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "intake_bore", util.select()
				.position(ENGINE.cylinder(0)), 90);
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.GREEN)
			.text("1. Intake. The Camshaft opens a valve and the piston draws fuel in.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.bore(0));
		scene.idle(100);

		// STROKE 2 - COMPRESSION. Both valves shut, so the Cylinder alone.
		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "compression", util.select()
				.position(ENGINE.cylinder(0)), 90);
		scene.overlay()
			.showText(90)
			.text("2. Compression. Both valves shut and the piston squeezes the charge.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.bore(0));
		scene.idle(100);

		// STROKE 3 - POWER. The Spark Plug lights it and the Crankshaft is pushed,
		// so both are marked: this is a relationship, not a place.
		scene.overlay()
			.showOutline(PonderPalette.RED, "power_bore", util.select()
				.position(ENGINE.cylinder(0)), 90);
		scene.overlay()
			.showOutline(PonderPalette.RED, "power_crank", util.select()
				.position(ENGINE.crankshaft(0)), 90);
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.RED)
			.text("3. Power. The Spark Plug lights it, and the Crankshaft is pushed round.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.sparkPlug(0));
		scene.idle(100);

		// STROKE 4 - EXHAUST.
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "exhaust", util.select()
				.position(ENGINE.cylinder(0)), 90);
		scene.overlay()
			.showText(90)
			.text("4. Exhaust. The other valve opens and the burnt charge is pushed out.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.bore(0));
		scene.idle(100);

		// The consequence, which is the whole reason a player needs this scene: the
		// Flywheel is what carries the engine through the three strokes that do not
		// push. A single feels it; an inline-4 barely does.
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "flywheel_carries", util.select()
				.position(ENGINE.flywheel()), 100);
		scene.overlay()
			.showText(100)
			.colored(PonderPalette.GREEN)
			.text("Only one stroke pushes. The Flywheel carries the engine through the rest.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.flywheel()));
		scene.idle(110);

		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "single_thump", util.select()
				.position(ENGINE.cylinder(0)), 90);
		scene.overlay()
			.showText(90)
			.text("That is why a single thumps, and why more cylinders run smoother.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.cylinder(0)));
		scene.idle(100);
		scene.markAsFinished();
	}
}
