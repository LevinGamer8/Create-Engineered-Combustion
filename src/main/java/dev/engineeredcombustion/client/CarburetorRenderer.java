package dev.engineeredcombustion.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.carburetor.CarburetorBlockEntity;
import net.createmod.catnip.platform.NeoForgeCatnipServices;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Draws the three parts of the Carburetor that are not fixed geometry: the fuel
 * standing in the float bowl, the throttle lever, and the Air Filter when one is
 * fitted.
 *
 * <p>Everything here reads state the block entity already synchronises. Nothing
 * is interpolated, guessed or timed locally, so what the player sees on the
 * carburetor is what the engine will actually do with it.
 */
public class CarburetorRenderer implements BlockEntityRenderer<CarburetorBlockEntity> {

	/**
	 * The float bowl's interior, in block-local 1/16 units. Must match the bowl
	 * built by {@code carburetor_elements()} in
	 * {@code tools/generate_engine_models.py}: the model leaves the +Z face open
	 * as a sight window, and this is the volume behind it.
	 */
	// Inset a hair from the bowl's inner walls on every side that has one, so the
	// fluid's faces never z-fight with the casting they sit against. The +Z face
	// stops just short of the opening instead, where there is no wall to fight.
	private static final float BOWL_X_MIN = 6.25F / 16.0F;
	private static final float BOWL_X_MAX = 9.75F / 16.0F;
	private static final float BOWL_Z_MIN = 5.05F / 16.0F;
	private static final float BOWL_Z_MAX = 7.75F / 16.0F;
	private static final float BOWL_Y_MIN = 1.85F / 16.0F;
	private static final float BOWL_Y_MAX = 4.4F / 16.0F;

	/** Where the throttle shaft's outer end is, in block-local 1/16 units. */
	private static final float PIVOT_X = 12.0F;
	private static final float PIVOT_Y = 5.6F;
	private static final float PIVOT_Z = 2.6F;

	public CarburetorRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(CarburetorBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
		int overlay) {
		BlockState state = be.getBlockState();

		renderFuelLevel(be, ms, buffer, light);
		renderThrottleLever(be, state, ms, buffer, light);

		if (be.hasAirFilter())
			CachedBuffers.partial(ECPartialModels.AIR_FILTER, state)
				.light(light)
				.renderInto(ms, buffer.getBuffer(RenderType.solid()));
	}

	/**
	 * Fills the float bowl to the tank's real level.
	 *
	 * <p>The height is quantised to {@link EngineTuning#FUEL_LEVEL_RENDER_STEPS}
	 * by the block entity, which is what allows the tank's synchronisation to be
	 * throttled: a continuous height would make every single millibucket visible,
	 * and therefore worth sending.
	 *
	 * <p>Drawn with catnip's fluid renderer, so it picks up gasoline's own still
	 * texture and tint rather than an approximation of them - the amber in the
	 * bowl is literally the fluid the engine burns.
	 */
	private static void renderFuelLevel(CarburetorBlockEntity be, PoseStack ms, MultiBufferSource buffer, int light) {
		FluidStack fluid = be.getFluid();
		if (fluid.isEmpty())
			return;
		float fill = be.getFuelFillFraction();
		if (fill <= 0.0F)
			return;

		float surface = BOWL_Y_MIN + (BOWL_Y_MAX - BOWL_Y_MIN) * fill;
		// renderBottom is false: the bowl's own floor is directly underneath, so the
		// downward face would only ever z-fight with it.
		NeoForgeCatnipServices.FLUID_RENDERER.renderFluidBox(fluid, BOWL_X_MIN, BOWL_Y_MIN, BOWL_Z_MIN, BOWL_X_MAX,
			surface, BOWL_Z_MAX, buffer, ms, light, false, true);
	}

	/**
	 * Swings the throttle lever to the angle the authoritative throttle setting
	 * implies.
	 *
	 * <p>The lever model is authored with its pivot on the block centre, so
	 * {@code rotateCentered} turns it about the pivot and the translate then puts
	 * that pivot on the real throttle shaft. Because catnip applies the buffer's
	 * transforms in reverse call order, translating first and rotating second is
	 * what produces "rotate about the pivot, then move the pivot into place" - the
	 * same trick the connecting rod uses about its wrist pin.
	 */
	private static void renderThrottleLever(CarburetorBlockEntity be, BlockState state, PoseStack ms,
		MultiBufferSource buffer, int light) {
		float angle = (float) Math.toRadians(EngineTuning.throttleLeverDegrees(be.getThrottle()));

		CachedBuffers.partial(ECPartialModels.THROTTLE_LEVER, state)
			.translate((PIVOT_X - 8.0F) / 16.0F, (PIVOT_Y - 8.0F) / 16.0F, (PIVOT_Z - 8.0F) / 16.0F)
			.rotateCentered(angle, Direction.EAST)
			.light(light)
			.renderInto(ms, buffer.getBuffer(RenderType.solid()));
	}
}
