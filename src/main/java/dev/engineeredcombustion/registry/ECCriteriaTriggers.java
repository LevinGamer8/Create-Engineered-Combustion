package dev.engineeredcombustion.registry;

import dev.engineeredcombustion.EngineeredCombustion;
import dev.engineeredcombustion.advancement.EngineEventTrigger;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The mod's advancement criteria.
 *
 * <p>There is exactly one, and that is the design - see
 * {@link EngineEventTrigger}. Every advancement the mod ships is this trigger
 * with a different filter, so this class is not expected to grow with the
 * advancement tree.
 */
public final class ECCriteriaTriggers {

	private ECCriteriaTriggers() {
	}

	public static final DeferredRegister<CriterionTrigger<?>> TRIGGERS =
		DeferredRegister.create(BuiltInRegistries.TRIGGER_TYPES, EngineeredCombustion.ID);

	/** Everything an engine does that a player can be rewarded for. */
	public static final DeferredHolder<CriterionTrigger<?>, EngineEventTrigger> ENGINE_EVENT =
		TRIGGERS.register("engine_event", EngineEventTrigger::new);

	public static void register(IEventBus modEventBus) {
		TRIGGERS.register(modEventBus);
	}
}
