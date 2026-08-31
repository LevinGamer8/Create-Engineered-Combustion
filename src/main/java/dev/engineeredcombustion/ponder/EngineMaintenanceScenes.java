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
 * How an engine wears, how it is diagnosed, and where its fuel comes from.
 *
 * <p>The maintenance scene carries the single most important message in the mod
 * after "engines must be cranked": <b>a properly lubricated and filtered engine
 * wears extremely slowly</b>. It has to be careful not to teach the opposite by
 * accident - a tutorial that spends four steps replacing pistons leaves a player
 * believing pistons are consumables, which after the 13.1 rebalance they
 * emphatically are not.
 */
public class EngineMaintenanceScenes {

	private static final BlockPos R4_FIRST = new BlockPos(2, 2, 2);
	private static final BlockPos R4_CARBURETOR = new BlockPos(2, 4, 2);
	private static final BlockPos R4_SUMP = new BlockPos(2, 1, 2);
	private static final BlockPos WORN_CYLINDER = new BlockPos(4, 3, 2);

	/** B8. Engine Maintenance. */
	public static void engineMaintenance(SceneBuilder scene, SceneBuildingUtil util) {
		scene.title("engine_maintenance", "Engine Maintenance");
		scene.configureBasePlate(0, 0, 7);
		scene.scaleSceneView(0.75F);
		scene.showBasePlate();
		scene.idle(10);
		scene.world()
			.showSection(util.select()
				.layersFrom(1), Direction.DOWN);
		scene.idle(20);

		// THE HEADLINE. First, and stated as the normal case.
		scene.overlay()
			.showText(110)
			.colored(PonderPalette.GREEN)
			.text("A properly lubricated and filtered engine wears extremely slowly.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(R4_FIRST));
		scene.idle(120);

		scene.overlay()
			.showText(110)
			.text("Crankshafts and Piston Assemblies are not routine consumables.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(R4_FIRST));
		scene.idle(120);

		// Then what actually wears one out. Each is a real multiplier.
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.MEDIUM)
			.text("Unfiltered operation increases long-term cylinder wear.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(R4_CARBURETOR));
		scene.idle(100);

		scene.overlay()
			.showText(90)
			.colored(PonderPalette.MEDIUM)
			.text("Low oil increases friction and component wear.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(R4_SUMP));
		scene.idle(100);

		scene.overlay()
			.showText(90)
			.colored(PonderPalette.RED)
			.text("Running without lubrication can cause serious damage.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(R4_SUMP));
		scene.idle(100);

		scene.overlay()
			.showText(110)
			.colored(PonderPalette.RED)
			.text("External machines can force an engine past its intended speed. "
				+ "Sustained overspeed wears it quickly.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(new BlockPos(6, 2, 2)));
		scene.idle(120);

		// A badly abused engine, and what the goggles say about it.
		scene.overlay()
			.showOutline(PonderPalette.RED, "worn", util.select()
				.position(WORN_CYLINDER), 100);
		scene.overlay()
			.showText(100)
			.text("Mechanical Condition: Poor. Cylinder 3 Compression: Poor.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(WORN_CYLINDER));
		scene.idle(110);

		scene.overlay()
			.showText(100)
			.text("Worn components reduce performance and make starting harder.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(WORN_CYLINDER));
		scene.idle(110);

		// Service it - stopped first, which is a real rule the game enforces.
		scene.overlay()
			.showText(90)
			.text("Stop the engine before servicing it.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(R4_FIRST));
		scene.idle(100);

		scene.overlay()
			.showControls(centre(WORN_CYLINDER), Pointing.RIGHT, 40)
			.rightClick()
			.whileSneaking();
		scene.idle(25);
		scene.overlay()
			.showText(90)
			.text("Sneak and right-click to take the Piston Assembly out. It keeps its condition.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(WORN_CYLINDER));
		scene.idle(100);

		// THE NO-FREE-REPAIR RULE, demonstrated rather than asserted.
		scene.overlay()
			.showControls(centre(WORN_CYLINDER), Pointing.RIGHT, 40)
			.withItem(new ItemStack(ECItems.PISTON_ASSEMBLY.get()))
			.rightClick();
		scene.idle(25);
		scene.overlay()
			.showText(100)
			.colored(PonderPalette.RED)
			.text("Putting the same worn part back does not repair it.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(WORN_CYLINDER));
		scene.idle(110);

		scene.overlay()
			.showControls(centre(WORN_CYLINDER), Pointing.RIGHT, 40)
			.withItem(new ItemStack(ECItems.PISTON_ASSEMBLY.get()))
			.rightClick();
		scene.idle(25);
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "repaired", util.select()
				.position(WORN_CYLINDER), 70);
		scene.overlay()
			.showText(100)
			.colored(PonderPalette.GREEN)
			.text("A fresh Piston Assembly restores that cylinder's compression completely.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(WORN_CYLINDER));
		scene.idle(110);

		scene.overlay()
			.showText(90)
			.text("A worn Crankshaft section is replaced the same way, by mining and replacing it.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(new BlockPos(4, 2, 2)));
		scene.idle(100);

		// And the closing message, which is the headline again.
		scene.overlay()
			.showText(120)
			.colored(PonderPalette.GREEN)
			.text("Major internal parts normally need replacing only after severe or "
				+ "very long-term wear.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(R4_FIRST));
		scene.idle(130);
		scene.markAsFinished();
	}

	/** B9. Diagnosing an Engine. */
	public static void diagnosingAnEngine(SceneBuilder scene, SceneBuildingUtil util) {
		scene.title("diagnosing_an_engine", "Diagnosing an Engine");
		scene.configureBasePlate(0, 0, 5);
		scene.scaleSceneView(0.9F);
		scene.showBasePlate();
		scene.idle(10);
		scene.world()
			.showSection(util.select()
				.layersFrom(1), Direction.DOWN);
		scene.idle(20);

		BlockPos crank = new BlockPos(3, 2, 2);
		BlockPos cylinder = new BlockPos(3, 3, 2);
		BlockPos carburetor = new BlockPos(3, 4, 2);
		BlockPos sump = new BlockPos(3, 1, 2);

		scene.overlay()
			.showText(100)
			.text("Engineer's Goggles show what an engine is doing right now.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(crank));
		scene.idle(110);

		scene.overlay()
			.showText(110)
			.text("State, Speed, Generation, Active Cylinders, Throttle, Fuel, "
				+ "Lubrication and Condition.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(crank));
		scene.idle(120);

		scene.overlay()
			.showControls(centre(crank), Pointing.DOWN, 50)
			.whileSneaking();
		scene.idle(20);
		scene.overlay()
			.showText(100)
			.text("Sneak while looking at it for per-cylinder diagnostics.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(crank));
		scene.idle(110);

		// The common faults, each with the reading that identifies it.
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.RED)
			.text("No Gasoline: nothing can burn, so the engine will not run.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(carburetor));
		scene.idle(100);

		scene.overlay()
			.showOutline(PonderPalette.RED, "noplug", util.select()
				.position(cylinder), 80);
		scene.overlay()
			.showText(100)
			.text("A Cylinder with no Spark Plug cannot contribute. Active Cylinders drops.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(cylinder));
		scene.idle(110);

		scene.overlay()
			.showText(90)
			.colored(PonderPalette.MEDIUM)
			.text("Low or no oil shows as a Wear Risk warning.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(sump));
		scene.idle(100);

		scene.overlay()
			.showText(100)
			.text("A worn Piston shows as poor compression, and the engine makes less power.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(cylinder));
		scene.idle(110);

		// THE DISTINCTION the whole mod rests on.
		scene.overlay()
			.showText(110)
			.colored(PonderPalette.MEDIUM)
			.text("An engine turned by another machine reads Speed above zero, "
				+ "Generation Inactive, Capacity 0.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(new BlockPos(5, 2, 2)));
		scene.idle(120);

		scene.overlay()
			.showText(110)
			.colored(PonderPalette.GREEN)
			.text("Rotation does not necessarily mean the engine is producing power.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(crank));
		scene.idle(120);

		// ACTIVE is not the same question as HEALTHY.
		scene.overlay()
			.showText(120)
			.text("Active Cylinders counts cylinders that are firing, not healthy ones. "
				+ "4 / 4 with one worn bore is normal.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(cylinder));
		scene.idle(130);
		scene.markAsFinished();
	}

	/** B10. From Oil Shale to Fuel - the shape of the real recipe chain. */
	public static void fromShaleToFuel(SceneBuilder scene, SceneBuildingUtil util) {
		scene.title("from_shale_to_fuel", "From Oil Shale to Fuel");
		scene.configureBasePlate(0, 0, 7);
		scene.scaleSceneView(0.8F);
		scene.showBasePlate();
		scene.idle(10);
		scene.world()
			.showSection(util.select()
				.layersFrom(1), Direction.DOWN);
		scene.idle(20);

		BlockPos shale = new BlockPos(1, 1, 2);

		scene.overlay()
			.showText(90)
			.text("Oil Shale is found deep underground, and is where every engine's fuel starts.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(shale));
		scene.idle(100);

		scene.overlay()
			.showText(100)
			.text("Crush or mill it into Crushed Oil Shale. Crushing Wheels give twice the yield.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(shale));
		scene.idle(110);

		scene.overlay()
			.showText(100)
			.text("Heat Crushed Oil Shale with Water in a Basin to retort it into Crude Oil.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(shale));
		scene.idle(110);

		scene.overlay()
			.showText(100)
			.text("Crack the Crude Oil to get Gasoline, and Petroleum Residue alongside it.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(shale));
		scene.idle(110);

		scene.overlay()
			.showText(100)
			.text("Blend the Residue with a Zinc Nugget to make Engine Oil.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(shale));
		scene.idle(110);

		scene.overlay()
			.showText(110)
			.colored(PonderPalette.GREEN)
			.text("Gasoline runs the engine. Engine Oil keeps it alive. "
				+ "Check a recipe viewer for exact amounts.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(shale));
		scene.idle(120);
		scene.markAsFinished();
	}
}
