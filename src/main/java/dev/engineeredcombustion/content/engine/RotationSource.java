package dev.engineeredcombustion.content.engine;

/** What is currently turning the engine. Purely informational. */
public enum RotationSource {

	NONE("none"),
	/** Another Create kinetic source is motoring the engine. */
	EXTERNAL("external"),
	/** The engine is the kinetic source of its network. */
	ENGINE("engine");

	private final String id;

	RotationSource(String id) {
		this.id = id;
	}

	public String translationKey() {
		return "gui.source." + id;
	}
}
