package dev.engineeredcombustion;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import dev.engineeredcombustion.registry.ECBlocks;
import dev.engineeredcombustion.registry.ECItems;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

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
	}

	public static ResourceLocation asResource(String path) {
		return ResourceLocation.fromNamespaceAndPath(ID, path);
	}
}
