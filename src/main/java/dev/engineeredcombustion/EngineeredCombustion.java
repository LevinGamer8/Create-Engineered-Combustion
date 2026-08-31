package dev.engineeredcombustion;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.engineeredcombustion.network.ECPackets;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import dev.engineeredcombustion.registry.ECBlocks;
import dev.engineeredcombustion.registry.ECDataComponents;
import dev.engineeredcombustion.registry.ECCreativeTabs;
import dev.engineeredcombustion.registry.ECFluids;
import dev.engineeredcombustion.registry.ECItems;
import dev.engineeredcombustion.registry.ECSounds;
import dev.engineeredcombustion.registry.ECStressValues;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

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
		ECFluids.register(modEventBus);
		ECBlocks.register(modEventBus);
		ECItems.register(modEventBus);
		ECBlockEntityTypes.register(modEventBus);
		ECCreativeTabs.register(modEventBus);
		// The wear an engine part carries when it is not installed in anything. See
		// ECDataComponents: this is what stops breaking and replacing a worn part from
		// being a free repair.
		ECDataComponents.register(modEventBus);
		ECSounds.register(modEventBus);

		modEventBus.addListener(EngineeredCombustion::commonSetup);
		modEventBus.addListener(EngineeredCombustion::registerCapabilities);
		// Clientbound, but registered on both sides: a server has to have a payload in
		// its registry before it is allowed to send it. The handler behind it is
		// dist-guarded - see ECPackets.
		modEventBus.addListener(ECPackets::register);
	}

	private static void commonSetup(FMLCommonSetupEvent event) {
		// Create's SimpleRegistry is documented as safe to use during parallel mod
		// init, so this does not need to be deferred onto the main thread.
		ECStressValues.register();
	}

	/**
	 * Exposes the Carburetor's and Oil Sump's tanks through the standard NeoForge
	 * fluid capability. These two registrations are what let Create's pipes,
	 * vanilla buckets and any other mod's transport fill them, with no per-mod
	 * code and no special-cased pipe integration.
	 */
	private static void registerCapabilities(RegisterCapabilitiesEvent event) {
		event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ECBlockEntityTypes.CARBURETOR.get(),
			(blockEntity, side) -> blockEntity.getFluidHandler());
		event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ECBlockEntityTypes.OIL_SUMP.get(),
			(blockEntity, side) -> blockEntity.getFluidHandler());
	}

	public static ResourceLocation asResource(String path) {
		return ResourceLocation.fromNamespaceAndPath(ID, path);
	}
}
