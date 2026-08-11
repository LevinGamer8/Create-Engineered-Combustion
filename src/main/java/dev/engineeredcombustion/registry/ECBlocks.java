package dev.engineeredcombustion.registry;

import dev.engineeredcombustion.EngineeredCombustion;
import dev.engineeredcombustion.content.engine.carburetor.CarburetorBlock;
import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlock;
import dev.engineeredcombustion.content.engine.cylinder.CylinderBlock;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlock;
import dev.engineeredcombustion.content.engine.sump.OilSumpBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ECBlocks {

	public static final DeferredRegister<Block> BLOCKS =
		DeferredRegister.create(BuiltInRegistries.BLOCK, EngineeredCombustion.ID);

	public static final DeferredHolder<Block, CrankshaftBlock> CRANKSHAFT =
		BLOCKS.register("crankshaft", () -> new CrankshaftBlock(metal().noOcclusion()));

	public static final DeferredHolder<Block, CylinderBlock> CYLINDER =
		BLOCKS.register("cylinder", () -> new CylinderBlock(metal().noOcclusion()));

	public static final DeferredHolder<Block, EngineFlywheelBlock> FLYWHEEL =
		BLOCKS.register("flywheel", () -> new EngineFlywheelBlock(metal().noOcclusion()));

	public static final DeferredHolder<Block, CarburetorBlock> CARBURETOR =
		BLOCKS.register("carburetor", () -> new CarburetorBlock(metal().noOcclusion()));

	public static final DeferredHolder<Block, OilSumpBlock> OIL_SUMP =
		BLOCKS.register("oil_sump", () -> new OilSumpBlock(metal().noOcclusion()));

	/**
	 * The mod's one worldgen block: a petroleum-bearing sedimentary rock, and the
	 * standalone Survival entry point into the whole fuel chain.
	 *
	 * <p>A plain {@link Block} rather than a {@code DropExperienceBlock}: it drops
	 * <i>itself</i> and is then processed, so there is no raw-material item to give
	 * experience for, and no silk-touch special case to get wrong. See
	 * {@code data/engineered_combustion/loot_table/blocks/oil_shale.json}.
	 *
	 * <p>Modelled on vanilla stone ore rather than on this mod's metal parts:
	 * stone sounds, stone strength, and a correct tool required, so a bare hand
	 * cannot mine petroleum out of the ground.
	 */
	public static final DeferredHolder<Block, Block> OIL_SHALE =
		BLOCKS.register("oil_shale", () -> new Block(BlockBehaviour.Properties.of()
			.mapColor(MapColor.TERRACOTTA_GRAY)
			.strength(3.0F, 3.0F)
			.requiresCorrectToolForDrops()
			.sound(SoundType.STONE)));

	private static BlockBehaviour.Properties metal() {
		return BlockBehaviour.Properties.of()
			.mapColor(MapColor.METAL)
			.strength(3.0F, 6.0F)
			.requiresCorrectToolForDrops()
			.sound(SoundType.NETHERITE_BLOCK);
	}

	public static void register(IEventBus modEventBus) {
		BLOCKS.register(modEventBus);
	}
}
