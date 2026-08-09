package dev.engineeredcombustion.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlockEntity;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlockEntity;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;

/**
 * Spins the flywheel: rim, spokes, hub and the shaft running through the block.
 *
 * <p>The block's baked model is empty on purpose - every part of a flywheel
 * turns, so leaving the shaft in the chunk mesh would have left one visibly
 * stationary piece of the engine's output side.
 *
 * <p>When an engine is attached, the wheel uses the engine's crank angle - the
 * same value the piston uses - so the two are visibly locked together. When the
 * flywheel is driven by something else entirely (a player put a motor on it),
 * it falls back to Create's standard kinetic angle so it still behaves like a
 * normal kinetic block.
 *
 * <p>This is a plain block entity renderer rather than a Flywheel (the rendering
 * library) visual. That is deliberate for a prototype: no visual is registered,
 * so this renderer runs regardless of which rendering backend is active.
 * Converting it to an instanced visual is a later optimisation.
 */
public class EngineFlywheelRenderer implements BlockEntityRenderer<EngineFlywheelBlockEntity> {

	public EngineFlywheelRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(EngineFlywheelBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
		int light, int overlay) {
		Axis axis = be.getBlockState()
			.getValue(HorizontalAxisKineticBlock.HORIZONTAL_AXIS);

		CrankshaftBlockEntity crankshaft = be.getAdjacentCrankshaft();
		float angleRadians = crankshaft != null
			? (float) Math.toRadians(crankshaft.getEngineState()
				.getRenderCrankAngleDegrees(partialTicks))
			: KineticBlockEntityRenderer.getAngleForBe(be, be.getBlockPos(), axis);

		PartialModel wheel = axis == Axis.X ? ECPartialModels.FLYWHEEL_WHEEL_X : ECPartialModels.FLYWHEEL_WHEEL_Z;

		CachedBuffers.partial(wheel, be.getBlockState())
			.rotateCentered(angleRadians, Direction.get(AxisDirection.POSITIVE, axis))
			.light(light)
			.renderInto(ms, buffer.getBuffer(RenderType.solid()));
	}
}
