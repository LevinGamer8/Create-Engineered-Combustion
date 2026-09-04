package dev.engineeredcombustion.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

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
 *
 * <h2>And the camshaft, when the engine has one</h2>
 * Drawn here rather than by the Cylinder because that is where it physically is:
 * in a cradle cast into the crankcase's intake flank, one shaft running the whole
 * length of the engine. It turns at <b>half</b> crank speed, from the same one
 * cycle position everything else reads, so it needs no clock of its own and cannot
 * drift from the pistons - see {@code CamshaftTiming}.
 *
 * <p>Nothing is drawn when no Camshaft is installed, and that absence is the whole
 * diagnostic: an engine turning over with a bare cradle and an empty timing case
 * where its cam should be is an engine that will never fire, and it says so by
 * looking like one.
 *
 * <h2>And the timing drive, on the first section only</h2>
 * An engine has one timing drive and it goes at the free end, so the section whose
 * cylinder index is 0 draws three things nobody else does: the camshaft slice with
 * its gear on the end, the gear the crankshaft turns, and the case the two run in.
 * The small wheel is half the diameter of the big one and turns twice as fast,
 * which is the four-stroke's defining ratio drawn rather than asserted.
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

		Direction rotationAxis = Direction.get(AxisDirection.POSITIVE, axis);
		VertexConsumer vertices = buffer.getBuffer(RenderType.solid());

		CachedBuffers.partial(crank, be.getBlockState())
			.rotateCentered(angle, rotationAxis)
			.light(light)
			.renderInto(ms, vertices);

		if (!be.engineHasCamshaft())
			return;

		// Translate onto the cam's own axis, THEN turn about the block centre. The
		// order is what makes this work: rotateCentered can only pivot about the
		// centre, so the model is authored there, spun, and moved into its cradle
		// afterwards - see EngineValvetrain.
		// The FIRST section carries the timing drive, so it draws the shaft with its
		// gear on the end. Every other section draws the plain slice.
		boolean drive = be.getCylinderIndex() == 0;
		PartialModel camshaft = axis == Axis.X
			? (drive ? ECPartialModels.CAMSHAFT_DRIVE_X : ECPartialModels.CAMSHAFT_RUNNING_X)
			: (drive ? ECPartialModels.CAMSHAFT_DRIVE_Z : ECPartialModels.CAMSHAFT_RUNNING_Z);
		// This section's OWN cam angle, exactly as the crank above uses this
		// section's own crank angle. A shaft drawn at the master angle would put an
		// inline-4's four lobe pairs at one clock position while their four pushrods
		// moved a quarter cycle apart.
		float camAngle = (float) Math.toRadians(be.getEngineState()
			.getLocalRenderCamshaftAngleDegrees(be.getCylinderIndex(), partialTicks));
		float offsetZ = EngineValvetrain.camOffsetZ();
		CachedBuffers.partial(camshaft, be.getBlockState())
			.translate(axis == Axis.X ? 0.0F : offsetZ, EngineValvetrain.camOffsetY(),
				axis == Axis.X ? offsetZ : 0.0F)
			.rotateCentered(camAngle, rotationAxis)
			.light(light)
			.renderInto(ms, vertices);

		if (!drive)
			return;

		// The case, which does not move, and the gear the crankshaft turns, which
		// does. NEGATED, and that is not a fudge: meshing wheels turn in opposite
		// senses, so a drive gear drawn the same way round as the one it drives
		// would be visibly impossible at the one place a player looks - where the
		// teeth meet. Its speed is still exactly the crank's, which is what a gear
		// geared 1:1 to the crankshaft through the case turns at, and the wheel it
		// drives is twice its diameter and therefore turns at half. That is the
		// four-stroke's 2:1, drawn rather than asserted.
		CachedBuffers.partial(axis == Axis.X ? ECPartialModels.TIMING_CASE_X
			: ECPartialModels.TIMING_CASE_Z, be.getBlockState())
			.light(light)
			.renderInto(ms, vertices);

		float driveOffsetZ = EngineValvetrain.timingDriveOffsetZ();
		CachedBuffers.partial(axis == Axis.X ? ECPartialModels.TIMING_GEAR_X
			: ECPartialModels.TIMING_GEAR_Z, be.getBlockState())
			.translate(axis == Axis.X ? 0.0F : driveOffsetZ, EngineValvetrain.timingDriveOffsetY(),
				axis == Axis.X ? driveOffsetZ : 0.0F)
			.rotateCentered(-angle, rotationAxis)
			.light(light)
			.renderInto(ms, vertices);
	}
}
