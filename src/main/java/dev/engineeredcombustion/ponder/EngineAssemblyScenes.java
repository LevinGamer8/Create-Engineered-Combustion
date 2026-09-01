package dev.engineeredcombustion.ponder;

import static dev.engineeredcombustion.ponder.PonderEngine.centre;

import dev.engineeredcombustion.registry.ECItems;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/**
 * How an engine is built, fuelled and lubricated.
 *
 * <p>The two scenes a player sees first, and the two that have to be exactly
 * right: everything else in the mod assumes the machine in front of them is
 * assembled correctly, and a tutorial that teaches a layout the game refuses is
 * worse than no tutorial.
 *
 * <p>Nothing here writes a coordinate. Every position comes from
 * {@link PonderEngine}, which derives them from the layout {@code EngineComponents}
 * enforces and which {@code tools/validate_ux.py} checks against the schematic
 * these scenes are staged on. The engine sits with its crankshaft at y = 2 so that
 * the Oil Sump - which hangs <i>below</i> the crankcase - has somewhere to go that
 * is not inside the base plate.
 *
 * <h2>Every step outlines what its sentence is about</h2>
 * A line about the Air Filter outlines the Carburetor the filter clamps to and
 * points at the filter itself; a line about the Flywheel outlines the Flywheel.
 * Where a step is genuinely about a <i>relationship</i> - a filter protecting the
 * cylinders, two Flywheels being one too many - it outlines each part separately
 * rather than drawing one box around everything in between, which is what a
 * {@code fromTo} selection would do and which is how "this is the Air Filter"
 * came to be drawn around most of an engine.
 */
public class EngineAssemblyScenes {

	/** The inline-1 both scenes are staged on. */
	private static final PonderEngine ENGINE = PonderEngine.of(3, 2, 2, 1);

	/**
	 * B3. Building a Basic Engine - a real inline-1, one component at a time.
	 */
	public static void buildingABasicEngine(SceneBuilder scene, SceneBuildingUtil util) {
		scene.title("assembling_an_engine", "Building a Basic Engine");
		scene.configureBasePlate(0, 0, 5);
		scene.scaleSceneView(0.9F);
		scene.showBasePlate();
		scene.idle(10);

		// Nothing above the base plate is shown yet, and nothing needs hiding to
		// keep it that way: a Ponder structure's blocks are not visible until a
		// showSection reveals them. Each step below reveals exactly its own
		// component, so the player watches an engine appear in the order they would
		// build it.
		//
		// This is where the 1.0.82 crash was. hideSection(Selection) erases that
		// Selection from the scene's BASE WorldSectionElement, and the base element
		// starts life empty - its `section` field is literally null until a
		// showSection's fade-in completes and merges into it. Calling hideSection
		// first therefore dereferenced null inside WorldSectionElementImpl.erase.

		// STEP 1 - the crankshaft.
		scene.world()
			.showSection(util.select()
				.position(ENGINE.crankshaft(0)), Direction.UP);
		scene.idle(15);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "crankshaft", util.select()
				.position(ENGINE.crankshaft(0)), 70);
		scene.overlay()
			.showText(70)
			.text("The Crankshaft forms the mechanical base of the engine.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.crankshaft(0)));
		scene.idle(80);

		// STEP 2 - the cylinder, in its one valid position.
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "cylinder_seat", util.select()
				.position(ENGINE.cylinder(0)), 40);
		scene.idle(20);
		scene.world()
			.showSection(util.select()
				.position(ENGINE.cylinder(0)), Direction.DOWN);
		scene.idle(15);
		scene.overlay()
			// BOTH, because the sentence is about the RELATIONSHIP between them:
			// "each Crankshaft section supports one Cylinder". Boxing only the
			// Cylinder taught where a Cylinder goes and left the player to work out
			// what it was going on.
			.showOutline(PonderPalette.WHITE, "cylinder", util.select()
				.fromTo(ENGINE.crankshaft(0), ENGINE.cylinder(0)), 70);
		scene.overlay()
			.showText(70)
			.text("Each Crankshaft section supports one Cylinder, directly above it.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.cylinder(0)));
		scene.idle(80);

		// STEP 3 - the Piston Assembly, which is an ITEM installed INTO the block.
		// Pointed at the bore rather than at the middle of the block: the piston
		// goes down the barrel, and that is the part of the casting to look at.
		scene.overlay()
			.showControls(ENGINE.bore(0), Pointing.RIGHT, 40)
			.withItem(new ItemStack(ECItems.PISTON_ASSEMBLY.get()))
			.rightClick();
		scene.idle(20);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "bore", util.select()
				.position(ENGINE.cylinder(0)), 80);
		scene.overlay()
			.showText(80)
			.text("Piston Assemblies are installed inside Cylinders, not placed as blocks.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.bore(0));
		scene.idle(90);

		// STEP 4 - the Spark Plug. Per cylinder, always, and screwed into the head
		// rather than into the block in general - so the hand, the text and the
		// outline all address the top of the barrel.
		scene.overlay()
			.showControls(ENGINE.sparkPlug(0), Pointing.LEFT, 40)
			.withItem(new ItemStack(ECItems.SPARK_PLUG.get()))
			.rightClick();
		scene.idle(20);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "plug_seat", util.select()
				.position(ENGINE.cylinder(0)), 80);
		scene.overlay()
			.showText(80)
			.text("Each Cylinder needs its own Spark Plug to ignite fuel.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.sparkPlug(0));
		scene.idle(90);

		// STEP 5 - the Camshaft. ONE per engine, and mandatory: an engine without one
		// turns over perfectly and never fires, which is the single most confusing
		// way a correctly built engine can fail. So it is taught here, between the
		// parts that make the engine turn and the parts that make it burn, which is
		// where it sits mechanically.
		scene.overlay()
			.showControls(centre(ENGINE.crankshaft(0)), Pointing.DOWN, 40)
			.withItem(new ItemStack(ECItems.CAMSHAFT.get()))
			.rightClick();
		scene.idle(20);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "camshaft", util.select()
				.position(ENGINE.crankshaft(0)), 80);
		scene.overlay()
			.showText(80)
			.text("One Camshaft is installed into the Crankshaft, and works every valve.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.crankshaft(0)));
		scene.idle(90);

		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "camshaft_needed", util.select()
				.position(ENGINE.cylinder(0)), 80);
		scene.overlay()
			.showText(80)
			.text("Without one the Cylinder cannot draw fuel in, so it never fires.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.bore(0));
		scene.idle(90);

		// STEP 6 - the Carburetor.
		scene.world()
			.showSection(util.select()
				.position(ENGINE.carburetor()), Direction.DOWN);
		scene.idle(15);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "carburetor", util.select()
				.position(ENGINE.carburetor()), 80);
		scene.overlay()
			.showText(80)
			.text("The Carburetor holds Gasoline and controls the throttle.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.carburetor()));
		scene.idle(90);

		// STEP 7 - the Oil Sump, underneath, as a real one is.
		scene.world()
			.showSection(util.select()
				.position(ENGINE.oilSump()), Direction.UP);
		scene.idle(15);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "sump", util.select()
				.position(ENGINE.oilSump()), 70);
		scene.overlay()
			.showText(70)
			.text("The Oil Sump hangs under the crankcase and supplies lubrication.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.oilSump()));
		scene.idle(80);

		// STEP 8 - ONE Flywheel, at one axial end.
		scene.world()
			.showSection(util.select()
				.position(ENGINE.flywheel()), Direction.WEST);
		scene.idle(15);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "flywheel", util.select()
				.position(ENGINE.flywheel()), 80);
		scene.overlay()
			.showText(80)
			.text("The Flywheel transfers engine power into a Create kinetic network.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.flywheel()));
		scene.idle(90);

		// Either end is valid - shown by moving it, not merely asserted.
		scene.world()
			.hideSection(util.select()
				.position(ENGINE.flywheel()), Direction.EAST);
		scene.idle(10);
		scene.world()
			.showSection(util.select()
				.position(ENGINE.farFlywheel()), Direction.EAST);
		scene.idle(15);
		scene.overlay()
			// "Either end of the crankshaft" names three things: the shaft and both of
			// its ends. Boxing only the far Flywheel taught a third of that, and left
			// the player looking at the one end the sentence is NOT about.
			.showOutline(PonderPalette.GREEN, "far_flywheel", util.select()
				.fromTo(ENGINE.farFlywheel(), ENGINE.flywheel()), 70);
		scene.overlay()
			.showText(70)
			.text("Either end of the crankshaft will do.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.farFlywheel()));
		scene.idle(80);

		// But BOTH is not an engine. Marked invalid, and left invalid.
		//
		// TWO outlines, one per Flywheel, rather than one selection spanning both:
		// a fromTo would draw a single box from one Flywheel to the other, which
		// puts the crankshaft between them inside the red - and the crankshaft is
		// not what is wrong here.
		scene.world()
			.showSection(util.select()
				.position(ENGINE.flywheel()), Direction.WEST);
		scene.idle(10);
		scene.overlay()
			.showOutline(PonderPalette.RED, "flywheel_end", util.select()
				.position(ENGINE.flywheel()), 80);
		scene.overlay()
			.showOutline(PonderPalette.RED, "flywheel_far_end", util.select()
				.position(ENGINE.farFlywheel()), 80);
		scene.overlay()
			.showText(80)
			.colored(PonderPalette.RED)
			.text("One engine uses one Flywheel. Two is not a valid engine.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.flywheel()));
		scene.idle(90);
		scene.world()
			.hideSection(util.select()
				.position(ENGINE.farFlywheel()), Direction.WEST);
		scene.idle(15);

		// STEP 9 - the Air Filter, and the fact that it is OPTIONAL.
		//
		// The filter clamps onto the Carburetor's air horn, so the hand offers it
		// there, the text points there, and the outline is of the Carburetor - the
		// one block involved. This step is the reason this pass exists: it used to
		// point at the middle of the Carburetor with no outline of its own, while
		// the red two-Flywheel box from the step above was still the last thing the
		// reader had been shown a box around.
		scene.overlay()
			.showControls(ENGINE.airFilter(), Pointing.RIGHT, 40)
			.withItem(new ItemStack(ECItems.AIR_FILTER.get()))
			.rightClick();
		scene.idle(20);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "air_filter", util.select()
				.position(ENGINE.carburetor()), 70);
		scene.overlay()
			.showText(70)
			.text("An Air Filter clamps onto the Carburetor, and is optional.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.airFilter());
		scene.idle(80);

		// TWO STEPS, because it was two sentences about two different parts. The
		// filter is on the Carburetor and what it protects is the bore, so the box
		// moves off the one and onto the other rather than staying put while the
		// subject changes underneath it.
		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "filtered_bore", util.select()
				.position(ENGINE.cylinder(0)), 80);
		scene.overlay()
			.showText(80)
			.text("Without one, the cylinders take more long-term wear.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.bore(0));
		scene.idle(90);

		// The one step that is genuinely about the whole machine, so the one step
		// whose outline covers it: sump, crankcase, barrel, carburetor and
		// Flywheel, which is exactly what "mechanically complete" means.
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "complete", util.select()
				.fromTo(ENGINE.oilSump(), ENGINE.carburetor()), 80);
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "complete_drive", util.select()
				.position(ENGINE.flywheel()), 80);
		scene.overlay()
			.showText(80)
			.text("This engine is mechanically complete.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.crankshaft(0)));
		scene.idle(90);
		scene.markAsFinished();
	}

	/**
	 * B4. Fuel and Lubrication - what goes in, and what happens when it does not.
	 */
	public static void fuelAndLubrication(SceneBuilder scene, SceneBuildingUtil util) {
		scene.title("fuel_and_lubrication", "Fuel and Lubrication");
		scene.configureBasePlate(0, 0, 5);
		scene.scaleSceneView(0.9F);
		scene.showBasePlate();
		scene.idle(10);
		scene.world()
			.showSection(util.select()
				.layersFrom(1), Direction.DOWN);
		scene.idle(20);

		// Gasoline into the Carburetor. A bucket, because that is the first way a
		// player will ever do it, offered at the float bowl - the part of the
		// Carburetor the fuel ends up standing in, and the part a player can watch
		// it standing in.
		scene.overlay()
			.showControls(ENGINE.floatBowl(), Pointing.RIGHT, 40)
			.withItem(new ItemStack(ECItems.GASOLINE_BUCKET.get()))
			.rightClick();
		scene.idle(20);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "carburetor", util.select()
				.position(ENGINE.carburetor()), 80);
		scene.overlay()
			.showText(80)
			.text("Gasoline goes into the Carburetor, and is consumed during combustion.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.floatBowl());
		scene.idle(90);

		// Engine Oil into the Sump.
		scene.overlay()
			.showControls(centre(ENGINE.oilSump()), Pointing.RIGHT, 40)
			.withItem(new ItemStack(ECItems.ENGINE_OIL_BUCKET.get()))
			.rightClick();
		scene.idle(20);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "sump", util.select()
				.position(ENGINE.oilSump()), 80);
		scene.overlay()
			.showText(80)
			.text("Engine Oil goes into the Oil Sump, and protects the moving parts.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.dipstick());
		scene.idle(90);

		// Both tanks are ordinary fluid handlers, so Create's pipes fill them.
		// "Both" is two components, so it is two outlines.
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "carburetor_tank", util.select()
				.position(ENGINE.carburetor()), 80);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "sump_tank", util.select()
				.position(ENGINE.oilSump()), 80);
		scene.overlay()
			.showText(80)
			.text("Both accept fluids from Create's pipes and tanks as well as from buckets.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.carburetor()));
		scene.idle(90);

		// THE LUBRICATION MESSAGE. Worded so that a future oil-condition system
		// does not make it a lie.
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "lubricated", util.select()
				.position(ENGINE.oilSump()), 90);
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.GREEN)
			.text("Proper lubrication keeps major component wear very low.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.dipstick());
		scene.idle(100);

		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "low_oil", util.select()
				.position(ENGINE.oilSump()), 80);
		scene.overlay()
			.showText(80)
			.colored(PonderPalette.MEDIUM)
			.text("Low oil increases friction and wear.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.dipstick());
		scene.idle(90);

		// What running dry damages is the bearings and the bores, so those are what
		// is marked - not the pan, which is merely where the oil was not.
		scene.overlay()
			.showOutline(PonderPalette.RED, "dry_bearings", util.select()
				.position(ENGINE.crankshaft(0)), 80);
		scene.overlay()
			.showOutline(PonderPalette.RED, "dry_bore", util.select()
				.position(ENGINE.cylinder(0)), 80);
		scene.overlay()
			.showText(80)
			.colored(PonderPalette.RED)
			.text("Running dry can seriously damage an engine.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.crankshaft(0)));
		scene.idle(90);

		// The Air Filter, again as optional, because this is the other scene a
		// player might reach first. A relationship, deliberately shown as one: the
		// filter goes on the Carburetor, and what it protects is the bore.
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "filter_mount", util.select()
				.position(ENGINE.carburetor()), 90);
		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "filter_protects", util.select()
				.position(ENGINE.cylinder(0)), 90);
		scene.overlay()
			.showText(90)
			.text("The engine runs without an Air Filter, but long-term cylinder wear increases.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.airFilter());
		scene.idle(100);
		scene.markAsFinished();
	}
}
