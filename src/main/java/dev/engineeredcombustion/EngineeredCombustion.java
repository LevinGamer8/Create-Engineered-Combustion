package dev.engineeredcombustion;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import dev.engineeredcombustion.registry.ECBlocks;
import dev.engineeredcombustion.registry.ECCreativeTabs;
import dev.engineeredcombustion.registry.ECItems;
import dev.engineeredcombustion.registry.ECStressValues;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Common entry point of Create: Engineered Combustion.
 *
 * <p>Registration is deliberately split into small holder classes under
 * {@code dev.engineeredcombustion.registry} so that later milestones can add
 * blocks, items and block entities without this class growing.
 */
@Mod(EngineeredCombustion.ID)
public class EngineeredCombustion {

	public static final String ID = "engineered_combustion";
	public static final String NAME = "Create: Engineered Combustion";

	public static final Logger LOGGER = LogUtils.getLogger();

	public EngineeredCombustion(IEventBus modEventBus, ModContainer modContainer) {
		ECBlocks.register(modEventBus);
		ECItems.register(modEventBus);
		ECBlockEntityTypes.register(modEventBus);
		ECCreativeTabs.register(modEventBus);

		modEventBus.addListener(EngineeredCombustion::commonSetup);
	}

	private static void commonSetup(FMLCommonSetupEvent event) {
		// Create's SimpleRegistry is documented as safe to use during parallel mod
		// init, so this does not need to be deferred onto the main thread.
		ECStressValues.register();
	}

	public static ResourceLocation asResource(String path) {
		return ResourceLocation.fromNamespaceAndPath(ID, path);
	}
}
