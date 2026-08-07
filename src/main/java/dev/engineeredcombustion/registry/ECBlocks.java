package dev.engineeredcombustion.registry;

import dev.engineeredcombustion.EngineeredCombustion;
import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlock;
import dev.engineeredcombustion.content.engine.cylinder.CylinderBlock;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlock;
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
