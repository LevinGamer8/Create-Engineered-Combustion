package dev.engineeredcombustion.content.engine.flywheel;

import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;
import com.simibubi.create.foundation.block.IBE;

import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlock;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The engine's output side and its only connection to Create.
 *
 * <p>Extending {@link HorizontalAxisKineticBlock} gives us Create's shaft
 * semantics for free: {@code hasShaftTowards} on both ends of the block's
 * horizontal axis, {@code getRotationAxis}, wrench rotation, and the correct
 * {@code onRemove}/{@code updateIndirectNeighbourShapes} kinetic bookkeeping.
 * Re-implementing any of that would be duplicating Create.
 */
public class EngineFlywheelBlock extends HorizontalAxisKineticBlock implements IBE<EngineFlywheelBlockEntity> {

	public EngineFlywheelBlock(Properties properties) {
		super(properties);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		// Adopt the axis of an adjacent crankshaft if there is one - otherwise the
		// player would routinely place a flywheel that is 90 degrees off and get a
		// silently invalid engine.
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockState neighbour = context.getLevel()
				.getBlockState(context.getClickedPos()
					.relative(direction));
			if (!(neighbour.getBlock() instanceof CrankshaftBlock))
				continue;
			Axis crankAxis = neighbour.getValue(CrankshaftBlock.HORIZONTAL_AXIS);
			if (crankAxis == direction.getAxis())
				return defaultBlockState().setValue(HORIZONTAL_AXIS, crankAxis);
		}
		return super.getStateForPlacement(context);
	}

	/** The flywheel generates; it never loads the network. */
	@Override
	public boolean hideStressImpact() {
		return true;
	}

	@Override
	public Class<EngineFlywheelBlockEntity> getBlockEntityClass() {
		return EngineFlywheelBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends EngineFlywheelBlockEntity> getBlockEntityType() {
		return ECBlockEntityTypes.FLYWHEEL.get();
	}
}
