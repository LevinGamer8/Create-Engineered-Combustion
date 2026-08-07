package dev.engineeredcombustion.registry;

import dev.engineeredcombustion.EngineeredCombustion;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Item registry. Engine components are added in milestone 1. */
public class ECItems {

	public static final DeferredRegister<Item> ITEMS =
		DeferredRegister.create(BuiltInRegistries.ITEM, EngineeredCombustion.ID);

	public static void register(IEventBus modEventBus) {
		ITEMS.register(modEventBus);
	}
}
