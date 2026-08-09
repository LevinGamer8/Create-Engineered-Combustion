package dev.engineeredcombustion.content.engine.sump;

import org.jetbrains.annotations.Nullable;

import dev.engineeredcombustion.content.engine.EngineComponents;
import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
public class OilSumpBlock extends Block implements EntityBlock {

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
}
