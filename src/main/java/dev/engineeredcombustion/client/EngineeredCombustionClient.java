package dev.engineeredcombustion.client;

import dev.engineeredcombustion.EngineeredCombustion;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import dev.engineeredcombustion.registry.ECFluids;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

/** Client-only entry point. */
@Mod(value = EngineeredCombustion.ID, dist = Dist.CLIENT)
public class EngineeredCombustionClient {

	public EngineeredCombustionClient(IEventBus modEventBus) {
		// Partial models have to be created before Minecraft loads models, so this
		// happens as early as possible - during mod construction, like Create does.
		ECPartialModels.init();

		modEventBus.addListener(EngineeredCombustionClient::registerRenderers);
		modEventBus.addListener(EngineeredCombustionClient::registerFluidExtensions);
	}

	/**
	 * Tells the client which textures gasoline is drawn with. Without this a fluid
	 * renders as a missing texture everywhere it appears - tanks, pipes, buckets.
	 */
	private static void registerFluidExtensions(RegisterClientExtensionsEvent event) {
		event.registerFluidType(new IClientFluidTypeExtensions() {

			@Override
			public ResourceLocation getStillTexture() {
				return ECFluids.GASOLINE_STILL_TEXTURE;
			}

			@Override
			public ResourceLocation getFlowingTexture() {
				return ECFluids.GASOLINE_FLOWING_TEXTURE;
			}
		}, ECFluids.GASOLINE_TYPE.get());
	}

	private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(ECBlockEntityTypes.CYLINDER.get(), CylinderRenderer::new);
		event.registerBlockEntityRenderer(ECBlockEntityTypes.FLYWHEEL.get(), EngineFlywheelRenderer::new);
	}
}
