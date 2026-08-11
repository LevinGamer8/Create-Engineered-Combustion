package dev.engineeredcombustion.content.engine.cylinder;

import org.jetbrains.annotations.Nullable;

import dev.engineeredcombustion.registry.ECItems;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A cylinder that a Piston Assembly and a Spark Plug can be installed into.
 *
 * <p>Interactions:
 * <ul>
 * <li>right-click holding a Piston Assembly or a Spark Plug - installs it
 * (consumed unless creative). Anywhere on the block; the part knows where it
 * goes.</li>
 * <li>sneak + right-click with an empty hand - pulls a part back out and hands
 * it to the player.</li>
 * </ul>
 * Breaking the cylinder drops every installed part rather than voiding it.
 *
 * <h2>Which part comes out</h2>
 * With only one part fitted there is nothing to decide and the gesture removes
 * it, wherever on the block it landed. With both fitted the <i>height of the
 * click</i> chooses, and it chooses the way the machine is actually built: the
 * head is the top two units of the block and that is where the plug screws in,
 * so a click there takes the plug and a click on the barrel below takes the
 * piston.
 *
 * <p>Being forgiving in the single-part case is the point. A player who has
 * fitted only a piston should never have to aim, and one who has fitted both is
 * already looking at two visibly different parts of the casting.
 */
public class CylinderBlock extends Block implements EntityBlock {

	/**
	 * Where the head casting starts, as a fraction of the block. Matches the head
	 * slab in {@code cylinder_elements()} - which begins at model y 14 of 16 - so
	 * the interaction area is the part of the block the plug is visibly screwed
	 * into rather than an arbitrary threshold.
	 */
	private static final double HEAD_BOTTOM = 14.0D / 16.0D;

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
		if (!(level.getBlockEntity(pos) instanceof CylinderBlockEntity cylinder))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

		if (stack.is(ECItems.PISTON_ASSEMBLY.get()))
			return install(cylinder.hasPistonAssembly(), cylinder::installPistonAssembly, state, level, pos, player,
				stack);
		if (stack.is(ECItems.SPARK_PLUG.get()))
			return install(cylinder.hasSparkPlug(), cylinder::installSparkPlug, state, level, pos, player, stack);
		return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
	}

	/**
	 * Fits one part, on the server, and charges the player for it.
	 *
	 * <p>Shared by both parts so the two can never drift apart in the details that
	 * matter - the creative exemption, the sound, and passing rather than
	 * claiming the click when the socket is already occupied. Passing is what lets
	 * a player holding a spare plug still place a block against a finished
	 * cylinder.
	 */
	private static ItemInteractionResult install(boolean alreadyFitted, Runnable fit, BlockState state, Level level,
		BlockPos pos, Player player, ItemStack stack) {
		if (alreadyFitted)
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

		if (!level.isClientSide) {
			fit.run();
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
		if (!player.isShiftKeyDown())
			return InteractionResult.PASS;

		boolean piston = cylinder.hasPistonAssembly();
		boolean plug = cylinder.hasSparkPlug();
		if (!piston && !plug)
			return InteractionResult.PASS;

		// Only an ambiguous click needs the head/barrel rule; with one part fitted
		// the answer is that part, wherever the player hit the block.
		boolean takePlug = plug && (!piston || hitOnHead(pos, hitResult));

		if (!level.isClientSide) {
			boolean removed = takePlug ? cylinder.removeSparkPlug() : cylinder.removePistonAssembly();
			if (removed)
				recover(level, pos, state, player, takePlug ? ECItems.SPARK_PLUG.get()
					: ECItems.PISTON_ASSEMBLY.get());
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	/** Whether the click landed on the head casting rather than on the barrel. */
	private static boolean hitOnHead(BlockPos pos, BlockHitResult hitResult) {
		return hitResult.getLocation().y - pos.getY() >= HEAD_BOTTOM;
	}

	/** Hands a removed part back, dropping it only when the inventory is full. */
	private static void recover(Level level, BlockPos pos, BlockState state, Player player, Item item) {
		ItemStack recovered = new ItemStack(item);
		if (!player.getInventory()
			.add(recovered))
			popResource(level, pos, recovered);
		level.playSound(null, pos, state.getSoundType()
			.getBreakSound(), SoundSource.BLOCKS, 0.8F, 0.9F);
	}

	/**
	 * Every installed part is a real item the player paid for, so mining the
	 * cylinder must not evaporate any of them. The cylinder itself comes from its
	 * loot table; these do not, because the loot table cannot see block entity
	 * state.
	 */
	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof CylinderBlockEntity cylinder) {
			if (cylinder.hasPistonAssembly())
				popResource(level, pos, new ItemStack(ECItems.PISTON_ASSEMBLY.get()));
			if (cylinder.hasSparkPlug())
				popResource(level, pos, new ItemStack(ECItems.SPARK_PLUG.get()));
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}
}
