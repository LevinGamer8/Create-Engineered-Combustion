package dev.engineeredcombustion.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

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
 * Turns the crankshaft itself inside the crankcase.
 *
 * <p>The block's baked model is only the <i>crankcase</i>: pan, cut-away side
 * walls, main bearing housings and the machined top deck. Everything that
 * rotates - main journals, both crank webs, the counterweights and the offset
 * crank pin - lives in a partial model here, so the player can watch the throw
 * go round through the crankcase windows.
 *
 * <p>The rotation is applied about the block centre, which is where the main
 * journal axis is modelled, where Create attaches a shaft, and what the adjacent
 * flywheel rotates about. Using the engine's own crank angle - the same value
 * the piston and connecting rod use - is what keeps the crank pin underneath the
 * rod's big end at every frame, in both directions of rotation.
 *
 * <h2>One shaft, several throws</h2>
 * On a multi-cylinder engine each section draws the <i>same</i> crank assembly at
 * its <i>own</i> phase: the engine's one master angle plus
 * {@code index * 360 / cylinderCount}. So an inline-4 shows four throws 90
 * degrees apart on one shaft, each with its own piston and rod above it, and the
 * player can watch the firing order walk along the engine.
 *
 * <p>The phase comes from the block entity's synchronised cylinder index, which
 * is the very number the simulation fires that cylinder at - so the throw the
 * player sees pushed is the throw the combustion pushed.
 */
public class CrankshaftRenderer implements BlockEntityRenderer<CrankshaftBlockEntity> {

	public CrankshaftRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(CrankshaftBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
		int overlay) {
		Axis axis = be.getAxis();
		float angle = (float) Math.toRadians(be.getEngineState()
			.getLocalRenderCrankAngleDegrees(be.getCylinderIndex(), partialTicks));
		PartialModel crank = axis == Axis.X ? ECPartialModels.CRANK_ASSEMBLY_X : ECPartialModels.CRANK_ASSEMBLY_Z;

		CachedBuffers.partial(crank, be.getBlockState())
			.rotateCentered(angle, Direction.get(AxisDirection.POSITIVE, axis))
			.light(light)
			.renderInto(ms, buffer.getBuffer(RenderType.solid()));
	}
}
