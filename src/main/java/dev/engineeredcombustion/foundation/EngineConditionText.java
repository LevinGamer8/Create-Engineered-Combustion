package dev.engineeredcombustion.foundation;

import java.util.List;

import dev.engineeredcombustion.content.engine.WearCondition;
import dev.engineeredcombustion.registry.ECDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * How a part's condition is written down for the player, in one place.
 *
 * <p>Every readout in the mod - the engine overlay, the Cylinder's overlay, the
 * sneak diagnostics, the item tooltips - goes through here, so a Piston Assembly
 * reads the same in the player's hand as it does in the bore, and the colour that
 * means "start thinking about a replacement" means it everywhere.
 *
 * <p>What is deliberately absent is a number. The exact wear is a float the
 * simulation needs and the player does not; showing it would turn a machine with
 * worn parts in it into a percentage bar, which is the reading this whole
 * milestone is built to avoid. Development diagnostics can still get at the
 * floats - see {@code CrankshaftBlockEntity#describeWear}.
 */
public class EngineConditionText {

	private EngineConditionText() {
	}

	/**
	 * The colour a condition is printed in.
	 *
	 * <p>Green while the part is fine, amber while it is on its way out, red once
	 * it is worth acting on. Critical gets its own darker red so that "this needs
	 * replacing" and "this is finished" are not the same colour at a glance.
	 */
	public static ChatFormatting color(WearCondition condition) {
		return switch (condition) {
			case PRISTINE, GOOD -> ChatFormatting.GREEN;
			case USED -> ChatFormatting.YELLOW;
			case WORN -> ChatFormatting.GOLD;
			case POOR -> ChatFormatting.RED;
			case CRITICAL -> ChatFormatting.DARK_RED;
		};
	}

	/** A condition as one coloured word: "Good", "Worn", "Critical". */
	public static Component name(WearCondition condition) {
		return ECLang.translate(condition.translationKey())
			.style(color(condition))
			.component();
	}

	/**
	 * A labelled condition line, e.g. {@code Compression: Worn}.
	 *
	 * @param labelKey the line's own translation key root, taking one argument
	 */
	public static Component line(String labelKey, WearCondition condition) {
		return ECLang.translate(labelKey, name(condition))
			.style(ChatFormatting.GRAY)
			.component();
	}

	/**
	 * Adds a condition line to an item tooltip - but only when there is something to
	 * say.
	 *
	 * <p>A pristine part says nothing at all, which is what keeps a freshly crafted
	 * Piston Assembly's tooltip as quiet as every other component's. The line
	 * appearing <i>is</i> the information: a part that has been in an engine
	 * announces itself, and one that has not is silent.
	 */
	public static void appendCondition(List<Component> tooltip, ItemStack stack, DataComponentType<Float> type,
		String labelKey) {
		WearCondition condition = WearCondition.of(ECDataComponents.wearOf(stack, type));
		if (!condition.isWorthReporting())
			return;
		tooltip.add(line(labelKey, condition));
	}
}
