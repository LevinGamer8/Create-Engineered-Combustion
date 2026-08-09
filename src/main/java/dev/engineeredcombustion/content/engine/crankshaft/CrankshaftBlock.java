package dev.engineeredcombustion.content.engine.crankshaft;

import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;
import com.simibubi.create.foundation.block.IBE;

import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * The crankshaft: logical controller of a single-cylinder engine, and a real
 * Create kinetic block on both ends of its own axis.
 *
 * <p>It owns the engine simulation; the adjacent Flywheel is what <i>generates</i>
 * for Create. See {@code EngineComponents} for the supported layout and every
 * component offset.
 *
 * <h2>Why this is a kinetic block now</h2>
 * The crankshaft model has always shown a main journal leaving the crankcase at
 * <i>both</i> ends, but the block was a plain {@link Block} with no
 * {@code IRotate} on it and a plain block entity underneath. Create's
 * {@code RotationPropagator} only ever walks between {@code KineticBlockEntity}
 * instances whose blocks implement {@code IRotate}, so the crankshaft was simply
 * invisible to the kinetic graph: a Shaft placed against the visible journal on
 * the far side from the Flywheel received nothing, because there was nothing
 * there for it to connect to. Only the Flywheel's own free face transmitted.
 *
 * <p>Extending {@link HorizontalAxisKineticBlock} fixes that with Create's own
 * semantics rather than a reimplementation of them:
 * {@code hasShaftTowards(...)} returns true exactly when
 * {@code face.getAxis() == HORIZONTAL_AXIS}, which is both ends of the crank
 * axis and nowhere else; {@code getRotationAxis(...)} agrees with it; and wrench
 * rotation plus the {@code onRemove} / {@code updateIndirectNeighbourShapes}
 * kinetic bookkeeping come along for free.
 *
 * <p>Crucially the crankshaft is a <i>relay</i>, not a second generator. Its
 * block entity extends {@code KineticBlockEntity}, not
 * {@code GeneratingKineticBlockEntity}, so {@code getGeneratedSpeed()} stays 0
 * and it can never become a source; and it registers neither stress capacity nor
 * stress impact. Crankshaft and Flywheel sit adjacent along a shared axis, which
 * {@code RotationPropagator} resolves as a 1:1 axis connection, so the two are
 * one network turning at one speed, fed by one source: the engine.
 *
 * <p>Placement: the crankshaft's axis follows the horizontal direction the
 * player is facing, so walking towards where the engine should run and placing
 * the block gives the expected axis. That deliberately overrides
 * {@code HorizontalAxisKineticBlock}, which prefers the axis of an adjacent
 * shaft and otherwise takes the player's <i>clockwise</i> direction - correct
 * for a cogwheel, wrong for a machine the player is lining up by eye.
 */
public class CrankshaftBlock extends HorizontalAxisKineticBlock implements IBE<CrankshaftBlockEntity> {

	/**
	 * Whether the ignition indicator lamp on the crankcase is glowing.
	 *
	 * <p>Purely cosmetic - nothing reads it back, the simulation still takes
	 * ignition from the live redstone signal. It exists so that a player without
	 * Engineer's Goggles can see at a glance that the engine is switched on, which
	 * is exactly the kind of thing a real machine tells you by looking at it. Kept
	 * as a block state rather than a renderer so it costs nothing to draw and works
	 * on any rendering backend.
	 *
	 * <p>It is deliberately not part of {@code areStatesKineticallyEquivalent},
	 * which compares block and rotation axis only, so toggling the lamp can never
	 * re-propagate the kinetic network.
	 */
	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	public CrankshaftBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(HORIZONTAL_AXIS, Axis.X)
			.setValue(LIT, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LIT);
		// super adds HORIZONTAL_AXIS - the same BlockStateProperties instance the
		// old hand-rolled declaration used, so the blockstate JSON is unchanged.
		super.createBlockStateDefinition(builder);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(HORIZONTAL_AXIS, context.getHorizontalDirection()
			.getAxis())
			.setValue(LIT, false);
	}

	/**
	 * The crankshaft neither loads the network nor adds capacity to it - it only
	 * relays. Hiding the impact keeps a permanent "0 su" line off its tooltip.
	 */
	@Override
	public boolean hideStressImpact() {
		return true;
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
		// KineticBlock#onRemove routes to IBE.onRemove, which destroys the block
		// entity and lets it detach from the kinetic network. Skipping it would
		// strand this position in whatever network it belonged to.
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	public Class<CrankshaftBlockEntity> getBlockEntityClass() {
		return CrankshaftBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends CrankshaftBlockEntity> getBlockEntityType() {
		return ECBlockEntityTypes.CRANKSHAFT.get();
	}

	// Deliberately no useWithoutItem override. Right-clicking the crankshaft used to
	// print the engine's internal simulation values into chat; that was development
	// output and is gone. The engine's state is read by looking at it - plain
	// hovering information for anyone, full instrumentation through Engineer's
	// Goggles - so bare-handed clicking now correctly does nothing at all.
}
