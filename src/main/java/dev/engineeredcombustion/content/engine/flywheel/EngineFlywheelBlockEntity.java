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
 * The crankshaft is a kinetic block too - it has to be, or the journal its model
 * shows on the far side from this flywheel would have nothing to connect to. But
 * it is a plain {@code KineticBlockEntity}: a relay that never generates.
 *
 * <p>The <i>generator</i> stays here, and there is exactly one of it. Create
 * decides a source's capacity and speed from the block that generates, so a
 * second {@code GeneratingKineticBlockEntity} on the crankshaft would mean two
 * sources, two stress capacities and two speeds to reconcile for one engine.
 * Instead the crankshaft and this flywheel sit adjacent along a shared axis,
 * which {@code RotationPropagator} resolves as a 1:1 axis connection, so both
 * ends of the engine are one network at one speed fed by this one source.
 *
 * <p>Everything Create needs is reachable through
 * {@link CrankshaftBlockEntity#getGeneratedRpmFor(BlockPos)}, so if Create's
 * kinetic API changes, this class and {@code CrankshaftBlock}'s connectivity are
 * the only places that have to follow.
 */
public class EngineFlywheelBlockEntity extends GeneratingKineticBlockEntity {

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

	/**
	 * What Create asks for. Zero whenever the engine is not making its own power,
	 * which is exactly what turns this block into an ordinary <i>passive</i> kinetic
	 * component that a hand crank or any other Create source can motor - see
	 * {@code GeneratingKineticBlockEntity#applyNewSpeed}, which keeps a generator
	 * attached to its external source while it generates 0.
	 *
	 * <p>The value is latched by the simulation rather than computed live: Create
	 * calls this during propagation and during its periodic kinetic validation, and
	 * a value that drifted every tick would make it re-propagate the network
	 * constantly.
	 */
	@Override
	public float getGeneratedSpeed() {
		CrankshaftBlockEntity crankshaft = getAdjacentCrankshaft();
		if (crankshaft == null)
			return 0.0F;
		// Always positive along the block's own axis: the engine only ever fires
		// while turning forwards, so the generated speed can never disagree in sign
		// with the rotation already present - which matters, because
		// RotationPropagator destroys blocks on opposing-sign sources.
		return crankshaft.getGeneratedRpmFor(worldPosition);
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
	 * the crankshaft in {@link CrankshaftBlockEntity#getGeneratedRpmFor(BlockPos)},
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
