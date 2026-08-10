package dev.engineeredcombustion.content.engine.control;

import java.util.Locale;

import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.INamedIconOptions;
import com.simibubi.create.foundation.gui.AllIcons;

import dev.engineeredcombustion.EngineeredCombustion;
import dev.engineeredcombustion.content.engine.EngineTuning;
import net.minecraft.util.Mth;

/**
 * What, if anything, a redstone signal at the Crankshaft is allowed to do.
 *
 * <p>The default is {@link #MANUAL}, and it is the mode an engine is in whenever
 * no Redstone Control Module is installed - which is the whole point of the
 * module. An engine without one ignores redstone completely: see
 * {@code CrankshaftBlockEntity#readRedstoneSignal()}, which does not even look at
 * the neighbours unless a mode that uses them is selected.
 *
 * <p>Implementing Create's {@link INamedIconOptions} is what lets a
 * {@code ScrollOptionBehaviour} present these four as a normal Create value box:
 * the icon is drawn in the box, the translation key names the mode in the value
 * settings screen, and Create handles the packet, the persistence and the
 * client/server split. Nothing here is client-only - Create's own mode enums
 * (e.g. {@code IControlContraption.MovementMode}) reference {@code AllIcons} from
 * common code in exactly this way.
 *
 * <h2>Adding a mode later</h2>
 * Only two methods decide what a mode <i>means</i> -
 * {@link #controlsIgnition()} and {@link #controlsThrottle()} - and they are read
 * in exactly one place, {@code CrankshaftBlockEntity#resolveControlState()}. A
 * future automation mode (maintain RPM, auto-start on demand, shut down on a
 * warning) is a new constant plus its answer to those two questions; anything
 * that needs more than they can express should extend the resolver rather than
 * widen them.
 *
 * <p>New constants must be <b>appended</b>. Create's {@code ScrollValueBehaviour}
 * persists the selection as the enum's ordinal, so inserting a constant in the
 * middle would silently change the mode of every engine already built.
 */
public enum ControlMode implements INamedIconOptions {

	/** Redstone is ignored. The switch and the Carburetor are the only controls. */
	MANUAL(AllIcons.I_NONE),

	/** Signal 0 switches the ignition off, 1-15 switch it on. Throttle stays manual. */
	IGNITION(AllIcons.I_PLAY),

	/** Signal drives the throttle across its whole range. Ignition stays manual. */
	THROTTLE(AllIcons.I_PRIORITY_HIGH),

	/** Signal 0 is off; 1-15 is on, with the throttle following the signal. */
	IGNITION_AND_THROTTLE(AllIcons.I_WHITELIST_AND);

	/** Strongest signal a redstone input can carry. */
	public static final int MAX_SIGNAL = 15;

	private final AllIcons icon;
	private final String translationKey;

	ControlMode(AllIcons icon) {
		this.icon = icon;
		this.translationKey =
			EngineeredCombustion.ID + ".gui.control_mode." + name().toLowerCase(Locale.ROOT);
	}

	// --- what a mode means ---------------------------------------------------

	/** Whether the redstone signal decides the ignition rather than the switch. */
	public boolean controlsIgnition() {
		return this == IGNITION || this == IGNITION_AND_THROTTLE;
	}

	/** Whether the redstone signal decides the throttle rather than the Carburetor. */
	public boolean controlsThrottle() {
		return this == THROTTLE || this == IGNITION_AND_THROTTLE;
	}

	/** Whether this mode reads redstone at all. False for exactly {@link #MANUAL}. */
	public boolean usesRedstone() {
		return controlsIgnition() || controlsThrottle();
	}

	/**
	 * Throttle percentage commanded by a redstone signal, for the two modes that
	 * read one. Documented mapping:
	 *
	 * <pre>
	 * THROTTLE                signal  0 -> 0 %   (idle - 0 % is a closed main
	 *                                             throttle, not an engine stop)
	 *                                15 -> 100 %
	 *                         linear in between: percent = round(signal * 100 / 15)
	 *
	 * IGNITION_AND_THROTTLE   signal  0 -> ignition off; no throttle is commanded
	 *                                 1 -> 0 %   (the lowest running command: idle)
	 *                                15 -> 100 %
	 *                         linear in between: percent = round((signal - 1) * 100 / 14)
	 * </pre>
	 *
	 * The two differ because 0 means something else in each: in THROTTLE mode it
	 * is the bottom of the range, while in IGNITION_AND_THROTTLE it is reserved for
	 * switching the engine off, so the running range starts at 1 and the whole
	 * throttle span is stretched over 1-15 rather than losing its bottom step.
	 */
	public int commandedThrottlePercent(int signal) {
		int clamped = Mth.clamp(signal, 0, MAX_SIGNAL);
		float fraction = switch (this) {
			case THROTTLE -> clamped / (float) MAX_SIGNAL;
			case IGNITION_AND_THROTTLE -> clamped <= 0 ? 0.0F : (clamped - 1) / (float) (MAX_SIGNAL - 1);
			// Not a throttle mode; the resolver never asks, and 0 % is the harmless
			// answer if a later mode forgets to.
			default -> 0.0F;
		};
		return Math.round(fraction * EngineTuning.THROTTLE_MAX_PERCENT);
	}

	// --- identity ------------------------------------------------------------

	@Override
	public AllIcons getIcon() {
		return icon;
	}

	@Override
	public String getTranslationKey() {
		return translationKey;
	}

	/**
	 * The mode Create's integer-valued scroll behaviour is pointing at. Out of
	 * range values clamp rather than throw: the value arrives from NBT, which a
	 * different version of this mod may have written.
	 */
	public static ControlMode byOrdinal(int ordinal) {
		return values()[Mth.clamp(ordinal, 0, values().length - 1)];
	}
}
