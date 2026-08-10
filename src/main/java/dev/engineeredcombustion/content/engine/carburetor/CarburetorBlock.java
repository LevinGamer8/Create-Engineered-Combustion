package dev.engineeredcombustion.content.engine.carburetor;

import com.simibubi.create.foundation.block.IBE;

import dev.engineeredcombustion.content.engine.EngineComponents;
import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlockEntity;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
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
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * Meters gasoline into the cylinder, carries the throttle, and takes an Air
 * Filter.
 *
 * <p>Interactions:
 * <ul>
 * <li>right-click with any fluid container - fills or empties the tank through
 * NeoForge's standard helper, so a Gasoline Bucket works without any
 * bucket-specific code;</li>
 * <li>right-click holding an Air Filter - fits it (consumed unless
 * creative);</li>
 * <li>sneak + right-click empty-handed - takes the Air Filter back off and
 * hands it over;</li>
 * <li>right-click and hold on the value box - Create's own throttle UI, handled
 * entirely by {@code ValueSettingsInputHandler} against the
 * {@code ScrollValueBehaviour} on the block entity.</li>
 * </ul>
 * Breaking the carburetor drops an installed filter rather than voiding it.
 */
public class CarburetorBlock extends Block implements IBE<CarburetorBlockEntity> {

	public CarburetorBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (!(level.getBlockEntity(pos) instanceof CarburetorBlockEntity carburetor))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

		if (stack.is(ECItems.AIR_FILTER.get()))
			return installAirFilter(carburetor, state, level, pos, player, stack);

		if (FluidUtil.getFluidHandler(stack)
			.isEmpty())
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

		if (!level.isClientSide
			&& FluidUtil.interactWithFluidHandler(player, hand, carburetor.getFluidHandler()))
			notifyCrankshaft(level, pos);
		return ItemInteractionResult.sidedSuccess(level.isClientSide);
	}

	private static ItemInteractionResult installAirFilter(CarburetorBlockEntity carburetor, BlockState state,
		Level level, BlockPos pos, Player player, ItemStack stack) {
		if (carburetor.hasAirFilter())
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (!level.isClientSide) {
			carburetor.installAirFilter();
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
		if (!(level.getBlockEntity(pos) instanceof CarburetorBlockEntity carburetor))
			return InteractionResult.PASS;
		// Sneaking on purpose: Create's value UI only reacts to a non-sneaking
		// right-click, so this cannot be swallowed by the throttle box.
		if (!player.isShiftKeyDown() || !carburetor.hasAirFilter())
			return InteractionResult.PASS;

		if (!level.isClientSide && carburetor.removeAirFilter()) {
			ItemStack recovered = new ItemStack(ECItems.AIR_FILTER.get());
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
		if (!state.is(newState.getBlock())) {
			// An installed filter is a real item the player paid for; it must not
			// evaporate because the block it was bolted to was mined.
			if (level.getBlockEntity(pos) instanceof CarburetorBlockEntity carburetor && carburetor.hasAirFilter())
				popResource(level, pos, new ItemStack(ECItems.AIR_FILTER.get()));
			notifyCrankshaft(level, pos);
		}
		// IBE.onRemove rather than super: it calls destroy() on the block entity
		// before dropping it, which is what a SmartBlockEntity expects.
		IBE.onRemove(state, level, pos, newState);
	}

	/**
	 * Adding or removing fuel changes nothing about any block state, so the
	 * crankshaft has to be told explicitly. The offset comes from
	 * {@link EngineComponents} so this and the engine's own lookup can never
	 * disagree about which crankshaft owns this carburetor.
	 */
	private static void notifyCrankshaft(Level level, BlockPos carburetorPos) {
		if (level.isClientSide)
			return;
		BlockPos crankshaftPos = EngineComponents.crankshaftPosFromCarburetor(carburetorPos);
		if (level.isLoaded(crankshaftPos)
			&& level.getBlockEntity(crankshaftPos) instanceof CrankshaftBlockEntity crankshaft)
			crankshaft.onSurroundingsChanged();
	}

	@Override
	public Class<CarburetorBlockEntity> getBlockEntityClass() {
		return CarburetorBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends CarburetorBlockEntity> getBlockEntityType() {
		return ECBlockEntityTypes.CARBURETOR.get();
	}
}
