package com.mattmx.nametags.config;

import com.mattmx.nametags.NameTags;
import com.mattmx.nametags.entity.NameTagEntity;
import com.mattmx.nametags.entity.trait.RefreshTrait;
import com.mattmx.nametags.entity.trait.SneakTrait;
import com.mattmx.nametags.event.NameTagEntityCreateEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class ConfigDefaultsListener implements Listener {
    private final @NotNull NameTags plugin;

    public ConfigDefaultsListener(@NotNull NameTags plugin) {
        this.plugin = plugin;

        NameTags.getInstance()
            .getEntityManager()
            .setDefaultProvider(((entity, meta) -> {
                meta.setUseDefaultBackground(false);
                meta.setTransformationInterpolationDuration(5);
                meta.setPositionRotationInterpolationDuration(5);
                TextDisplayMetaConfiguration.applyMeta(defaultSection(), meta);
            }));
    }

    /**
     * Caches the player's highest-priority group so we don't check every
     * group permission and sort on every refresh. Re-checked every
     * {@code options.group-check-interval} ms (default 5000).
     */
    private final class GroupCache {
        private final @NotNull Player player;
        private @Nullable Map.Entry<String, ConfigurationSection> highest = null;
        private long nextCheck = 0L;

        private GroupCache(@NotNull Player player) {
            this.player = player;
        }

        private @Nullable Map.Entry<String, ConfigurationSection> get() {
            long now = System.currentTimeMillis();
            if (now < nextCheck) return highest;

            highest = plugin.getGroups()
                .entrySet()
                .stream()
                .filter((e) -> player.hasPermission(e.getKey()))
                .reduce((a, b) -> GroupPriorityComparator.get().compare(a, b) > 0 ? a : b)
                .orElse(null);
            nextCheck = now + plugin.getConfig().getLong("options.group-check-interval", 5000L);
            return highest;
        }
    }

    private ConfigurationSection defaultSection() {
        return plugin.getConfig().getConfigurationSection("defaults");
    }

    @EventHandler
    public void onCreate(@NotNull NameTagEntityCreateEvent event) {
        if (!(event.getNameTag().getBukkitEntity() instanceof Player player)) return;

        // By default, we shouldn't notify until we have finished processing.
        event.getNameTag()
            .getPassenger()
            .getEntityMeta()
            .setNotifyAboutChanges(false);

        long refreshMillis = plugin.getConfig().getLong("defaults.refresh-every", 50);

        if (refreshMillis == 0L) {
            return;
        }

        registerDefaultRefreshListener(event.getNameTag(), refreshMillis);
    }

    public void registerDefaultRefreshListener(@NotNull NameTagEntity tag, long refreshMillis) {
        Player player = (Player) tag.getBukkitEntity();
        GroupCache groupCache = new GroupCache(player);

        tag.getTraits().getOrAddTrait(RefreshTrait.class, () ->
            RefreshTrait.ofMillis(
                plugin,
                refreshMillis,
                (entity) -> {
                    TextDisplayMetaConfiguration.applyMeta(defaultSection(), entity.getMeta());
                    TextDisplayMetaConfiguration.applyTextMeta(defaultSection(), entity.getMeta(), player);

                    Map.Entry<String, ConfigurationSection> highest = groupCache.get();

                    long recentRefreshEvery = plugin.getConfig().getLong("defaults.refresh-every", 50);
                    if (highest != null) {

                        TextDisplayMetaConfiguration.applyMeta(highest.getValue(), entity.getMeta());
                        TextDisplayMetaConfiguration.applyTextMeta(highest.getValue(), entity.getMeta(), player);

                        long groupRefresh = highest.getValue().getLong("refresh-every", -1);
                        if (groupRefresh > 0) {
                            recentRefreshEvery = groupRefresh;
                        }
                    }

                    if (recentRefreshEvery != refreshMillis) {
                        entity.getTraits().removeTrait(RefreshTrait.class);
                        registerDefaultRefreshListener(tag, recentRefreshEvery);
                    }

                    // Look passenger down to remove debug getting in the way.
                    // Only sends packets if the billboard changed since last time.
                    entity.ensureRotation();

                    // Preserve background color for sneaking
                    // Maybe we should introduce an `afterRefresh` callback?
                    entity.getTraits()
                        .getTrait(SneakTrait.class)
                        .ifPresent(SneakTrait::manuallyUpdateSneakingOpacity);

                    entity.updateVisibility();
                    // Only send metadata entries that actually changed.
                    entity.flushMetaChanges();
                }
            )
        );
    }

}
