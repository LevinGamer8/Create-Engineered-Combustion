package dev.engineeredcombustion.foundation;

import dev.engineeredcombustion.EngineeredCombustion;
import net.createmod.catnip.lang.Lang;
import net.createmod.catnip.lang.LangBuilder;

/**
 * This mod's equivalent of Create's {@code CreateLang}.
 *
 * <p>Catnip's {@link LangBuilder} is the addon-facing mechanism behind every
 * Create tooltip. Using it rather than assembling raw {@code Component}s is what
 * makes our overlays lay out identically to Create's own: {@code forGoggles}
 * applies the font-width-aware indentation that leaves room for the overlay
 * icon, which is precisely the margin a hand-rolled tooltip was missing.
 */
public class ECLang {

	public static LangBuilder builder() {
		return Lang.builder(EngineeredCombustion.ID);
	}

	public static LangBuilder translate(String langKey, Object... args) {
		return builder().translate(langKey, args);
	}

	public static LangBuilder text(String text) {
		return builder().text(text);
	}

	/** Whole numbers print without a decimal point; everything else gets one. */
	public static LangBuilder number(double value) {
		return builder().text(value == Math.floor(value) && !Double.isInfinite(value)
			? String.valueOf((long) value)
			: String.format("%.1f", value));
	}
}
