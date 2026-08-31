package dev.engineeredcombustion.content.engine.cylinder;

import org.jetbrains.annotations.Nullable;

import dev.engineeredcombustion.content.engine.EngineComponents;
import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlockEntity;
import dev.engineeredcombustion.foundation.ECLang;
import dev.engineeredcombustion.registry.ECDataComponents;
import dev.engineeredcombustion.registry.ECItems;
import net.minecraft.ChatFormatting;
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

		if (stack.is(ECItems.PISTON_ASSEMBLY.get())) {
			// The assembly's own condition comes in with it. A freshly crafted part
			// carries none and restores this cylinder's compression completely; one
			// that has been in an engine before is exactly as tired as it was when it
			// came out, which is the whole of the no-free-repair rule.
			float wear = ECDataComponents.wearOf(stack, ECDataComponents.PISTON_WEAR);
			return install(cylinder.hasPistonAssembly(), () -> cylinder.installPistonAssembly(wear), state, level,
				pos, player, stack);
		}
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

		// Pulling a piston out of a turning engine is not a thing anyone does. The
		// plug is not covered: it screws into the head from outside and taking one out
		// of a running engine is a perfectly ordinary way to shut a cylinder down.
		if (!takePlug && !engineIsAtRest(level, pos)) {
			if (!level.isClientSide)
				ECLang.translate("gui.stop_engine_before_servicing")
					.style(ChatFormatting.RED)
					.sendStatus(player);
			// Claimed rather than passed, so the click does not fall through to placing
			// a block against the cylinder the player was trying to service.
			return InteractionResult.sidedSuccess(level.isClientSide);
		}

		if (!level.isClientSide) {
			if (takePlug) {
				if (cylinder.removeSparkPlug())
					recover(level, pos, state, player, new ItemStack(ECItems.SPARK_PLUG.get()));
			} else {
				// One call takes the part out AND hands back its condition, so the two
				// can never happen apart - see CylinderBlockEntity#takePistonAssemblyWear.
				float wear = cylinder.takePistonAssemblyWear();
				if (wear >= 0.0F)
					recover(level, pos, state, player, pistonAssembly(wear));
			}
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	/**
	 * A Piston Assembly item carrying the condition the one that came out was in.
	 *
	 * <p>The single place a removed assembly becomes an item, so a part recovered by
	 * hand and one recovered by mining the cylinder are indistinguishable - which
	 * they must be, or one of the two routes would be a free repair.
	 */
	private static ItemStack pistonAssembly(float wear) {
		ItemStack stack = new ItemStack(ECItems.PISTON_ASSEMBLY.get());
		ECDataComponents.setWear(stack, ECDataComponents.PISTON_WEAR, wear);
		return stack;
	}

	/**
	 * Whether the engine this cylinder belongs to has genuinely stopped.
	 *
	 * <p>Asked of the crankshaft below, which resolves its engine's controller, so
	 * the answer is about the whole engine rather than this one section: an inline-4
	 * turning at 190 RPM is not a machine to be reaching into, whichever bore the
	 * player is looking at.
	 *
	 * <p>It asks about <i>rotation</i>, not about the engine phase, so it covers an
	 * engine being motored by another Create source and one coasting down after the
	 * fuel ran out, neither of which is "running" and both of which have a piston
	 * moving in the bore. A cylinder with no crankshaft under it is not part of any
	 * engine, so there is nothing to stop.
	 */
	private static boolean engineIsAtRest(Level level, BlockPos pos) {
		BlockPos crankshaftPos = EngineComponents.crankshaftPosFromCylinder(pos);
		if (!level.isLoaded(crankshaftPos))
			return true;
		if (!(level.getBlockEntity(crankshaftPos) instanceof CrankshaftBlockEntity crankshaft))
			return true;
		return crankshaft.getEngineState()
			.isAtRest();
	}

	/** Whether the click landed on the head casting rather than on the barrel. */
	private static boolean hitOnHead(BlockPos pos, BlockHitResult hitResult) {
		return hitResult.getLocation().y - pos.getY() >= HEAD_BOTTOM;
	}

	/** Hands a removed part back, dropping it only when the inventory is full. */
	private static void recover(Level level, BlockPos pos, BlockState state, Player player, ItemStack recovered) {
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
			// With its condition on it. Breaking the cylinder is the other way a worn
			// assembly leaves an engine, and it must be worth exactly what pulling it
			// out by hand is worth - otherwise mining the block would be the repair.
			if (cylinder.hasPistonAssembly())
				popResource(level, pos, pistonAssembly(cylinder.getPistonWear()));
			if (cylinder.hasSparkPlug())
				popResource(level, pos, new ItemStack(ECItems.SPARK_PLUG.get()));
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}
}
