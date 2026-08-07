package dev.engineeredcombustion.content.engine.flywheel;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlockEntity;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Create kinetic adapter.
 *
 * <p>This is the <b>only</b> class in the mod that knows about Create's kinetic
 * network. It answers one question for Create - "what speed do you generate?" -
 * and gets the answer from the engine simulation living in the adjacent
 * {@link CrankshaftBlockEntity}.
 *
 * <h2>Why the generator lives here and not in the crankshaft</h2>
 * Create decides everything about a kinetic source from the block itself:
 * {@code IRotate#getRotationAxis} and {@code IRotate#hasShaftTowards} determine
 * where rotation may leave the block, and {@code RotationPropagator} only walks
 * between {@code KineticBlockEntity} instances. Putting
 * {@code GeneratingKineticBlockEntity} on the crankshaft would therefore have
 * forced the crankshaft to expose shafts on its own faces, which contradicts the
 * intended layout (crankshaft - flywheel - shaft) and would have welded engine
 * simulation to Create internals. Keeping it on the flywheel means the
 * crankshaft stays a plain block entity with zero Create kinetic coupling, and
 * milestone 2 can replace the whole simulation without touching this file.
 *
 * <p>The reverse also holds: everything Create needs is reachable through
 * {@link CrankshaftBlockEntity#getOutputRpmFor(BlockPos)}, so if Create's
 * kinetic API changes, only this class has to follow.
 */
public class EngineFlywheelBlockEntity extends GeneratingKineticBlockEntity {

	/**
	 * Stress capacity per RPM, matching Create's convention. At the milestone-1
	 * debug speed of 32 RPM this provides 1024 SU - comfortably enough for a small
	 * mechanism, and in the same league as Create's own early generators.
	 */
	public static final double STRESS_CAPACITY_PER_RPM = 32.0D;

	public EngineFlywheelBlockEntity(BlockPos pos, BlockState state) {
		super(ECBlockEntityTypes.FLYWHEEL.get(), pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		super.addBehaviours(behaviours);
	}

	@Override
	public void initialize() {
		super.initialize();
		// Mirrors Create's own generators: re-assert generated rotation after load,
		// unless something faster is already driving this network.
		if (!hasSource() || getGeneratedSpeed() > getTheoreticalSpeed())
			updateGeneratedRotation();
	}

	@Override
	public float getGeneratedSpeed() {
		CrankshaftBlockEntity crankshaft = getAdjacentCrankshaft();
		if (crankshaft == null)
			return 0.0F;
		// Positive along the block's own axis. The engine always turns the same way
		// for now; direction becomes meaningful once the simulation has real
		// angular velocity in milestone 2.
		return crankshaft.getOutputRpmFor(worldPosition);
	}

	/** Called by the crankshaft when the engine's rotational output changed. */
	public void onEngineOutputChanged() {
		updateGeneratedRotation();
	}

	/**
	 * Finds a crankshaft sitting directly next to this flywheel along this
	 * flywheel's own rotation axis, with a matching axis of its own.
	 *
	 * <p>Whether that crankshaft actually drives <i>this</i> flywheel is decided by
	 * the crankshaft in {@link CrankshaftBlockEntity#getOutputRpmFor(BlockPos)},
	 * which returns 0 for anything that is not its structural flywheel.
	 */
	@Nullable
	public CrankshaftBlockEntity getAdjacentCrankshaft() {
		if (level == null)
			return null;
		Axis axis = getBlockState().getValue(HorizontalAxisKineticBlock.HORIZONTAL_AXIS);
		for (AxisDirection axisDirection : AxisDirection.values()) {
			BlockPos neighbour = worldPosition.relative(Direction.get(axisDirection, axis));
			if (!level.isLoaded(neighbour))
				continue;
			if (level.getBlockEntity(neighbour) instanceof CrankshaftBlockEntity crankshaft
				&& crankshaft.getAxis() == axis)
				return crankshaft;
		}
		return null;
	}
}
