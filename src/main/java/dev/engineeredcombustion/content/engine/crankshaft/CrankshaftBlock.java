package dev.engineeredcombustion.content.engine.crankshaft;

import org.jetbrains.annotations.Nullable;

import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The crankshaft: logical controller of a single-cylinder engine.
 *
 * <p>This block is deliberately <i>not</i> a Create kinetic block. It owns the
 * engine simulation; the adjacent Flywheel is what talks to Create. See
 * {@code EngineStructure} for the supported layout.
 *
 * <p>Placement: the crankshaft's axis follows the horizontal direction the
 * player is facing, so walking towards where the engine should run and placing
 * the block gives the expected axis.
 */
public class CrankshaftBlock extends Block implements EntityBlock {

	public static final EnumProperty<Axis> HORIZONTAL_AXIS = BlockStateProperties.HORIZONTAL_AXIS;

	public CrankshaftBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(HORIZONTAL_AXIS, Axis.X));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(HORIZONTAL_AXIS);
		super.createBlockStateDefinition(builder);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(HORIZONTAL_AXIS, context.getHorizontalDirection()
			.getAxis());
	}

	@Override
	public BlockState rotate(BlockState state, Rotation rotation) {
		Axis axis = state.getValue(HORIZONTAL_AXIS);
		return state.setValue(HORIZONTAL_AXIS, rotation.rotate(Direction.get(AxisDirection.POSITIVE, axis))
			.getAxis());
	}

	@Override
	public BlockState mirror(BlockState state, Mirror mirror) {
		return state;
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new CrankshaftBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
		BlockEntityType<T> type) {
		// Ticks on both sides: the server runs the simulation, the client only
		// advances its copy of the crank angle so rendering stays smooth without
		// per-tick network traffic.
		return type != ECBlockEntityTypes.CRANKSHAFT.get() ? null
			: (tickLevel, pos, tickState, blockEntity) -> ((CrankshaftBlockEntity) blockEntity).tick();
	}

	@Override
	public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
		boolean movedByPiston) {
		super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
		if (level.isClientSide)
			return;
		if (level.getBlockEntity(pos) instanceof CrankshaftBlockEntity crankshaft)
			crankshaft.onSurroundingsChanged();
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		// Clear the engine and stop the flywheel *before* the block entity goes away,
		// otherwise the flywheel would keep asking a crankshaft that no longer exists
		// until Create's periodic kinetic validation notices.
		if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof CrankshaftBlockEntity crankshaft)
			crankshaft.onEngineRemoved();
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
		BlockHitResult hitResult) {
		if (!(level.getBlockEntity(pos) instanceof CrankshaftBlockEntity crankshaft))
			return InteractionResult.PASS;
		if (!level.isClientSide)
			crankshaft.sendDebugReport(player);
		return InteractionResult.sidedSuccess(level.isClientSide);
	}
}
