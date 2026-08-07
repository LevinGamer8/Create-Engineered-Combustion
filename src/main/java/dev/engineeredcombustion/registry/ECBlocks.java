package dev.engineeredcombustion.registry;

import dev.engineeredcombustion.EngineeredCombustion;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Block registry. Engine components are added in milestone 1. */
public class ECBlocks {

	public static final DeferredRegister<Block> BLOCKS =
		DeferredRegister.create(BuiltInRegistries.BLOCK, EngineeredCombustion.ID);

	/** Shared properties for the mod's machined metal parts. */
	static BlockBehaviour.Properties metal() {
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
