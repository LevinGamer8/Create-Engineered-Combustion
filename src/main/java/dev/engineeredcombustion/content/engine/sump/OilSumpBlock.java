package dev.engineeredcombustion.content.engine.sump;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.equipment.wrench.IWrenchable;

import dev.engineeredcombustion.content.engine.EngineComponents;
import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlock;
import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlockEntity;
import dev.engineeredcombustion.foundation.EngineAxis;
import dev.engineeredcombustion.foundation.EngineCasting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * Holds the engine's oil. Sits directly below the crankshaft.
 *
 * <p>Right-clicking with any fluid container fills or empties it through
 * NeoForge's standard helper, so an Engine Oil Bucket works without any
 * bucket-specific code - the same path the carburetor uses.
 */
public class OilSumpBlock extends Block implements EntityBlock, EngineCasting, IWrenchable {

	/**
	 * Which way the engine over this pan runs.
	 *
	 * <p>One shared Oil Sump serves a whole inline engine, and the part of the
	 * model that says so is a pair of oil feed risers that stand up out of the
	 * flange to meet the gallery running along the crankcase's flanks - which runs
	 * along the crank axis. Turned the wrong way they would come up under the
	 * blank sides of the crankcase instead, and the one thing the pan has to
	 * communicate - that its oil reaches every bearing in the engine, not just the
	 * bay it hangs under - would be lost.
	 *
	 * <p>Cosmetic. The tank, its capacity and every drop of oil in it are the same
	 * whichever way this points.
	 */
	public static final EnumProperty<EngineAxis> AXIS = EngineAxis.PROPERTY;

	/**
	 * Follows the pan's actual silhouette: a full-width flange bolted up against
	 * the crankcase, stepping down twice to the deep sump. A full cube would claim
	 * the empty air around the taper, which is visible from below where the sump
	 * hangs under the engine. Three boxes is enough - the drain plug is inside the
	 * bottom one rather than being its own box.
	 */
	private static final VoxelShape SHAPE = Shapes.or(
		Block.box(0.0D, 13.0D, 0.0D, 16.0D, 16.0D, 16.0D),
		Block.box(1.5D, 7.0D, 1.5D, 14.5D, 13.0D, 14.5D),
		Block.box(3.5D, 0.5D, 3.5D, 12.5D, 7.0D, 12.5D));

	public OilSumpBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(AXIS, EngineAxis.NONE));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(AXIS);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return castingState(context.getLevel(), context.getClickedPos(), defaultBlockState());
	}

	@Override
	protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
		LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
		return castingState(level, pos, state);
	}

	@Override
	public BlockState castingState(LevelReader level, BlockPos pos, BlockState state) {
		BlockState crankshaft = level.getBlockState(EngineComponents.crankshaftPosFromOilSump(pos));
		EngineAxis alignment = crankshaft.getBlock() instanceof CrankshaftBlock
			? EngineAxis.of(crankshaft.getValue(CrankshaftBlock.HORIZONTAL_AXIS))
			: EngineAxis.NONE;
		return state.getValue(AXIS) == alignment ? state : state.setValue(AXIS, alignment);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new OilSumpBlockEntity(pos, state);
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (!(level.getBlockEntity(pos) instanceof OilSumpBlockEntity sump))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (FluidUtil.getFluidHandler(stack)
			.isEmpty())
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

		if (!level.isClientSide && FluidUtil.interactWithFluidHandler(player, hand, sump.getFluidHandler()))
			notifyCrankshaft(level, pos);
		return ItemInteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock()))
			// THE OIL IN THE PAN IS NOT KEPT. Carrying it on the item would need a
			// fluid data component, and the two routes to an emptied sump - mined, or
			// drained into a pipe - would then have to agree about the millibucket.
			// Losing it is the unambiguous answer: nothing duplicated, nothing created,
			// and a player who cares drains it first. Same rule as the Carburetor.
			notifyCrankshaft(level, pos);
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	/**
	 * Adding or removing oil changes nothing about any block state, so the
	 * crankshaft has to be told explicitly. The offset comes from
	 * {@link EngineComponents} so this and the engine's own lookup can never
	 * disagree about which crankshaft owns this sump.
	 */
	private static void notifyCrankshaft(Level level, BlockPos sumpPos) {
		if (level.isClientSide)
			return;
		BlockPos crankshaftPos = EngineComponents.crankshaftPosFromOilSump(sumpPos);
		if (level.isLoaded(crankshaftPos)
			&& level.getBlockEntity(crankshaftPos) instanceof CrankshaftBlockEntity crankshaft)
			crankshaft.onSurroundingsChanged();
	}

	/**
	 * Dismantled with a Create Wrench, exactly as Create's own machines are.
	 *
	 * <p><b>Sneak-wrench only.</b> {@code IWrenchable}'s default non-sneaking
	 * behaviour rotates the block, and this one has no rotation of its own to
	 * offer: which way it faces is read from the Crankshaft underneath it and
	 * re-derived on every neighbour change, so a wrench that turned it would be
	 * undone before the player let go. Passing leaves the click to whatever else
	 * wants it.
	 *
	 * <p>The sneaking default is inherited unchanged, and that is the point: it
	 * calls {@code Block#getDrops} - the loot table, with this block entity in
	 * hand, so every data component the table copies comes with it - and then
	 * destroys the block WITHOUT dropping it again. Destroying it runs
	 * {@link #onRemove}, which is where installed parts come out. So a wrench and
	 * a pickaxe reach the same one path and return the same one of everything;
	 * there is deliberately no wrench-specific drop code anywhere in this mod,
	 * because a wrench that dropped a part itself would hand the player two.
	 */
	@Override
	public InteractionResult onWrenched(BlockState state, UseOnContext context) {
		return InteractionResult.PASS;
	}

}
