package dev.engineeredcombustion.client;

import dev.engineeredcombustion.EngineeredCombustion;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client-only entry point. */
@Mod(value = EngineeredCombustion.ID, dist = Dist.CLIENT)
public class EngineeredCombustionClient {

	public EngineeredCombustionClient(IEventBus modEventBus) {
		// Partial models have to be created before Minecraft loads models, so this
		// happens as early as possible - during mod construction, like Create does.
		ECPartialModels.init();

		modEventBus.addListener(EngineeredCombustionClient::registerRenderers);
	}

	private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(ECBlockEntityTypes.CRANKSHAFT.get(), CrankshaftRenderer::new);
		event.registerBlockEntityRenderer(ECBlockEntityTypes.CYLINDER.get(), CylinderRenderer::new);
		event.registerBlockEntityRenderer(ECBlockEntityTypes.FLYWHEEL.get(), EngineFlywheelRenderer::new);
	}
}
