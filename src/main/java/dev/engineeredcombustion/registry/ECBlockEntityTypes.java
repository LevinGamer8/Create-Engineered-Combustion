package dev.engineeredcombustion.registry;

import dev.engineeredcombustion.EngineeredCombustion;
import dev.engineeredcombustion.content.engine.carburetor.CarburetorBlockEntity;
import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlockEntity;
import dev.engineeredcombustion.content.engine.cylinder.CylinderBlockEntity;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ECBlockEntityTypes {

	public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
		DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, EngineeredCombustion.ID);

	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CrankshaftBlockEntity>> CRANKSHAFT =
		BLOCK_ENTITY_TYPES.register("crankshaft", () -> BlockEntityType.Builder
			.of(CrankshaftBlockEntity::new, ECBlocks.CRANKSHAFT.get())
			.build(null));

	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CylinderBlockEntity>> CYLINDER =
		BLOCK_ENTITY_TYPES.register("cylinder", () -> BlockEntityType.Builder
			.of(CylinderBlockEntity::new, ECBlocks.CYLINDER.get())
			.build(null));

	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EngineFlywheelBlockEntity>> FLYWHEEL =
		BLOCK_ENTITY_TYPES.register("flywheel", () -> BlockEntityType.Builder
			.of(EngineFlywheelBlockEntity::new, ECBlocks.FLYWHEEL.get())
			.build(null));

	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CarburetorBlockEntity>> CARBURETOR =
		BLOCK_ENTITY_TYPES.register("carburetor", () -> BlockEntityType.Builder
			.of(CarburetorBlockEntity::new, ECBlocks.CARBURETOR.get())
			.build(null));

	public static void register(IEventBus modEventBus) {
		BLOCK_ENTITY_TYPES.register(modEventBus);
	}
}
