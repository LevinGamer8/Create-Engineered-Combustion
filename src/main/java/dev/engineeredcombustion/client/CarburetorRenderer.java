package dev.engineeredcombustion.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.carburetor.CarburetorBlock;
import dev.engineeredcombustion.content.engine.carburetor.CarburetorBlockEntity;
import dev.engineeredcombustion.foundation.EngineAxis;
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
		// A carburetor on an engine running along Z is drawn a quarter turn round,
		// so that it stays on the intake side of the head it feeds. The blockstate
		// turns the baked body; everything drawn here is a partial model or a raw
		// box, neither of which that reaches, so each of the three has to make the
		// same turn itself. `turned` is the single answer all three ask.
		boolean turned = state.hasProperty(CarburetorBlock.AXIS)
			&& state.getValue(CarburetorBlock.AXIS) == EngineAxis.Z;

		renderFuelLevel(be, turned, ms, buffer, light);
		renderThrottleLever(be, state, turned, ms, buffer, light);

		if (be.hasAirFilter())
			CachedBuffers.partial(turned ? ECPartialModels.AIR_FILTER_Z : ECPartialModels.AIR_FILTER_X, state)
				.light(light)
				.renderInto(ms, buffer.getBuffer(RenderType.solid()));
	}

	/**
	 * A quarter turn about the middle of the block, in block fractions: the same
	 * turn a blockstate's {@code "y": 90} applies to a baked model, which maps
	 * (x, z) to (1 - z, x).
	 */
	private static float turnedX(float z) {
		return 1.0F - z;
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
	private static void renderFuelLevel(CarburetorBlockEntity be, boolean turned, PoseStack ms,
		MultiBufferSource buffer, int light) {
		FluidStack fluid = be.getFluid();
		if (fluid.isEmpty())
			return;
		float fill = be.getFuelFillFraction();
		if (fill <= 0.0F)
			return;

		float surface = BOWL_Y_MIN + (BOWL_Y_MAX - BOWL_Y_MIN) * fill;
		// The bowl is a box rather than a model, so the quarter turn is applied to
		// its corners: the two x bounds come from the z ones, in the other order,
		// because turning clockwise about the block's middle reverses that axis.
		float x0 = turned ? turnedX(BOWL_Z_MAX) : BOWL_X_MIN;
		float x1 = turned ? turnedX(BOWL_Z_MIN) : BOWL_X_MAX;
		float z0 = turned ? BOWL_X_MIN : BOWL_Z_MIN;
		float z1 = turned ? BOWL_X_MAX : BOWL_Z_MAX;
		// renderBottom is false: the bowl's own floor is directly underneath, so the
		// downward face would only ever z-fight with it.
		NeoForgeCatnipServices.FLUID_RENDERER.renderFluidBox(fluid, x0, BOWL_Y_MIN, z0, x1,
			surface, z1, buffer, ms, light, false, true);
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
	private static void renderThrottleLever(CarburetorBlockEntity be, BlockState state, boolean turned, PoseStack ms,
		MultiBufferSource buffer, int light) {
		float angle = (float) Math.toRadians(EngineTuning.throttleLeverDegrees(be.getThrottle()));

		// Turned, the shaft this lever sits on runs along Z instead of X - so the
		// lever swings about Z, its own model is the quarter-turned copy, and the
		// pivot it is translated onto makes the same turn. The angle does not
		// change: turning the whole arrangement about Y carries the swing with it.
		float pivotX = turned ? 16.0F - PIVOT_Z : PIVOT_X;
		float pivotZ = turned ? PIVOT_X : PIVOT_Z;

		CachedBuffers.partial(turned ? ECPartialModels.THROTTLE_LEVER_Z : ECPartialModels.THROTTLE_LEVER_X, state)
			.translate((pivotX - 8.0F) / 16.0F, (PIVOT_Y - 8.0F) / 16.0F, (pivotZ - 8.0F) / 16.0F)
			.rotateCentered(angle, turned ? Direction.SOUTH : Direction.EAST)
			.light(light)
			.renderInto(ms, buffer.getBuffer(RenderType.solid()));
	}
}
