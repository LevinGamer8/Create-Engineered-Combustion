package dev.engineeredcombustion.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.engineeredcombustion.content.engine.CrankMath;
import dev.engineeredcombustion.content.engine.cylinder.CylinderBlockEntity;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * Draws the installed piston at the height the crank angle dictates.
 *
 * <p>There is no interpolation state and no animation timer here. The position
 * is a pure function of the crank angle the engine simulation owns, evaluated
 * with the frame's partial ticks - which is what makes the piston provably
 * synchronised with engine rotation rather than merely looking synchronised.
 */
public class CylinderRenderer implements BlockEntityRenderer<CylinderBlockEntity> {

	/**
	 * Piston travel inside the cylinder model, in 1/16 block units. The cylinder
	 * model is an open cage from y=2 to y=14; the piston model is 4 units tall, so
	 * it can travel between y=2 (bottom dead centre) and y=10 (top dead centre).
	 */
	private static final float BOTTOM_DEAD_CENTRE = 2.0F / 16.0F;
	private static final float STROKE = 8.0F / 16.0F;

	public CylinderRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(CylinderBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
		int overlay) {
		if (!be.hasPistonAssembly())
			return;

		float crankAngle = be.getCrankAngleForRender(partialTicks);
		float offset = BOTTOM_DEAD_CENTRE + STROKE * CrankMath.pistonPosition(crankAngle);

		CachedBuffers.partial(ECPartialModels.PISTON_HEAD, be.getBlockState())
			.translate(0, offset, 0)
			.light(light)
			.renderInto(ms, buffer.getBuffer(RenderType.solid()));
	}
}
