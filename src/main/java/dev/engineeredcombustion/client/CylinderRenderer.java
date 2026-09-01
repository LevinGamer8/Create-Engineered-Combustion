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
 * Draws the cylinder's installed parts: the Spark Plug in the head, and the
 * Piston Assembly - piston <i>and</i> connecting rod - at the position the crank
 * angle dictates.
 *
 * <p>Both are drawn here rather than baked into the Cylinder's block model
 * because both are optional. A plug baked into the casting is a plug every
 * cylinder has, which is precisely what stopped it being a component.
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
		BlockState state = be.getBlockState();
		VertexConsumer vertices = buffer.getBuffer(RenderType.solid());

		// Which way the engine runs decides both of the models below. The Cylinder's
		// own baked model is turned with it by the blockstate, and a partial model
		// is not - so the plug is picked here, and the rod's swing plane is picked
		// from the same answer further down.
		Axis axis = be.getEngineAxisForRender();

		// Independent of the piston: a head can have a plug in it with nothing in
		// the bore, and a bore can have a piston in it with no plug in the head.
		// Both are real states a player can build, and both have to draw correctly.
		if (be.hasSparkPlug())
			CachedBuffers.partial(axis == Axis.X ? ECPartialModels.SPARK_PLUG_X : ECPartialModels.SPARK_PLUG_Z,
				state)
				.light(light)
				.renderInto(ms, vertices);

		if (!be.hasPistonAssembly())
			return;

		float crankAngle = be.getCrankAngleForRender(partialTicks);
		// Both models put the wrist pin at the same height, so one offset moves
		// the piston and the rod together and they cannot come apart.
		float lift = (CrankMath.wristPinHeight(crankAngle) - CrankMath.WRIST_PIN_MODEL_HEIGHT) / 16.0F;

		CachedBuffers.partial(ECPartialModels.PISTON, state)
			.translate(0, lift, 0)
			.light(light)
			.renderInto(ms, vertices);

		// The rod swings in the plane containing the cylinder and perpendicular
		// to the crankshaft, so which way it leans depends on the engine's axis.
		PartialModel rod = axis == Axis.X ? ECPartialModels.CONNECTING_ROD_X : ECPartialModels.CONNECTING_ROD_Z;

		CachedBuffers.partial(rod, state)
			.translate(0, lift, 0)
			.rotateCentered(CrankMath.rodSwing(crankAngle), Direction.get(AxisDirection.POSITIVE, axis))
			.light(light)
			.renderInto(ms, vertices);

		renderValvetrain(be, state, axis, partialTicks, light, ms, vertices);
		renderCombustionFlash(be, state, partialTicks, ms, buffer);
	}

	/**
	 * This cylinder's two pushrods, rockers and valves, at the lift its own place in
	 * the cycle gives them.
	 *
	 * <p><b>Drawn only when the engine has a Camshaft</b>, because without one there
	 * is nothing to work them - and that absence is the diagnostic: bare tunnels and
	 * a bare rocker shaft over a turning engine is exactly what an engine that will
	 * never fire looks like.
	 *
	 * <p>Every position here is a function of the one cycle angle, through the same
	 * {@code ValveTiming} and {@code CamshaftTiming} the simulation uses. So there is
	 * no animation state, nothing to drift, and a valve is never open on a stroke the
	 * server thinks is a compression.
	 *
	 * <p>The pushrod and the valve move in opposite directions on purpose: the rocker
	 * is a lever, so a rod pushed <i>up</i> presses its valve <i>down</i>. Watching
	 * that happen is the whole reason the pushrod design was chosen over an overhead
	 * camshaft.
	 */
	private static void renderValvetrain(CylinderBlockEntity be, BlockState state, Axis axis, float partialTicks,
		int light, PoseStack ms, VertexConsumer vertices) {
		if (!be.hasCamshaft())
			return;

		float cycleAngle = be.getCycleAngleForRender(partialTicks);
		boolean alongX = axis == Axis.X;
		PartialModel pushrod = alongX ? ECPartialModels.PUSHROD_X : ECPartialModels.PUSHROD_Z;
		PartialModel rocker = alongX ? ECPartialModels.ROCKER_X : ECPartialModels.ROCKER_Z;
		PartialModel valve = alongX ? ECPartialModels.VALVE_X : ECPartialModels.VALVE_Z;
		Direction rotationAxis = Direction.get(AxisDirection.POSITIVE, axis);

		for (int index = 0; index < EngineValvetrain.VALVE_X.length; index++) {
			float lift = EngineValvetrain.liftOf(index, cycleAngle);
			float alongRun = EngineValvetrain.valveOffset(index);

			CachedBuffers.partial(pushrod, state)
				.translate(alongX ? alongRun : 0.0F, lift, alongX ? 0.0F : alongRun)
				.light(light)
				.renderInto(ms, vertices);

			// Translate onto the rocker shaft, then swing about the block centre -
			// the same order the camshaft uses, and for the same reason.
			float offsetZ = EngineValvetrain.rockerOffsetZ();
			CachedBuffers.partial(rocker, state)
				.translate(alongX ? alongRun : offsetZ, EngineValvetrain.rockerOffsetY(),
					alongX ? offsetZ : alongRun)
				.rotateCentered(-EngineValvetrain.rockerSwing(index, cycleAngle), rotationAxis)
				.light(light)
				.renderInto(ms, vertices);

			CachedBuffers.partial(valve, state)
				.translate(alongX ? alongRun : 0.0F, -lift, alongX ? 0.0F : alongRun)
				.light(light)
				.renderInto(ms, vertices);
		}
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
