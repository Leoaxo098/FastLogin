/*
 * SPDX-License-Identifier: MIT
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015-2024 games647 and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.github.games647.fastlogin.bukkit.listener;

import com.github.games647.fastlogin.bukkit.BukkitLoginSession;
import com.github.games647.fastlogin.bukkit.FastLoginBukkit;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Listens to the Paper configuration phase and bypasses AuthMe's pre-join dialog
 * for premium players that FastLogin has already verified.
 *
 * <p>On Minecraft 1.21.5+ (Paper), AuthMe's {@code PaperDialogFlowListener} shows
 * a blocking login or register dialog during the configuration phase. This prevents
 * premium players from reaching {@code PlayerJoinEvent}, which is where FastLogin
 * normally runs its force-login/register logic.</p>
 *
 * <p>This listener runs at {@link EventPriority#LOWEST} (before AuthMe's HIGHEST
 * handler) and schedules a main-thread task that completes the blocking future
 * AuthMe is waiting on, allowing the player to proceed to PLAY state where
 * FastLogin's existing {@code ForceLoginTask} handles automatic premium login.</p>
 *
 * <p>All Paper-specific API classes are accessed via reflection to avoid
 * compile-time dependencies on Paper 1.21.5+ APIs.</p>
 */
public class PaperPreJoinBypassListener implements Listener {

    private final FastLoginBukkit plugin;

    // Reflection handles for AuthMe internals (no public API alternative exists)
    private Object preJoinDialogService;
    private Map<UUID, CompletableFuture<String>> pendingRegisterResponses;
    private boolean reflectionFailed = false;

    // The Paper event class, loaded reflectively
    private Class<?> configureEventClass;
    private Method getConnectionMethod;
    private Method getProfileMethod;
    private Method getNameMethod;
    private Method getIdMethod;

    public PaperPreJoinBypassListener(FastLoginBukkit plugin) {
        this.plugin = plugin;
        initReflection();
    }

    /**
     * Initialises reflection handles to Paper and AuthMe internal APIs.
     *
     * If reflection fails, the listener logs a warning and disables itself gracefully.
     */
    @SuppressWarnings("unchecked")
    private void initReflection() {
        try {
            // Step 1: Load Paper 1.21.5+ configuration event classes reflectively
            configureEventClass = Class.forName(
                    "io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent");
            Class<?> connectionClass = Class.forName("io.papermc.paper.connection.PlayerConfigurationConnection");
            Class<?> profileClass = Class.forName("com.destroystokyo.paper.profile.PlayerProfile");

            getConnectionMethod = configureEventClass.getMethod("getConnection");
            getProfileMethod = connectionClass.getMethod("getProfile");
            getNameMethod = profileClass.getMethod("getName");
            getIdMethod = profileClass.getMethod("getId");

            // Step 2: Access AuthMe internals
            Object authMePlugin = Bukkit.getPluginManager().getPlugin("AuthMeReloaded");
            if (authMePlugin == null) {
                reflectionFailed = true;
                return;
            }

            Class<?> authMeClass = authMePlugin.getClass();
            Field injectorField = authMeClass.getDeclaredField("injector");
            injectorField.setAccessible(true);
            Object injector = injectorField.get(authMePlugin);

            if (injector == null) {
                plugin.getLog().warn("AuthMe injector is null; cannot access PreJoinDialogService");
                reflectionFailed = true;
                return;
            }

            Method getSingleton = injector.getClass().getMethod("getSingleton", Class.class);
            preJoinDialogService = getSingleton.invoke(injector,
                    Class.forName("fr.xephi.authme.service.PreJoinDialogService"));

            // Step 3: Find PaperDialogFlowListener instance from registered listeners
            // to access the private pendingRegisterResponses map
            Method getHandlerList = configureEventClass.getMethod("getHandlerList");
            Object handlerList = getHandlerList.invoke(null);

            Method getRegisteredListeners = handlerList.getClass().getMethod("getRegisteredListeners");
            Object[] registeredListeners = (Object[]) getRegisteredListeners.invoke(handlerList);

            Class<?> registeredListenerClass = registeredListeners[0].getClass();
            Method getListenerMethod = registeredListenerClass.getMethod("getListener");

            for (Object rl : registeredListeners) {
                Object listener = getListenerMethod.invoke(rl);
                String listenerClassName = listener.getClass().getName();
                if ("fr.xephi.authme.listener.PaperDialogFlowListener".equals(listenerClassName)) {
                    Field field = listener.getClass().getDeclaredField("pendingRegisterResponses");
                    field.setAccessible(true);
                    pendingRegisterResponses = (Map<UUID, CompletableFuture<String>>) field.get(listener);
                    break;
                }
            }

            if (preJoinDialogService != null) {
                plugin.getLog().info("Successfully hooked into AuthMe pre-join dialog service "
                        + "for premium auto-bypass on Paper 1.21.5+");
            }
        } catch (Exception e) {
            plugin.getLog().warn("Could not hook into AuthMe pre-join dialog service. "
                    + "Premium auto-bypass on Paper 1.21.5+ will be disabled: {}", e.getMessage());
            reflectionFailed = true;
        }
    }

    /**
     * Registers this listener using a dynamic event executor that invokes
     * the handler via reflection for Paper 1.21.5+ compatibility.
     *
     * @param plugin the FastLogin plugin instance
     * @param listener the bypass listener instance
     */
    public static void register(FastLoginBukkit plugin, PaperPreJoinBypassListener listener) {
        try {
            Class<?> eventClass = Class.forName(
                    "io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent");

            Class<? extends Event> eventClassCast = eventClass.asSubclass(Event.class);
            plugin.getServer().getPluginManager().registerEvent(
                    eventClassCast,
                    listener,
                    EventPriority.LOWEST,
                    (l, event) -> {
                        if (event.getClass() == eventClass) {
                            listener.onPlayerConfigureReflective(event);
                        }
                    },
                    plugin
            );
        } catch (ClassNotFoundException e) {
            // Pre-1.21.5 Paper or non-Paper — event doesn't exist
            plugin.getLog().trace("AsyncPlayerConnectionConfigureEvent not available; "
                    + "skipping pre-join dialog bypass registration");
        }
    }

    /**
     * Reflective event handler called from the dynamic executor.
     *
     * @param event the raw event object
     */
    private void onPlayerConfigureReflective(Event event) {
        if (reflectionFailed || preJoinDialogService == null) {
            return;
        }

        try {
            Object connection = getConnectionMethod.invoke(event);
            Object profile = getProfileMethod.invoke(connection);
            String playerName = (String) getNameMethod.invoke(profile);
            UUID playerId = (UUID) getIdMethod.invoke(profile);

            if (playerName == null || playerId == null) {
                return;
            }

            // Only act on players FastLogin has verified as premium
            BukkitLoginSession session = findPremiumSession(playerName);
            if (session == null || !session.isVerifiedPremium()) {
                return;
            }

            plugin.getLog().info("Bypassing AuthMe pre-join dialog for verified premium player: {}", playerName);

            // Schedule a sync task to complete AuthMe's blocking future.
            // This runs on the main server thread because:
            // 1. AuthMe's HIGHEST handler blocks the async event thread on future.join()
            // 2. Completing the future from any thread unblocks it
            // 3. The main thread is free to execute this while the async thread is blocked
            String normalizedName = playerName.toLowerCase(Locale.ROOT);
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    bypassPreJoinDialog(normalizedName, playerId);
                } catch (Exception e) {
                    plugin.getLog().warn("Failed to bypass AuthMe pre-join dialog for {}: {}",
                            playerName, e.getMessage());
                }
            });
        } catch (Exception e) {
            plugin.getLog().warn("Failed to process configuration event for bypass: {}", e.getMessage());
        }
    }

    /**
     * Completes the blocking future AuthMe is waiting on during the configuration phase.
     *
     * <p>Two cases are handled:</p>
     * <ul>
     *   <li><b>Login dialog:</b> Uses the public {@code approvePreJoinForceLogin} method on
     *       PreJoinDialogService, which completes the future with {@code null} (no kick)
     *       and marks the player for force-login after join.</li>
     *   <li><b>Register dialog:</b> Directly completes the {@code pendingRegisterResponses}
     *       future with {@code null} (no kick), allowing the player to proceed.</li>
     * </ul>
     *
     * <p>In both cases, {@code markSkipPostJoinDialog} is called to prevent AuthMe from
     * showing a post-join dialog before FastLogin's ForceLoginTask handles the player.</p>
     *
     * @param normalizedName the player name in lowercase
     * @param playerId the player's UUID
     */
    private void bypassPreJoinDialog(String normalizedName, UUID playerId) {
        // Case 1: Login dialog — use public PreJoinDialogService API
        try {
            Method approveMethod = preJoinDialogService.getClass()
                    .getMethod("approvePreJoinForceLogin", String.class);
            boolean approved = (boolean) approveMethod.invoke(preJoinDialogService, normalizedName);
            if (approved) {
                plugin.getLog().info("Approved pre-join force login for {}", normalizedName);
            }
        } catch (Exception e) {
            plugin.getLog().warn("Could not call approvePreJoinForceLogin: {}", e.getMessage());
        }

        // Case 2: Register dialog — complete the pending future directly
        if (pendingRegisterResponses != null) {
            CompletableFuture<String> registerFuture = pendingRegisterResponses.get(playerId);
            if (registerFuture != null && !registerFuture.isDone()) {
                registerFuture.complete(null);
                plugin.getLog().info("Completed pending register dialog future for {}", normalizedName);
            }
        }

        // Mark the player to skip post-join dialog (prevents race with ForceLoginTask)
        try {
            Method markSkipMethod = preJoinDialogService.getClass()
                    .getMethod("markSkipPostJoinDialog", UUID.class);
            markSkipMethod.invoke(preJoinDialogService, playerId);
        } catch (Exception e) {
            plugin.getLog().warn("Could not call markSkipPostJoinDialog: {}", e.getMessage());
        }
    }

    /**
     * Finds a FastLogin session for the given player name that has been verified as premium.
     *
     * <p>During the configuration phase, the Bukkit {@code Player} object is not yet available,
     * so we match by iterating the login sessions by name (matching {@code PaperCacheListener}'s
     * approach).</p>
     *
     * @param playerName the player's display name
     * @return the session if found and premium-verified, null otherwise
     */
    private BukkitLoginSession findPremiumSession(String playerName) {
        for (BukkitLoginSession session : plugin.getLoginSessions().values()) {
            if (session.getUsername() != null && session.getUsername().equalsIgnoreCase(playerName)) {
                return session;
            }
        }
        return null;
    }
}
