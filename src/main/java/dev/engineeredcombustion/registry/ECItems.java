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
	 * Optional redstone automation for an engine, fitted <i>into</i> a placed
	 * Crankshaft.
	 *
	 * <p>The engine is complete without it: the ignition switch on the crankcase
	 * and the throttle on the Carburetor are the whole of the controls, and an
	 * engine with no module ignores redstone entirely. Installing one adds the
	 * choice of letting a signal hold the ignition, the throttle, or both - see
	 * {@code ControlMode}.
	 *
	 * <p>An item rather than a block, and for the same reason the Air Filter is
	 * one: it is a part that plugs into a machine, not a machine of its own.
	 */
	public static final DeferredHolder<Item, Item> REDSTONE_CONTROL_MODULE =
		ITEMS.register("redstone_control_module", () -> new Item(new Item.Properties().stacksTo(16)));

	/**
	 * The ignition component, screwed <i>into</i> a placed Cylinder's head.
	 *
	 * <p>An item for the same reason the Piston Assembly is one: a spark plug
	 * occupies a hole in a cylinder head, not a block of its own. Unlike the Air
	 * Filter and the Redstone Control Module it is not optional - a gasoline
	 * engine with no plug turns over perfectly well and never fires. See
	 * {@code EngineState#tickSimulation}.
	 */
	public static final DeferredHolder<Item, Item> SPARK_PLUG =
		ITEMS.register("spark_plug", () -> new Item(new Item.Properties().stacksTo(16)));

	// --- petroleum chain ----------------------------------------------------

	public static final DeferredHolder<Item, BlockItem> OIL_SHALE =
		ITEMS.register("oil_shale", () -> new BlockItem(ECBlocks.OIL_SHALE.get(), new Item.Properties()));

	/** Crushing or milling an Oil Shale block; the feedstock for Crude Oil. */
	public static final DeferredHolder<Item, Item> CRUSHED_OIL_SHALE =
		ITEMS.register("crushed_oil_shale", () -> new Item(new Item.Properties()));

	/**
	 * The heavy bottom fraction left behind when Crude Oil is cracked into
	 * Gasoline, and the only route to Engine Oil.
	 *
	 * <p>An item rather than a fourth fluid on purpose: it keeps the refinery to
	 * one new fluid, and a basin that emits one fluid and one item needs no
	 * second output pipe to be usable.
	 */
	public static final DeferredHolder<Item, Item> PETROLEUM_RESIDUE =
		ITEMS.register("petroleum_residue", () -> new Item(new Item.Properties()));

	// --- sequenced assembly intermediates -----------------------------------
	//
	// Create's Sequenced Assembly carries its progress on a real item that sits on
	// the depot between steps, and every recipe needs its own: the progress data
	// component names the recipe it belongs to, so two recipes sharing one
	// transitional item would each see the other's half-finished work.

	public static final DeferredHolder<Item, Item> INCOMPLETE_PISTON_ASSEMBLY =
		ITEMS.register("incomplete_piston_assembly", () -> new Item(new Item.Properties()));

	public static final DeferredHolder<Item, Item> INCOMPLETE_CARBURETOR =
		ITEMS.register("incomplete_carburetor", () -> new Item(new Item.Properties()));

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

	/**
	 * <b>Every bucket here is a plain {@link BucketItem}, and that is
	 * load-bearing.</b> NeoForge attaches the fluid-handler capability that makes
	 * a bucket fillable only to items whose class is <i>exactly</i>
	 * {@code BucketItem} ({@code CapabilityHooks}: {@code item.getClass() ==
	 * BucketItem.class}), and Create re-checks the same thing in
	 * {@code GenericItemFilling#isFluidHandlerValid}. A subclass - however small
	 * the override - would silently lose the capability, and with it Create's
	 * Spout and Item Drain, {@code FluidUtil.interactWithFluidHandler}, and
	 * therefore the ability to fill a Carburetor or an Oil Sump from a bucket at
	 * all.
	 */
	public static final DeferredHolder<Item, BucketItem> CRUDE_OIL_BUCKET =
		ITEMS.register("crude_oil_bucket", () -> new BucketItem(ECFluids.CRUDE_OIL.get(),
			new Item.Properties().craftRemainder(Items.BUCKET)
				.stacksTo(1)));

	public static void register(IEventBus modEventBus) {
		ITEMS.register(modEventBus);
	}
}
