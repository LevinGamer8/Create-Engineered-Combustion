package dev.engineeredcombustion.content.engine.crankshaft;

import java.util.List;

import dev.engineeredcombustion.foundation.EngineConditionText;
import dev.engineeredcombustion.registry.ECDataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/**
 * The Crankshaft as an item, carrying the condition of the bearings inside it.
 *
 * <p>Same job as {@code PistonAssemblyItem}'s tooltip and the same reason: a
 * mined crankcase and a freshly crafted one are the same item, and the player
 * needs to be able to tell which of the two is in their hand before they build
 * an engine out of it.
 *
 * <p>The wear itself is not stored here - it rides on the stack as a Data
 * Component, put there by the block's loot table and read back by
 * {@code CrankshaftBlockEntity}. This only reads it out to print.
 */
public class CrankshaftBlockItem extends BlockItem {

	public CrankshaftBlockItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		super.appendHoverText(stack, context, tooltip, flag);
		EngineConditionText.appendCondition(tooltip, stack, ECDataComponents.CRANKSHAFT_BEARING_WEAR,
			"gui.bearing_condition");
	}
}
