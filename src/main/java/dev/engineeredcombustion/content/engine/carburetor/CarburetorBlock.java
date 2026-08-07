package dev.engineeredcombustion.content.engine.carburetor;

import org.jetbrains.annotations.Nullable;

import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * Meters gasoline into the cylinder. For now it is simply a small tank in the
 * right place - no air/fuel ratio, no jets.
 *
 * <p>Right-clicking with any fluid container fills or empties it through
 * NeoForge's standard helper, so a Gasoline Bucket works without any
 * bucket-specific code.
 */
public class CarburetorBlock extends Block implements EntityBlock {

	public CarburetorBlock(Properties properties) {
		super(properties);
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new CarburetorBlockEntity(pos, state);
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (!(level.getBlockEntity(pos) instanceof CarburetorBlockEntity carburetor))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (FluidUtil.getFluidHandler(stack)
			.isEmpty())
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

		if (!level.isClientSide
			&& FluidUtil.interactWithFluidHandler(player, hand, carburetor.getFluidHandler()))
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
	 * The carburetor sits two blocks above the crankshaft (directly on top of the
	 * cylinder), and adding or removing fuel changes nothing about any block
	 * state, so the crankshaft has to be told explicitly.
	 */
	private static void notifyCrankshaft(Level level, BlockPos carburetorPos) {
		if (level.isClientSide)
			return;
		BlockPos crankshaftPos = carburetorPos.below()
			.below();
		if (level.isLoaded(crankshaftPos)
			&& level.getBlockEntity(crankshaftPos) instanceof CrankshaftBlockEntity crankshaft)
			crankshaft.onSurroundingsChanged();
	}
}
