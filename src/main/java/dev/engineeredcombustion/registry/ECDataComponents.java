package dev.engineeredcombustion.registry;

import java.util.function.UnaryOperator;

import com.mojang.serialization.Codec;

import dev.engineeredcombustion.EngineeredCombustion;
import dev.engineeredcombustion.content.engine.EngineWearMath;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.ItemStack;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The item data this mod attaches to engine parts.
 *
 * <h2>Why the wear lives on the item</h2>
 * A worn part has to stay worn. Pulling a tired Piston Assembly out of a bore and
 * pushing it straight back in must not repair it, and neither must mining a
 * crankcase and placing the same block again - that would be a free rebuild for
 * the price of two clicks, and it would make the whole maintenance loop
 * pointless.
 *
 * <p>The only place that survives a part being an item rather than a block is the
 * item stack itself, so that is where the wear goes: as a Data Component, which
 * is 1.21's mechanism for exactly this. The two block entities that own a part
 * while it is installed hand their wear to the stack when it comes out and take
 * it back when it goes in - see {@code CylinderBlockEntity} and
 * {@code CrankshaftBlockEntity}.
 *
 * <h2>Absent means new</h2>
 * A component that is not present reads as 0, which is the correct answer for
 * every stack that could possibly lack one: a freshly crafted part, a creative
 * inventory stack, an item from a command, and every stack in a world saved
 * before this milestone. Wear is only ever <i>written</i> onto a stack that
 * actually has some, so a pristine part carries no data at all and looks exactly
 * like the one the player crafted.
 */
public class ECDataComponents {

	private static final DeferredRegister.DataComponents DATA_COMPONENTS =
		DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, EngineeredCombustion.ID);

	/**
	 * Compression-related wear of a Piston Assembly, {@code [0, 1]}.
	 *
	 * <p>Set when the part is pulled out of a Cylinder or the Cylinder is mined,
	 * read when it is installed again. This is the whole of the no-free-repair
	 * guarantee for the bores.
	 */
	public static final DataComponentType<Float> PISTON_WEAR = registerWear("piston_wear");

	/**
	 * Bearing wear of a Crankshaft section, {@code [0, 1]}.
	 *
	 * <p>Reaches the dropped item through the block's loot table - the
	 * {@code minecraft:copy_components} function on it copies exactly this - and
	 * comes back through {@code applyImplicitComponents} when the block is placed.
	 */
	public static final DataComponentType<Float> CRANKSHAFT_BEARING_WEAR = registerWear("crankshaft_bearing_wear");

	/**
	 * Wear as this mod stores it on an item: one float, in the same {@code [0, 1]}
	 * the simulation uses, range-checked on the way in so a hand-edited or
	 * datapack-supplied stack cannot smuggle a value the physics has never seen.
	 */
	private static DataComponentType<Float> registerWear(String name) {
		return register(name, builder -> builder.persistent(Codec.floatRange(0.0F, 1.0F))
			.networkSynchronized(ByteBufCodecs.FLOAT));
	}

	/**
	 * Builds the type eagerly and registers a supplier for it, so call sites get a
	 * plain {@code DataComponentType} rather than a holder to unwrap. Create's own
	 * {@code AllDataComponents} does the same, and it is safe because a component
	 * type carries no registry-dependent state.
	 */
	private static <T> DataComponentType<T> register(String name, UnaryOperator<DataComponentType.Builder<T>> builder) {
		DataComponentType<T> type = builder.apply(DataComponentType.builder())
			.build();
		DATA_COMPONENTS.register(name, () -> type);
		return type;
	}

	// --- reading and writing ------------------------------------------------

	/**
	 * The wear on this stack, or 0 for a stack that carries none.
	 *
	 * <p>Clamped on the way out as well as on the way in. Wear can arrive from
	 * outside the mod entirely - an old world, a command, a datapack - and the
	 * honest response to a value the simulation has never seen is to bring it into
	 * range, not to trust it and not to throw.
	 */
	public static float wearOf(ItemStack stack, DataComponentType<Float> type) {
		return EngineWearMath.clampWear(stack.getOrDefault(type, 0.0F));
	}

	/**
	 * Writes wear onto a stack, or removes the component entirely when the part is
	 * pristine.
	 *
	 * <p>Removing rather than storing a zero is what keeps a freshly crafted part
	 * byte-identical to one that has been fitted and pulled straight back out: it
	 * stacks with its siblings, it shows no tooltip line, and nothing about it hints
	 * that it has been anywhere.
	 */
	public static void setWear(ItemStack stack, DataComponentType<Float> type, float wear) {
		float clamped = EngineWearMath.clampWear(wear);
		if (clamped <= 0.0F)
			stack.remove(type);
		else
			stack.set(type, clamped);
	}

	public static void register(IEventBus modEventBus) {
		DATA_COMPONENTS.register(modEventBus);
	}
}
