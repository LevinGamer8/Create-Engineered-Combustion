package dev.engineeredcombustion.content.engine;

/**
 * What is currently turning the engine. Purely informational, and the fastest
 * way to read a multi-engine network: an engine reporting {@link #EXTERNAL} is
 * being spun by somebody else's power and must be contributing none of its own.
 */
public enum RotationSource {

	NONE("none"),
	/** Another Create kinetic source is motoring the engine. */
	EXTERNAL("external"),
	/**
	 * Nothing is driving the crankshaft; it is turning on the momentum already in
	 * the flywheel. A coast-down, whether after a shutdown or after an external
	 * source was disconnected.
	 */
	MOMENTUM("momentum"),
	/** The engine is producing this rotation itself, by burning fuel. */
	ENGINE("engine");

	private final String id;

	RotationSource(String id) {
		this.id = id;
	}

	public String translationKey() {
		return "gui.source." + id;
	}
}
