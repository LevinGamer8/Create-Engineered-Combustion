package dev.engineeredcombustion.ponder;

import dev.engineeredcombustion.EngineeredCombustion;
import dev.engineeredcombustion.registry.ECBlocks;
import dev.engineeredcombustion.registry.ECItems;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;

/**
 * Everything this mod teaches through Create's own Ponder screen.
 *
 * <p>Ponder is the layer that answers <b>"how does this machine work?"</b>, and
 * it is deliberately the only place that answers it at length. Tooltips say what
 * an item is for, advancements suggest what to try next, recipes say how to make
 * things, and the goggles say what an engine is doing right now - none of those
 * is a good place for a paragraph about cranking, and this is.
 *
 * <p>Registered through Ponder's own plugin interface rather than by drawing a
 * competing tutorial screen, so the mod's scenes appear beside Create's, respond
 * to the same key hint on item tooltips, and are indexed in the same place a
 * player already knows to look.
 *
 * <h2>The scenes are technically true</h2>
 * Every one of them shows the game as it actually behaves. None demonstrates an
 * engine starting itself from a standstill, two valid Flywheels, a mandatory Air
 * Filter, mandatory redstone, a throttle that teleports the speed, an externally
 * turned engine producing power, one Spark Plug serving two cylinders, or a
 * healthy engine wearing out quickly. Where the real behaviour is awkward to
 * present - starting is genuinely multi-cycle and partly random - the scene
 * scripts the presentation rather than changing the game underneath it.
 */
public class ECPonderPlugin implements PonderPlugin {

	@Override
	public String getModId() {
		return EngineeredCombustion.ID;
	}

	/** The mod's own tag in the Ponder index, and everything filed under it. */
	public static final ResourceLocation ENGINES = EngineeredCombustion.asResource("engines");

	@Override
	public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
		helper.registerTag(ENGINES)
			.addToIndex()
			.item(ECBlocks.CRANKSHAFT.get(), true, true)
			.title("Engineered Combustion")
			.description("Inline combustion engines that burn Gasoline to drive a Create network. "
				+ "Built from parts, fuelled, lubricated, cranked by hand, and maintained.")
			.register();

		// Every component of an engine is filed under the tag, so a player who
		// opens the index on any one of them finds the rest.
		helper.addToTag(ENGINES)
			.add(key(ECBlocks.CRANKSHAFT.get()))
			.add(key(ECBlocks.CYLINDER.get()))
			.add(key(ECBlocks.FLYWHEEL.get()))
			.add(key(ECBlocks.CARBURETOR.get()))
			.add(key(ECBlocks.OIL_SUMP.get()))
			.add(key(ECItems.PISTON_ASSEMBLY.get()))
			.add(key(ECItems.SPARK_PLUG.get()))
			.add(key(ECItems.AIR_FILTER.get()))
			.add(key(ECItems.REDSTONE_CONTROL_MODULE.get()))
			.add(key(ECItems.GASOLINE_BUCKET.get()))
			.add(key(ECItems.ENGINE_OIL_BUCKET.get()))
			.add(key(ECBlocks.OIL_SHALE.get()));
	}

	@Override
	public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
		// Which items open which scene. A player right-clicks the Ponder key on an
		// item and gets every scene that item is associated with, so the association
		// list is really the answer to "what would somebody holding this want
		// explained?".

		helper.forComponents(key(ECBlocks.CRANKSHAFT.get()), key(ECBlocks.CYLINDER.get()),
			key(ECItems.PISTON_ASSEMBLY.get()), key(ECBlocks.FLYWHEEL.get()), key(ECItems.SPARK_PLUG.get()),
			key(ECItems.CAMSHAFT.get()), key(ECItems.AIR_FILTER.get()))
			.addStoryBoard("assembling_an_engine", EngineAssemblyScenes::buildingABasicEngine, ENGINES);

		helper.forComponents(key(ECBlocks.CARBURETOR.get()), key(ECBlocks.OIL_SUMP.get()),
			key(ECItems.GASOLINE_BUCKET.get()), key(ECItems.ENGINE_OIL_BUCKET.get()),
			key(ECItems.AIR_FILTER.get()))
			.addStoryBoard("fuel_and_lubrication", EngineAssemblyScenes::fuelAndLubrication, ENGINES);

		helper.forComponents(key(ECBlocks.CRANKSHAFT.get()), key(ECItems.SPARK_PLUG.get()),
			key(ECItems.CAMSHAFT.get()), key(ECBlocks.FLYWHEEL.get()))
			.addStoryBoard("starting_an_engine", EngineOperationScenes::startingAnEngine, ENGINES);

		// Filed under the parts that only make sense once you know what the strokes
		// are: the Camshaft that opens the valves, the Spark Plug that lights the
		// charge, the Cylinder it happens in and the Flywheel that carries the three
		// strokes which do not push.
		helper.forComponents(key(ECItems.CAMSHAFT.get()), key(ECBlocks.CYLINDER.get()),
			key(ECItems.SPARK_PLUG.get()), key(ECBlocks.FLYWHEEL.get()))
			.addStoryBoard("the_four_stroke_cycle", FourStrokeScenes::theFourStrokeCycle, ENGINES);

		helper.forComponents(key(ECBlocks.CRANKSHAFT.get()), key(ECBlocks.CYLINDER.get()),
			key(ECItems.PISTON_ASSEMBLY.get()))
			.addStoryBoard("inline_engines", EngineOperationScenes::inlineEngines, ENGINES);

		helper.forComponents(key(ECItems.REDSTONE_CONTROL_MODULE.get()), key(ECBlocks.CARBURETOR.get()))
			.addStoryBoard("engine_controls", EngineOperationScenes::engineControls, ENGINES);

		helper.forComponents(key(ECItems.PISTON_ASSEMBLY.get()), key(ECBlocks.CRANKSHAFT.get()),
			key(ECItems.AIR_FILTER.get()), key(ECItems.ENGINE_OIL_BUCKET.get()))
			.addStoryBoard("engine_maintenance", EngineMaintenanceScenes::engineMaintenance, ENGINES);

		helper.forComponents(key(ECBlocks.CRANKSHAFT.get()), key(ECBlocks.FLYWHEEL.get()),
			key(ECItems.SPARK_PLUG.get()))
			.addStoryBoard("diagnosing_an_engine", EngineMaintenanceScenes::diagnosingAnEngine, ENGINES);

		helper.forComponents(key(ECBlocks.OIL_SHALE.get()), key(ECItems.CRUSHED_OIL_SHALE.get()),
			key(ECItems.CRUDE_OIL_BUCKET.get()), key(ECItems.GASOLINE_BUCKET.get()),
			key(ECItems.ENGINE_OIL_BUCKET.get()))
			.addStoryBoard("from_shale_to_fuel", EngineMaintenanceScenes::fromShaleToFuel, ENGINES);
	}

	/** An item or block as the key Ponder files scenes under: its registry id. */
	private static ResourceLocation key(ItemLike item) {
		return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.asItem());
	}
}
