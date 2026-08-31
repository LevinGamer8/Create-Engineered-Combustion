package dev.engineeredcombustion.foundation;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * Which way the engine under a stacked component runs - or that there is no
 * engine under it at all.
 *
 * <p>The Cylinder, the Carburetor and the Oil Sump are stacked on a crankshaft
 * section and have no orientation of their own to place them by: a player points
 * at a block and puts one on top of it. What decides which way they face is the
 * crankshaft, and this is that answer carried in a block state so the baked model
 * can be turned by it.
 *
 * <p>{@link #NONE} is the third value and it earns its place. Two Cylinders
 * standing side by side with nothing underneath them are not an engine, and must
 * not grow an intake manifold between them just because they are adjacent - so
 * "aligned with an engine along X" and "not part of an engine" have to be
 * different states rather than one boolean's worth of guessing.
 *
 * <p>Everything here is cosmetic. No simulation reads it; {@code EngineComponents}
 * resolves the real engine from the crankshaft's own axis, every time, and would
 * do so identically if every value below were wrong.
 */
public enum EngineAxis implements StringRepresentable {

	/** Not sitting on a crankshaft section, so not part of any engine. */
	NONE("none"),

	X("x"),

	Z("z");

	/**
	 * Deliberately the same property name the crankshaft's own axis uses. They
	 * are the same question asked of different blocks, and a player reading
	 * {@code F3} at a stalled engine should not have to learn two words for it.
	 */
	public static final EnumProperty<EngineAxis> PROPERTY = EnumProperty.create("axis", EngineAxis.class);

	private final String name;

	EngineAxis(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return name;
	}

	/** The alignment for a crank axis, or {@link #NONE} for no crankshaft. */
	public static EngineAxis of(@Nullable Direction.Axis axis) {
		if (axis == Direction.Axis.X)
			return X;
		if (axis == Direction.Axis.Z)
			return Z;
		return NONE;
	}

	/** The crank axis this alignment names, or null when it names none. */
	@Nullable
	public Direction.Axis axis() {
		return this == X ? Direction.Axis.X : this == Z ? Direction.Axis.Z : null;
	}

	/** Whether this component is lined up with an engine at all. */
	public boolean isAligned() {
		return this != NONE;
	}

	/**
	 * One step along this alignment. Never called on {@link #NONE} - the callers
	 * all check {@link #isAligned()} first, because a component that is not on an
	 * engine has no neighbours worth asking about.
	 */
	public Direction towards(Direction.AxisDirection side) {
		Direction.Axis axis = axis();
		if (axis == null)
			throw new IllegalStateException("no direction along " + this);
		return Direction.get(side, axis);
	}
}
