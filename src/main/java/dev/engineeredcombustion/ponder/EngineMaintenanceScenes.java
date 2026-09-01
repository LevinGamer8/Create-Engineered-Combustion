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
import net.minecraft.world.phys.Vec3;

/**
 * How an engine wears, how it is diagnosed, and where its fuel comes from.
 *
 * <p>The maintenance scene carries the single most important message in the mod
 * after "engines must be cranked": <b>a properly lubricated and filtered engine
 * wears extremely slowly</b>. It has to be careful not to teach the opposite by
 * accident - a tutorial that spends four steps replacing pistons leaves a player
 * believing pistons are consumables, which after the 13.1 rebalance they
 * emphatically are not.
 *
 * <p>Both engine scenes take their coordinates from {@link PonderEngine}, and
 * each step outlines the part its sentence is about: the oil lines mark the Oil
 * Sump, the filter line marks the Carburetor the filter clamps to <i>and</i> the
 * bores it protects, and the overspeed line marks the Flywheel an outside machine
 * would be driving it through.
 */
public class EngineMaintenanceScenes {

	/** The inline-4 the maintenance scene wears out and services. */
	private static final PonderEngine ENGINE = PonderEngine.of(2, 2, 2, 4);

	/** The inline-2 the diagnostics scene reads the goggle overlay against. */
	private static final PonderEngine PAIR = PonderEngine.of(3, 2, 2, 2);

	/**
	 * The bore the maintenance scene wears out: the third of four, so that the
	 * goggle line it quotes - "Cylinder 3 Compression: Poor" - names the cylinder
	 * the outline is actually around. Cylinder numbering counts from 1 at the
	 * controller, so cylinder 3 is index 2.
	 */
	private static final int WORN = 2;

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

		// THE HEADLINE. First, and stated as the normal case - marked on the two
		// components that make it true, which are the two things a player controls.
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "lubricated", util.select()
				.position(ENGINE.oilSump()), 110);
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "filtered", util.select()
				.position(ENGINE.carburetor()), 110);
		scene.overlay()
			.showText(110)
			.colored(PonderPalette.GREEN)
			.text("A properly lubricated and filtered engine wears extremely slowly.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.dipstick());
		scene.idle(120);

		// The two parts named are the crank run and the bores, so those are marked.
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "crankshafts", util.select()
				.fromTo(ENGINE.crankshaft(0), ENGINE.lastCrankshaft()), 110);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "pistons", util.select()
				.fromTo(ENGINE.cylinder(0), ENGINE.lastCylinder()), 110);
		scene.overlay()
			.showText(110)
			.text("Crankshafts and Piston Assemblies are not routine consumables.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.crankshaft(1)));
		scene.idle(120);

		// Then what actually wears one out. Each is a real multiplier, and each is
		// a relationship: the filter mounts on the Carburetor, the wear happens in
		// the bores, and both ends are marked.
		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "unfiltered", util.select()
				.position(ENGINE.carburetor()), 90);
		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "unfiltered_bores", util.select()
				.fromTo(ENGINE.cylinder(0), ENGINE.lastCylinder()), 90);
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.MEDIUM)
			.text("Unfiltered operation increases long-term cylinder wear.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.airFilter());
		scene.idle(100);

		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "low_oil", util.select()
				.position(ENGINE.oilSump()), 90);
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.MEDIUM)
			.text("Low oil increases friction and component wear.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.dipstick());
		scene.idle(100);

		// What is damaged by running dry is the bearings in the crank run, so the
		// run is marked alongside the empty pan.
		scene.overlay()
			.showOutline(PonderPalette.RED, "dry_pan", util.select()
				.position(ENGINE.oilSump()), 90);
		scene.overlay()
			.showOutline(PonderPalette.RED, "dry_bearings", util.select()
				.fromTo(ENGINE.crankshaft(0), ENGINE.lastCrankshaft()), 90);
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.RED)
			.text("Running without lubrication can cause serious damage.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.dipstick());
		scene.idle(100);

		// Overspeed arrives through the Flywheel, which is the one place an outside
		// machine is connected to the engine at all.
		scene.overlay()
			.showOutline(PonderPalette.RED, "overspeed", util.select()
				.position(ENGINE.flywheel()), 110);
		scene.overlay()
			.showText(110)
			.colored(PonderPalette.RED)
			.text("External machines can force an engine past its intended speed. "
				+ "Sustained overspeed wears it quickly.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.flywheel()));
		scene.idle(120);

		// A badly abused engine, and what the goggles say about it. One bore, the
		// one the quoted reading names.
		scene.overlay()
			.showOutline(PonderPalette.RED, "worn", util.select()
				.position(ENGINE.cylinder(WORN)), 100);
		scene.overlay()
			.showText(100)
			.text("Mechanical Condition: Poor. Cylinder 3 Compression: Poor.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.bore(WORN));
		scene.idle(110);

		scene.overlay()
			.showOutline(PonderPalette.RED, "worn_effect", util.select()
				.position(ENGINE.cylinder(WORN)), 100);
		scene.overlay()
			.showText(100)
			.text("Worn components reduce performance and make starting harder.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.bore(WORN));
		scene.idle(110);

		// Service it - stopped first, which is a real rule the game enforces. The
		// switch that stops it is on the controller, so that is what is pointed at.
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "stop_first", util.select()
				.position(ENGINE.crankshaft(0)), 90);
		scene.overlay()
			.showText(90)
			.text("Stop the engine before servicing it.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.ignition());
		scene.idle(100);

		scene.overlay()
			.showControls(ENGINE.bore(WORN), Pointing.RIGHT, 40)
			.rightClick()
			.whileSneaking();
		scene.idle(25);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "removing", util.select()
				.position(ENGINE.cylinder(WORN)), 90);
		scene.overlay()
			.showText(90)
			.text("Sneak and right-click to take the Piston Assembly out. It keeps its condition.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.bore(WORN));
		scene.idle(100);

		// THE NO-FREE-REPAIR RULE, demonstrated rather than asserted.
		scene.overlay()
			.showControls(ENGINE.bore(WORN), Pointing.RIGHT, 40)
			.withItem(new ItemStack(ECItems.PISTON_ASSEMBLY.get()))
			.rightClick();
		scene.idle(25);
		scene.overlay()
			.showOutline(PonderPalette.RED, "same_part", util.select()
				.position(ENGINE.cylinder(WORN)), 100);
		scene.overlay()
			.showText(100)
			.colored(PonderPalette.RED)
			.text("Putting the same worn part back does not repair it.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.bore(WORN));
		scene.idle(110);

		scene.overlay()
			.showControls(ENGINE.bore(WORN), Pointing.RIGHT, 40)
			.withItem(new ItemStack(ECItems.PISTON_ASSEMBLY.get()))
			.rightClick();
		scene.idle(25);
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "repaired", util.select()
				.position(ENGINE.cylinder(WORN)), 70);
		scene.overlay()
			.showText(100)
			.colored(PonderPalette.GREEN)
			.text("A fresh Piston Assembly restores that cylinder's compression completely.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(ENGINE.bore(WORN));
		scene.idle(110);

		// The section under that bore, not the bore: this line is about the
		// crankcase being a block a player mines and replaces.
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "worn_section", util.select()
				.position(ENGINE.crankshaft(WORN)), 90);
		scene.overlay()
			.showText(90)
			.text("A worn Crankshaft section is replaced the same way, by mining and replacing it.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.crankshaft(WORN)));
		scene.idle(100);

		// And the closing message, which is the headline again: the parts that
		// wear, and the fact that they rarely need touching.
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "healthy_crank", util.select()
				.fromTo(ENGINE.crankshaft(0), ENGINE.lastCrankshaft()), 120);
		scene.overlay()
			.showOutline(PonderPalette.GREEN, "healthy_bores", util.select()
				.fromTo(ENGINE.cylinder(0), ENGINE.lastCylinder()), 120);
		scene.overlay()
			.showText(120)
			.colored(PonderPalette.GREEN)
			.text("Major internal parts normally need replacing only after severe or "
				+ "very long-term wear.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(ENGINE.crankshaft(1)));
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

		// The goggles read the whole engine, and this is one of the few steps where
		// a box around all of it is the honest highlight rather than a lazy one.
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "whole_engine", util.select()
				.fromTo(PAIR.oilSump(), PAIR.carburetorSeat(PAIR.sections() - 1)), 100);
		scene.overlay()
			.showText(100)
			.text("Engineer's Goggles show what an engine is doing right now.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(PAIR.crankshaft(0)));
		scene.idle(110);

		scene.overlay()
			.showOutline(PonderPalette.WHITE, "readings", util.select()
				.fromTo(PAIR.oilSump(), PAIR.carburetorSeat(PAIR.sections() - 1)), 110);
		scene.overlay()
			.showText(110)
			.text("State, Speed, Generation, Active Cylinders, Throttle, Fuel, "
				+ "Lubrication and Condition.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(PAIR.crankshaft(0)));
		scene.idle(120);

		// Per-cylinder diagnostics, so the cylinders are what is marked.
		scene.overlay()
			.showControls(centre(PAIR.crankshaft(0)), Pointing.DOWN, 50)
			.whileSneaking();
		scene.idle(20);
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "per_cylinder", util.select()
				.fromTo(PAIR.cylinder(0), PAIR.lastCylinder()), 100);
		scene.overlay()
			.showText(100)
			.text("Sneak while looking at it for per-cylinder diagnostics.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(PAIR.crankshaft(0)));
		scene.idle(110);

		// The common faults, each with the reading that identifies it, each marked
		// on the component that carries the fault.
		scene.overlay()
			.showOutline(PonderPalette.RED, "no_fuel", util.select()
				.position(PAIR.carburetor()), 90);
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.RED)
			.text("No Gasoline: nothing can burn, so the engine will not run.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(PAIR.floatBowl());
		scene.idle(100);

		scene.overlay()
			.showOutline(PonderPalette.RED, "noplug", util.select()
				.position(PAIR.cylinder(0)), 80);
		scene.overlay()
			.showText(100)
			.text("A Cylinder with no Spark Plug cannot contribute. Active Cylinders drops.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(PAIR.sparkPlug(0));
		scene.idle(110);

		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "wear_risk", util.select()
				.position(PAIR.oilSump()), 90);
		scene.overlay()
			.showText(90)
			.colored(PonderPalette.MEDIUM)
			.text("Low or no oil shows as a Wear Risk warning.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(PAIR.dipstick());
		scene.idle(100);

		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "compression", util.select()
				.position(PAIR.cylinder(0)), 100);
		scene.overlay()
			.showText(100)
			.text("A worn Piston shows as poor compression, and the engine makes less power.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(PAIR.bore(0));
		scene.idle(110);

		// THE DISTINCTION the whole mod rests on. An outside machine reaches the
		// engine through the Flywheel, so the Flywheel is what both lines mark.
		scene.overlay()
			.showOutline(PonderPalette.MEDIUM, "motored", util.select()
				.position(PAIR.flywheel()), 110);
		scene.overlay()
			.showText(110)
			.colored(PonderPalette.MEDIUM)
			.text("An engine turned by another machine reads Speed above zero, "
				+ "Generation Inactive, Capacity 0.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(PAIR.flywheel()));
		scene.idle(120);

		scene.overlay()
			.showOutline(PonderPalette.GREEN, "rotation", util.select()
				.position(PAIR.flywheel()), 110);
		scene.overlay()
			.showText(110)
			.colored(PonderPalette.GREEN)
			.text("Rotation does not necessarily mean the engine is producing power.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(PAIR.flywheel()));
		scene.idle(120);

		// ACTIVE is not the same question as HEALTHY.
		scene.overlay()
			.showOutline(PonderPalette.WHITE, "active_count", util.select()
				.fromTo(PAIR.cylinder(0), PAIR.lastCylinder()), 120);
		scene.overlay()
			.showText(120)
			.text("Active Cylinders counts cylinders that are firing, not healthy ones. "
				+ "4 / 4 with one worn bore is normal.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(PAIR.bore(1));
		scene.idle(130);
		scene.markAsFinished();
	}

	/** The Oil Shale this scene starts from - the one block in its structure. */
	private static final BlockPos SHALE = new BlockPos(1, 1, 2);

	/**
	 * B10. From Oil Shale to Fuel - the shape of the real recipe chain.
	 *
	 * <p>The only scene whose subject is not a machine. Every step of a petroleum
	 * chain is an <i>item</i> or a <i>fluid</i>, and its structure holds one block
	 * - so pointing at that block on every line, which is what this scene used to
	 * do, told the reader nothing after the first step. Each step now shows the
	 * thing it is about, laid out left to right along the plate so the chain reads
	 * as a chain.
	 *
	 * <p>The machines themselves are deliberately not staged. They are Create's -
	 * Crushing Wheels, a Basin, a Mixer - and a recipe viewer shows the exact
	 * amounts better than a scene can. What this scene is for is the shape: which
	 * material becomes which, and in what order.
	 */
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

		scene.overlay()
			.showOutline(PonderPalette.WHITE, "shale", util.select()
				.position(SHALE), 90);
		scene.overlay()
			.showText(90)
			.text("Oil Shale is found deep underground, and is where every engine's fuel starts.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(centre(SHALE));
		scene.idle(100);

		// Each link of the chain, held up at its own place in the line. Written out
		// rather than looped: Ponder numbers a scene's text keys by the order the
		// lines appear in this source, and tools/generate_ponder_lang.py reads the
		// English straight out of it, so every line has to be a literal in the scene
		// that shows it rather than an argument to a helper.
		scene.overlay()
			.showControls(product(1), Pointing.DOWN, 100)
			.withItem(new ItemStack(ECItems.CRUSHED_OIL_SHALE.get()));
		scene.overlay()
			.showText(100)
			.text("Crush or mill it into Crushed Oil Shale. Crushing Wheels give twice the yield.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(product(1));
		scene.idle(110);

		scene.overlay()
			.showControls(product(2), Pointing.DOWN, 100)
			.withItem(new ItemStack(ECItems.CRUDE_OIL_BUCKET.get()));
		scene.overlay()
			.showText(100)
			.text("Heat Crushed Oil Shale with Water in a Basin to retort it into Crude Oil.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(product(2));
		scene.idle(110);

		// Cracking gives two things at once, so two are shown at once.
		scene.overlay()
			.showControls(product(3), Pointing.DOWN, 100)
			.withItem(new ItemStack(ECItems.GASOLINE_BUCKET.get()));
		scene.overlay()
			.showControls(product(4), Pointing.DOWN, 100)
			.withItem(new ItemStack(ECItems.PETROLEUM_RESIDUE.get()));
		scene.overlay()
			.showText(100)
			.text("Crack the Crude Oil to get Gasoline, and Petroleum Residue alongside it.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(product(3));
		scene.idle(110);

		scene.overlay()
			.showControls(product(4), Pointing.DOWN, 100)
			.withItem(new ItemStack(ECItems.PETROLEUM_RESIDUE.get()));
		scene.overlay()
			.showControls(product(5), Pointing.DOWN, 100)
			.withItem(new ItemStack(ECItems.ENGINE_OIL_BUCKET.get()));
		scene.overlay()
			.showText(100)
			.text("Blend the Residue with a Zinc Nugget to make Engine Oil.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(product(5));
		scene.idle(110);

		// The two things the whole chain is for, side by side at the end of it.
		scene.overlay()
			.showControls(product(3), Pointing.DOWN, 110)
			.withItem(new ItemStack(ECItems.GASOLINE_BUCKET.get()));
		scene.overlay()
			.showControls(product(5), Pointing.DOWN, 110)
			.withItem(new ItemStack(ECItems.ENGINE_OIL_BUCKET.get()));
		scene.overlay()
			.showText(110)
			.colored(PonderPalette.GREEN)
			.text("Gasoline runs the engine. Engine Oil keeps it alive. "
				+ "Check a recipe viewer for exact amounts.")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(product(4));
		scene.idle(120);
		scene.markAsFinished();
	}

	/** Where the {@code index}th product of the chain is held up, left to right. */
	private static Vec3 product(int index) {
		return new Vec3(SHALE.getX() + 0.5D + index, SHALE.getY() + 1.6D, SHALE.getZ() + 0.5D);
	}
}
