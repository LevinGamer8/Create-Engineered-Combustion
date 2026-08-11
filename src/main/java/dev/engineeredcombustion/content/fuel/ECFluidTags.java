package dev.engineeredcombustion.content.fuel;

import dev.engineeredcombustion.EngineeredCombustion;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;

/**
 * The single place this mod names a class of fluid.
 *
 * <p>Nothing in the engine ever compares against a {@code Fluid} instance. Every
 * question of the form "is this the right stuff" resolves to one of these tags,
 * which is what makes the engine work with another mod's petroleum without a
 * line of compatibility code: a datapack that adds a fluid to one of these tags
 * has added it to the engine.
 *
 * <h2>How these tags are populated</h2>
 * Each of the three is a thin wrapper that contains <i>only</i> the matching
 * conventional tag under the {@code c:} namespace, and it is the {@code c:} tag
 * that lists this mod's own fluids:
 *
 * <pre>
 *   engineered_combustion:gasoline   -&gt;  #c:gasoline    -&gt;  our gasoline
 *   engineered_combustion:engine_oil -&gt;  #c:engine_oil  -&gt;  our engine oil
 *   engineered_combustion:crude_oil  -&gt;  #c:crude_oil   -&gt;  our crude oil
 * </pre>
 *
 * That indirection is the whole compatibility story, and it gives a pack author
 * two doors rather than one. Adding a fluid to the {@code c:} tag makes it
 * usable here <i>and</i> in every other mod that reads the same convention;
 * adding it to the {@code engineered_combustion:} tag makes it usable here only,
 * which is the right door when a fluid is acceptable to this engine but is not
 * really the conventional thing.
 *
 * <h2>Why {@code c:} names that NeoForge does not define</h2>
 * NeoForge's {@code Tags.Fluids} for 1.21.1 covers water, lava, milk, honey,
 * potions, the soups, experience and gases - there is no petroleum convention to
 * follow and none to violate. Create itself publishes its own fluids the same
 * way, under {@code c:honey}, {@code c:chocolate} and {@code c:tea}, so a
 * {@code c:} tag named after the fluid is the established convention here rather
 * than an invention. Defining them ourselves also means they always exist: a
 * recipe or a predicate reading {@code #c:gasoline} cannot fail because no other
 * mod happened to create it.
 *
 * <h2>Deliberately no hardcoded external IDs</h2>
 * Create: Diesel Generators and TFMG are not dependencies, are not on this
 * project's classpath, and their 1.21.1 fluid identifiers were not available to
 * verify here. Guessing them would produce a tag entry that silently matches
 * nothing, which is strictly worse than a documented absence - so the
 * integration point is these tags, and it is documented instead.
 */
public final class ECFluidTags {

	/** Fluids a gasoline engine will burn. Queried by the Carburetor. */
	public static final TagKey<Fluid> GASOLINE = tag("gasoline");

	/** Fluids the Oil Sump accepts as lubricant. Queried by the Oil Sump. */
	public static final TagKey<Fluid> ENGINE_OIL = tag("engine_oil");

	/**
	 * Unrefined petroleum, i.e. what the refining recipes take as input.
	 *
	 * <p>Unlike the two above, nothing in the simulation asks about this one -
	 * crude is refinery feedstock, not something an engine ever touches. It earns
	 * its place because the refining recipes consume the <i>tag</i> rather than
	 * this mod's fluid, so another mod's crude oil can be run through this mod's
	 * refinery with no recipe of ours mentioning it.
	 */
	public static final TagKey<Fluid> CRUDE_OIL = tag("crude_oil");

	private ECFluidTags() {
	}

	private static TagKey<Fluid> tag(String path) {
		return TagKey.create(Registries.FLUID, EngineeredCombustion.asResource(path));
	}
}
