package dev.engineeredcombustion.client;

import java.util.List;

import dev.engineeredcombustion.EngineeredCombustion;
import net.minecraft.ChatFormatting;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * The one or two lines that say what an engine part is for.
 *
 * <p>Answers exactly one question - <b>"what is this item?"</b> - and
 * deliberately no more. A tooltip is the wrong place for a manual: Ponder
 * explains how the machine works, the recipe viewer explains how to make it, and
 * the goggles explain what it is doing right now. A part that needs a paragraph
 * to understand needs a Ponder scene, not a longer tooltip.
 *
 * <h2>Driven by the language file, not by Java</h2>
 * There is no table of strings here. A tooltip line exists if a translation key
 * for it exists, which means adding one is a line in {@code en_us.json} and
 * {@code de_de.json} and nothing else, and means no player-visible English can
 * ever be hard-coded in this class. Lines are numbered from 1 and the scan stops
 * at the first gap:
 *
 * <pre>
 * engineered_combustion.tooltip.crankshaft.1
 * engineered_combustion.tooltip.crankshaft.2
 * </pre>
 *
 * <p>Client-only, because {@link I18n} is - which is also why this asks whether
 * a key <i>exists</i> rather than translating and comparing, since a missing key
 * translates to itself and that comparison is the usual way this goes wrong.
 */
@EventBusSubscriber(modid = EngineeredCombustion.ID, value = Dist.CLIENT)
public final class ECItemTooltips {

	private ECItemTooltips() {
	}

	/** Most lines any one item may have. A stop, not a target. */
	private static final int MAX_LINES = 4;

	@SubscribeEvent
	public static void onItemTooltip(ItemTooltipEvent event) {
		ItemStack stack = event.getItemStack();
		ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
		if (!EngineeredCombustion.ID.equals(id.getNamespace()))
			return;

		List<Component> tooltip = event.getToolTip();
		for (int line = 1; line <= MAX_LINES; line++) {
			String key = EngineeredCombustion.ID + ".tooltip." + id.getPath() + "." + line;
			if (!I18n.exists(key))
				// Numbered from 1 with no gaps, so the first miss is the end.
				break;
			tooltip.add(Component.translatable(key)
				.withStyle(ChatFormatting.GRAY));
		}
	}
}
