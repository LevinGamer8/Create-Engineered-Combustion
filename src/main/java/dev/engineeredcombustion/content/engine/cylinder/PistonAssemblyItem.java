package dev.engineeredcombustion.content.engine.cylinder;

import java.util.List;

import dev.engineeredcombustion.foundation.EngineConditionText;
import dev.engineeredcombustion.registry.ECDataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * The Piston Assembly, which is a <i>service part</i> as much as it is a
 * component.
 *
 * <p>The only thing this adds to a plain item is a tooltip line, and it exists
 * because that line is what makes the maintenance loop legible. A player who
 * pulls an assembly out of a bore and finds a stack in their inventory has to be
 * able to tell whether it is the tired one they just removed or the fresh one
 * they crafted this morning - and since the two are otherwise identical items,
 * the tooltip is the only place that can say so.
 *
 * <p>A pristine assembly prints nothing. The line appearing is the information.
 */
public class PistonAssemblyItem extends Item {

	public PistonAssemblyItem(Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		super.appendHoverText(stack, context, tooltip, flag);
		EngineConditionText.appendCondition(tooltip, stack, ECDataComponents.PISTON_WEAR, "gui.condition");
	}
}
