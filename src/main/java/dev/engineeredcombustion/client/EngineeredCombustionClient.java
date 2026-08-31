package dev.engineeredcombustion.client;

import dev.engineeredcombustion.EngineeredCombustion;
import dev.engineeredcombustion.ponder.ECPonderPlugin;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import net.createmod.ponder.foundation.PonderIndex;

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

		// Ponder is client-only and its index is a plain static registry, so the
		// plugin goes in during construction like the partial models above rather
		// than waiting for an event. PonderIndex.addPlugin synchronises internally,
		// which is what makes that safe during parallel mod loading.
		PonderIndex.addPlugin(new ECPonderPlugin());

		modEventBus.addListener(EngineeredCombustionClient::registerRenderers);
	}

	private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(ECBlockEntityTypes.CRANKSHAFT.get(), CrankshaftRenderer::new);
		event.registerBlockEntityRenderer(ECBlockEntityTypes.CYLINDER.get(), CylinderRenderer::new);
		event.registerBlockEntityRenderer(ECBlockEntityTypes.FLYWHEEL.get(), EngineFlywheelRenderer::new);
		event.registerBlockEntityRenderer(ECBlockEntityTypes.CARBURETOR.get(), CarburetorRenderer::new);
	}
}
