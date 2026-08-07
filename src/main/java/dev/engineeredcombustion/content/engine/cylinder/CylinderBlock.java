package dev.engineeredcombustion.content.engine.cylinder;

import org.jetbrains.annotations.Nullable;

import dev.engineeredcombustion.registry.ECItems;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A cylinder that a Piston Assembly item can be installed into.
 *
 * <p>Interactions:
 * <ul>
 * <li>right-click holding a Piston Assembly - installs it (consumed unless
 * creative)</li>
 * <li>right-click with an empty hand while sneaking - pulls the assembly back
 * out and hands it to the player</li>
 * </ul>
 * Breaking the cylinder drops any installed assembly rather than voiding it.
 */
public class CylinderBlock extends Block implements EntityBlock {

	public CylinderBlock(Properties properties) {
		super(properties);
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new CylinderBlockEntity(pos, state);
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (!stack.is(ECItems.PISTON_ASSEMBLY.get()))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (!(level.getBlockEntity(pos) instanceof CylinderBlockEntity cylinder))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (cylinder.hasPistonAssembly())
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

		if (!level.isClientSide) {
			cylinder.installPistonAssembly();
			if (!player.isCreative())
				stack.shrink(1);
			level.playSound(null, pos, state.getSoundType()
				.getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.1F);
		}
		return ItemInteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
		BlockHitResult hitResult) {
		if (!(level.getBlockEntity(pos) instanceof CylinderBlockEntity cylinder))
			return InteractionResult.PASS;
		if (!player.isShiftKeyDown() || !cylinder.hasPistonAssembly())
			return InteractionResult.PASS;

		if (!level.isClientSide && cylinder.removePistonAssembly()) {
			ItemStack recovered = new ItemStack(ECItems.PISTON_ASSEMBLY.get());
			if (!player.getInventory()
				.add(recovered))
				popResource(level, pos, recovered);
			level.playSound(null, pos, state.getSoundType()
				.getBreakSound(), SoundSource.BLOCKS, 0.8F, 0.9F);
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof CylinderBlockEntity cylinder
			&& cylinder.hasPistonAssembly())
			popResource(level, pos, new ItemStack(ECItems.PISTON_ASSEMBLY.get()));
		super.onRemove(state, level, pos, newState, movedByPiston);
	}
}
