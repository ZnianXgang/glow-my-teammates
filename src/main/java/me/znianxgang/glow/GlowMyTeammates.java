package me.znianxgang.glow;

import me.znianxgang.glow.command.GlowCommand;
import me.znianxgang.glow.config.GlowConfigManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GlowMyTeammates implements ModInitializer {
	public static final String MOD_ID = "glow-my-teammates";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Glow My Teammates initializing...");

		// Config is per world: load on start, drop the reference on stop — an
		// integrated server can restart in the same JVM.
		ServerLifecycleEvents.SERVER_STARTED.register(
				server -> GlowConfigManager.getInstance().loadFromWorld(server));

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			GlowConfigManager.getInstance().clearServer();
			WaypointSync.clear();
		});

		// Locator-bar rebuilds run at the tick boundary, so they see the
		// settled team state of the whole tick.
		ServerTickEvents.END_SERVER_TICK.register(WaypointSync::flushPendingRebuilds);

		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> GlowCommand.register(dispatcher));

		LOGGER.info("Glow My Teammates initialized");
	}
}
