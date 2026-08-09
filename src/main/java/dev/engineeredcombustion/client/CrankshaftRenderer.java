package dev.engineeredcombustion.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlock;
import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlockEntity;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;

/**
 * Turns the crank throw inside the open crankcase.
 *
 * <p>The angle is {@code getRenderCrankAngleDegrees} - the same authoritative
 * value the piston and the flywheel disc already use, interpolated with the
 * frame's partial ticks. There is no animation timer here and no independent
 * phase: all three moving parts are driven from one number, so they cannot drift
 * apart. Watching the counterweights swing down as the piston rises is therefore
 * showing real simulation state, not a decorative loop.
 *
 * <p>The crank pin is modelled below the block centre, which is bottom dead
 * centre, matching {@code CrankMath.pistonPosition(0) == 0}. Rotating about the
 * same axis with the same value as {@code EngineFlywheelRenderer} keeps the
 * crank and the flywheel visually locked whichever way the engine turns.
 */
public class CrankshaftRenderer implements BlockEntityRenderer<CrankshaftBlockEntity> {

	public CrankshaftRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(CrankshaftBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
		int overlay) {
		Axis axis = be.getBlockState()
			.getValue(CrankshaftBlock.HORIZONTAL_AXIS);

		float angleRadians = (float) Math.toRadians(be.getEngineState()
			.getRenderCrankAngleDegrees(partialTicks));

		// Two baked variants rather than one plus a 90 degree buffer rotation, for
		// the same reason the flywheel does it: composing rotations on a
		// SuperByteBuffer is easy to get subtly wrong, and a second small model file
		// is cheaper than that risk.
		PartialModel throwModel = axis == Axis.X ? ECPartialModels.CRANK_THROW_X : ECPartialModels.CRANK_THROW_Z;

		CachedBuffers.partial(throwModel, be.getBlockState())
			.rotateCentered(angleRadians, Direction.get(AxisDirection.POSITIVE, axis))
			.light(light)
			.renderInto(ms, buffer.getBuffer(RenderType.solid()));
	}
}
