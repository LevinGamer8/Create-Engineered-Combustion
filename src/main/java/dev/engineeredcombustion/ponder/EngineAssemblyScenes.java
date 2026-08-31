package dev.engineeredcombustion.ponder;

import dev.engineeredcombustion.registry.ECItems;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * How an engine is built, fuelled and lubricated.
 *
 * <p>The two scenes a player sees first, and the two that have to be exactly
 * right: everything else in the mod assumes the machine in front of them is
 * assembled correctly, and a tutorial that teaches a layout the game refuses is
 * worse than no tutorial.
 *
 * <p>Positions here are structure coordinates and match
 * {@code tools/generate_ponder_structures.py}, which stamps out the schematics
 * these scenes are staged on. The engine sits with its crankshaft at y = 2 so
 * that the Oil Sump - which hangs <i>below</i> the crankcase - has somewhere to
 * go that is not inside the base plate.
 */
public class EngineAssemblyScenes {

	/** Where the inline-1 in the small scenes lives. */
	private static final BlockPos CRANK = new BlockPos(3, 2, 2);
	private static final BlockPos CYLINDER = new BlockPos(3, 3, 2);
	private static final BlockPos CARBURETOR = new BlockPos(3, 4, 2);
	private static final BlockPos SUMP = new BlockPos(3, 1, 2);
	private static final BlockPos FLYWHEEL = new BlockPos(4, 2, 2);
	private static final BlockPos SECOND_FLYWHEEL = new BlockPos(2, 2, 2);

	/**
	 * B3. Building a Basic Engine - a real inline-1, one component at a time.
	 */
	public static void buildingABasicEngine(SceneBuilder scene, SceneBuildingUtil util) {
		scene.title("assembling_an_engine", "Building a Basic Engine");
		scene.configureBasePlate(0, 0, 5);
		scene.scaleSceneView(0.9F);
		scene.showBasePlate();
		scene.idle(10);

		// Everything starts hidden; each step reveals exactly its own component, so
		// the player watches an engine appear in the order they would build it.
		hideEverything(scene, util);
		scene.idle(5);

		// STEP 1 - the crankshaft.
		scene.world()
			.showSection(util.select()
				.position(CRANK), Direction.UP);
		scene.idle(15);
		scene.overlay()
			.showText(70)
			.text("The Crankshaft forms the mechanical base of the engine.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CRANK));
		scene.idle(80);

		// STEP 2 - the cylinder, in its one valid position.
		scene.overlay()
			.showOutline(PonderPalette.GREEN, CYLINDER, util.select()
				.position(CYLINDER), 40);
		scene.idle(20);
		scene.world()
			.showSection(util.select()
				.position(CYLINDER), Direction.DOWN);
		scene.idle(15);
		scene.overlay()
			.showText(70)
			.text("Each Crankshaft section supports one Cylinder, directly above it.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CYLINDER));
		scene.idle(80);

		// STEP 3 - the Piston Assembly, which is an ITEM installed INTO the block.
		scene.overlay()
			.showControls(centre(CYLINDER), Pointing.RIGHT, 40)
			.withItem(new ItemStack(ECItems.PISTON_ASSEMBLY.get()))
			.rightClick();
		scene.idle(20);
		scene.overlay()
			.showText(80)
			.text("Piston Assemblies are installed inside Cylinders, not placed as blocks.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CYLINDER));
		scene.idle(90);

		// STEP 4 - the Spark Plug. Per cylinder, always.
		scene.overlay()
			.showControls(centre(CYLINDER)
				.add(0.0D, 0.4D, 0.0D), Pointing.LEFT, 40)
			.withItem(new ItemStack(ECItems.SPARK_PLUG.get()))
			.rightClick();
		scene.idle(20);
		scene.overlay()
			.showText(80)
			.text("Each Cylinder needs its own Spark Plug to ignite fuel.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CYLINDER));
		scene.idle(90);

		// STEP 5 - the Carburetor.
		scene.world()
			.showSection(util.select()
				.position(CARBURETOR), Direction.DOWN);
		scene.idle(15);
		scene.overlay()
			.showText(80)
			.text("The Carburetor holds Gasoline and controls the throttle.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CARBURETOR));
		scene.idle(90);

		// STEP 6 - the Oil Sump, underneath, as a real one is.
		scene.world()
			.showSection(util.select()
				.position(SUMP), Direction.UP);
		scene.idle(15);
		scene.overlay()
			.showText(70)
			.text("The Oil Sump hangs under the crankcase and supplies lubrication.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(SUMP));
		scene.idle(80);

		// STEP 7 - ONE Flywheel, at one axial end.
		scene.world()
			.showSection(util.select()
				.position(FLYWHEEL), Direction.WEST);
		scene.idle(15);
		scene.overlay()
			.showText(80)
			.text("The Flywheel transfers engine power into a Create kinetic network.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(FLYWHEEL));
		scene.idle(90);

		// Either end is valid - shown by moving it, not merely asserted.
		scene.world()
			.hideSection(util.select()
				.position(FLYWHEEL), Direction.EAST);
		scene.idle(10);
		scene.world()
			.showSection(util.select()
				.position(SECOND_FLYWHEEL), Direction.EAST);
		scene.idle(15);
		scene.overlay()
			.showText(70)
			.text("Either end of the crankshaft will do.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(SECOND_FLYWHEEL));
		scene.idle(80);

		// But BOTH is not an engine. Marked invalid, and left invalid.
		scene.world()
			.showSection(util.select()
				.position(FLYWHEEL), Direction.WEST);
		scene.idle(10);
		scene.overlay()
			.showOutline(PonderPalette.RED, "two_flywheels", util.select()
				.fromTo(SECOND_FLYWHEEL, FLYWHEEL), 80);
		scene.overlay()
			.showText(80)
			.colored(PonderPalette.RED)
			.text("One engine uses one Flywheel. Two is not a valid engine.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(FLYWHEEL));
		scene.idle(90);
		scene.world()
			.hideSection(util.select()
				.position(SECOND_FLYWHEEL), Direction.WEST);
		scene.idle(15);

		// STEP 8 - the Air Filter, and the fact that it is OPTIONAL.
		scene.overlay()
			.showControls(centre(CARBURETOR), Pointing.RIGHT, 40)
			.withItem(new ItemStack(ECItems.AIR_FILTER.get()))
			.rightClick();
		scene.idle(20);
		scene.overlay()
			.showText(90)
			.text("An Air Filter is optional. It protects the cylinders from long-term wear.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CARBURETOR));
		scene.idle(100);

		scene.overlay()
			.showText(80)
			.text("This engine is mechanically complete.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CRANK));
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
		// player will ever do it.
		scene.overlay()
			.showControls(centre(CARBURETOR), Pointing.RIGHT, 40)
			.withItem(new ItemStack(ECItems.GASOLINE_BUCKET.get()))
			.rightClick();
		scene.idle(20);
		scene.overlay()
			.showText(80)
			.text("Gasoline goes into the Carburetor, and is consumed during combustion.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CARBURETOR));
		scene.idle(90);

		// Engine Oil into the Sump.
		scene.overlay()
			.showControls(centre(SUMP), Pointing.RIGHT, 40)
			.withItem(new ItemStack(ECItems.ENGINE_OIL_BUCKET.get()))
			.rightClick();
		scene.idle(20);
		scene.overlay()
			.showText(80)
			.text("Engine Oil goes into the Oil Sump, and protects the moving parts.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(SUMP));
		scene.idle(90);

		// Both tanks are ordinary fluid handlers, so Create's pipes fill them.
		scene.overlay()
			.showText(80)
			.text("Both accept fluids from Create's pipes and tanks as well as from buckets.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CARBURETOR));
		scene.idle(90);

		// THE LUBRICATION MESSAGE. Worded so that a future oil-condition system
		// does not make it a lie.
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.GREEN)
			.text("Proper lubrication keeps major component wear very low.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(SUMP));
		scene.idle(100);

		scene.overlay()
			.showText(80)
			.colored(PonderPalette.MEDIUM)
			.text("Low oil increases friction and wear.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(SUMP));
		scene.idle(90);

		scene.overlay()
			.showText(80)
			.colored(PonderPalette.RED)
			.text("Running dry can seriously damage an engine.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(SUMP));
		scene.idle(90);

		// The Air Filter, again as optional, because this is the other scene a
		// player might reach first.
		scene.overlay()
			.showText(90)
			.text("The engine runs without an Air Filter, but long-term cylinder wear increases.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(CARBURETOR));
		scene.idle(100);
		scene.markAsFinished();
	}

	/** Hides every block above the base plate, so a scene can build itself up. */
	static void hideEverything(SceneBuilder scene, SceneBuildingUtil util) {
		Selection above = util.select()
			.layersFrom(1);
		scene.world()
			.hideSection(above, Direction.UP);
	}

	/** The middle of a block, as a scene-space vector. */
	static Vec3 centre(BlockPos pos) {
		return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
	}
}
