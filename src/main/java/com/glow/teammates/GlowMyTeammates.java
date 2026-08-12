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
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			GlowConfigManager.getInstance().loadFromWorld(server);
		});

		// Drop the server reference and the waypoint dedup map when the
		// server stops — an integrated server can start again in the same
		// JVM, and stale ServerLevel keys would otherwise linger.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			GlowConfigManager.getInstance().clearServer();
			WaypointSync.clear();
		});

		// Drain deferred locator-bar rebuilds at the tick boundary — see
		// WaypointSync for why the rebuild must see the final team state.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			WaypointSync.flushPendingRebuilds();
		});

		// Register commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			GlowCommand.register(dispatcher);
		});

		LOGGER.info("Glow My Teammates initialized");
	}
}
