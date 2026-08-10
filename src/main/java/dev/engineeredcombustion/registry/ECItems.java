package dev.engineeredcombustion.registry;

import dev.engineeredcombustion.EngineeredCombustion;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

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

	public static final DeferredHolder<Item, BlockItem> CARBURETOR =
		ITEMS.register("carburetor", () -> new BlockItem(ECBlocks.CARBURETOR.get(), new Item.Properties()));

	/**
	 * An air cleaner, fitted <i>onto</i> a placed Carburetor rather than being a
	 * block of its own.
	 *
	 * <p>The engine already stands five blocks tall, and a full block for every
	 * bolt-on part would make it unbuildable in a normal room. An air cleaner is
	 * also genuinely a part of the carburetor rather than a machine beside it, so
	 * an item that installs into one is both the smaller and the more honest
	 * model - the same call the Piston Assembly makes about the Cylinder.
	 */
	public static final DeferredHolder<Item, Item> AIR_FILTER =
		ITEMS.register("air_filter", () -> new Item(new Item.Properties().stacksTo(16)));

	/**
	 * Vanilla's BucketItem needs the Fluid itself, not a supplier. That is safe
	 * here because the FLUID registry is populated before the ITEM registry, so
	 * the fluid already exists by the time this supplier runs.
	 */
	public static final DeferredHolder<Item, BucketItem> GASOLINE_BUCKET =
		ITEMS.register("gasoline_bucket", () -> new BucketItem(ECFluids.GASOLINE.get(),
			new Item.Properties().craftRemainder(Items.BUCKET)
				.stacksTo(1)));

	public static final DeferredHolder<Item, BlockItem> OIL_SUMP =
		ITEMS.register("oil_sump", () -> new BlockItem(ECBlocks.OIL_SUMP.get(), new Item.Properties()));

	public static final DeferredHolder<Item, BucketItem> ENGINE_OIL_BUCKET =
		ITEMS.register("engine_oil_bucket", () -> new BucketItem(ECFluids.ENGINE_OIL.get(),
			new Item.Properties().craftRemainder(Items.BUCKET)
				.stacksTo(1)));

	public static void register(IEventBus modEventBus) {
		ITEMS.register(modEventBus);
	}
}
