package dev.engineeredcombustion.registry;

import dev.engineeredcombustion.EngineeredCombustion;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ECItems {

	public static final DeferredRegister<Item> ITEMS =
		DeferredRegister.create(BuiltInRegistries.ITEM, EngineeredCombustion.ID);

	/**
	 * For milestone 1 this single item stands in for the piston <i>and</i> its
	 * connecting rod. It is inserted into a placed Cylinder rather than being a
	 * block of its own, because a piston and a cylinder occupy the same volume.
	 */
	public static final DeferredHolder<Item, Item> PISTON_ASSEMBLY =
		ITEMS.register("piston_assembly", () -> new Item(new Item.Properties().stacksTo(16)));

	public static final DeferredHolder<Item, BlockItem> CRANKSHAFT =
		ITEMS.register("crankshaft", () -> new BlockItem(ECBlocks.CRANKSHAFT.get(), new Item.Properties()));

	public static final DeferredHolder<Item, BlockItem> CYLINDER =
		ITEMS.register("cylinder", () -> new BlockItem(ECBlocks.CYLINDER.get(), new Item.Properties()));

	public static final DeferredHolder<Item, BlockItem> FLYWHEEL =
		ITEMS.register("flywheel", () -> new BlockItem(ECBlocks.FLYWHEEL.get(), new Item.Properties()));

	public static void register(IEventBus modEventBus) {
		ITEMS.register(modEventBus);
	}
}
