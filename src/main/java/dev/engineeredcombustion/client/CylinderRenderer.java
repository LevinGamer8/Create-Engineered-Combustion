package dev.engineeredcombustion.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

import dev.engineeredcombustion.content.engine.CrankMath;
import dev.engineeredcombustion.content.engine.cylinder.CylinderBlockEntity;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the Piston Assembly - piston <i>and</i> connecting rod - at the position
 * the crank angle dictates.
 *
 * <p>There is no interpolation state and no animation timer here. Both parts are
 * a pure function of the crank angle the engine simulation owns, evaluated with
 * the frame's partial ticks, which is what makes them provably synchronised with
 * the crankshaft rather than merely looking synchronised.
 *
 * <h2>How the rod stays attached</h2>
 * The rod model is authored hanging straight down with its small end at the
 * centre of the block. Translating by the wrist pin's travel puts that small end
 * exactly on the wrist pin, and because the small end is then sitting on the
 * block centre, {@code rotateCentered} pivots about the wrist pin - no separate
 * translate-rotate-translate dance, and nothing to drift.
 *
 * <p>The big end lands on the crank pin because {@link CrankMath#rodSwing} is
 * derived from the same slider-crank relation as {@link CrankMath#wristPinHeight};
 * see the note there. The rod reaches roughly a block below this one, which is
 * fine: block entity renderers are not clipped to their own block.
 */
public class CylinderRenderer implements BlockEntityRenderer<CylinderBlockEntity> {

	public CylinderRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(CylinderBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
		int overlay) {
		if (!be.hasPistonAssembly())
			return;

		float crankAngle = be.getCrankAngleForRender(partialTicks);
		// Both models put the wrist pin at the same height, so one offset moves
		// the piston and the rod together and they cannot come apart.
		float lift = (CrankMath.wristPinHeight(crankAngle) - CrankMath.WRIST_PIN_MODEL_HEIGHT) / 16.0F;

		BlockState state = be.getBlockState();
		VertexConsumer vertices = buffer.getBuffer(RenderType.solid());

		CachedBuffers.partial(ECPartialModels.PISTON, state)
			.translate(0, lift, 0)
			.light(light)
			.renderInto(ms, vertices);

		// The rod swings in the plane containing the cylinder and perpendicular
		// to the crankshaft, so which way it leans depends on the engine's axis.
		Axis axis = be.getEngineAxisForRender();
		PartialModel rod = axis == Axis.X ? ECPartialModels.CONNECTING_ROD_X : ECPartialModels.CONNECTING_ROD_Z;

		CachedBuffers.partial(rod, state)
			.translate(0, lift, 0)
			.rotateCentered(CrankMath.rodSwing(crankAngle), Direction.get(AxisDirection.POSITIVE, axis))
			.light(light)
			.renderInto(ms, vertices);

		renderCombustionFlash(be, state, partialTicks, ms, buffer);
	}

	/**
	 * The burn, drawn inside the chamber for the few ticks after a real combustion
	 * event.
	 *
	 * <p>Because the cylinder is a cutaway, the top of the bore is genuinely
	 * visible from outside, so lighting it is the honest way to show that a charge
	 * fired - and it is the mechanical feedback the exposed design is <i>for</i>.
	 *
	 * <p>It starts when the server's combustion counter moves and never on a
	 * client-side guess, so a flash means a charge was paid for and burned. An
	 * engine sparking on an empty tank stays dark.
	 *
	 * <p>Deliberately not a particle. The flash lasts exactly
	 * {@code COMBUSTION_FLASH_TICKS} because the simulation says so, it cannot
	 * outlive the event, and its cost is one small model per frame no matter how
	 * fast the engine is turning - where a particle per firing would scale with
	 * engine speed and outlive its own event at high RPM.
	 *
	 * <p>Drawn <i>after</i> the piston, into the translucent buffer, and that
	 * ordering is load-bearing rather than incidental: the flash model is taller
	 * than the clearance volume, and the depth buffer is what clips it to the real
	 * chamber. At top dead centre the crown hides all but the top half unit of it;
	 * as the charge drives the piston down the bore, the same flash is uncovered.
	 *
	 * <p>Full brightness and no diffuse shading, so it reads as light coming from
	 * inside the cylinder rather than as a lump of orange appearing in it.
	 */
	private static void renderCombustionFlash(CylinderBlockEntity be, BlockState state, float partialTicks,
		PoseStack ms, MultiBufferSource buffer) {
		float intensity = be.getCombustionFlashIntensity(partialTicks);
		if (intensity <= 0.0F)
			return;

		int alpha = Math.round(255.0F * intensity);
		if (alpha <= 0)
			return;

		CachedBuffers.partial(ECPartialModels.COMBUSTION_FLASH, state)
			.color(255, 255, 255, alpha)
			.disableDiffuse()
			.light(LightTexture.FULL_BRIGHT)
			.renderInto(ms, buffer.getBuffer(RenderType.translucent()));
	}
}
