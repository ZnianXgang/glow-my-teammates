package com.glow.teammates;

import com.glow.teammates.command.GlowCommand;
import com.glow.teammates.config.GlowConfigManager;
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

		// Load config when the server starts
		ServerLifecycleEvents.SERVER_STARTED.register(
				server -> GlowConfigManager.getInstance().loadFromWorld(server));

		// Drop the server reference and the waypoint pending set when the
		// server stops — an integrated server can restart in the same JVM.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			GlowConfigManager.getInstance().clearServer();
			WaypointSync.clear();
		});

		// Drain deferred locator-bar rebuilds at the tick boundary — the
		// rebuild must see the final team state, and the server reference lets
		// the drain skip dimensions left by a crashed previous server.
		ServerTickEvents.END_SERVER_TICK.register(WaypointSync::flushPendingRebuilds);

		// Register commands
		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> GlowCommand.register(dispatcher));

		LOGGER.info("Glow My Teammates initialized");
	}
}
