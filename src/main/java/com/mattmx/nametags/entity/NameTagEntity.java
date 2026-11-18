package com.mattmx.nametags.entity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.mattmx.nametags.NameTags;
import com.mattmx.nametags.entity.trait.TraitHolder;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import me.tofaa.entitylib.meta.display.TextDisplayMeta;
import me.tofaa.entitylib.wrapper.WrapperEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta;
import com.mattmx.nametags.utils.ComponentUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public class NameTagEntity {
    private static final int TEXT_DISPLAY_TEXT_INDEX = 23;
    private static final TextComponent RELATIVE_ARG_PREFIX = Component.text("%rel_");

    private final @NotNull TraitHolder traits = new TraitHolder(this);
    private final @NotNull Entity bukkitEntity;
    private final @NotNull WrapperEntity passenger;
    private float cachedViewRange = -1f;
    // Metadata values the current viewers already have, keyed by metadata index.
    private final @NotNull Map<Integer, Object> lastSentMeta = new HashMap<>();

    public NameTagEntity(@NotNull Entity entity) {
        this.bukkitEntity = entity;
        this.passenger = new WrapperEntity(EntityTypes.TEXT_DISPLAY);
    }

    public void initialize() {
        Location location = updateLocation();

        this.passenger.spawn(location);

        if (NameTags.getInstance().getConfig().getBoolean("show-self", false)) {

            if (this.bukkitEntity instanceof Player self) {
                this.passenger.addViewer(self.getUniqueId());
                sendPassengerPacket(self);
            }

        }
    }

    public boolean isInvisible() {
        boolean hasInvisibilityEffect = bukkitEntity instanceof LivingEntity e
            && e.hasPotionEffect(PotionEffectType.INVISIBILITY);

        return bukkitEntity.isInvisible() || hasInvisibilityEffect;
    }

    public void updateVisibility() {
        updateVisibility(isInvisible());
    }

    public void updateVisibility(final boolean isInvisible) {
        modify((meta) -> {
            if (isInvisible && !meta.isInvisible()) {
                this.cachedViewRange = meta.getViewRange();
                meta.setViewRange(0f);
            } else if (!isInvisible && meta.isInvisible()) {
                meta.setViewRange(this.cachedViewRange);
            }
        });
    }

    public @NotNull TraitHolder getTraits() {
        return traits;
    }

    public void modify(Consumer<TextDisplayMeta> consumer) {
        this.passenger.consumeEntityMeta(TextDisplayMeta.class, consumer);
    }

    public @NotNull TextDisplayMeta getMeta() {
        return this.passenger.getEntityMeta(TextDisplayMeta.class);
    }

    /**
     * Sends only the metadata entries that changed since the last flush to the
     * current viewers. Sends nothing when nothing changed.
     *
     * Replaces {@link WrapperEntity#refresh()}, which resends every metadata
     * entry plus a passenger packet to every viewer each time it is called.
     * New viewers still receive the full metadata from EntityLib's addViewer.
     *
     * @return true if a packet was sent
     */
    public synchronized boolean flushMetaChanges() {
        if (!this.passenger.isSpawned()) return false;

        List<EntityData<?>> changed = null;
        for (EntityData<?> entry : getMeta().createPacket().getEntityMetadata()) {
            Object previous = this.lastSentMeta.get(entry.getIndex());
            if (previous != null && Objects.equals(previous, entry.getValue())
                && !needsPerViewerResend(entry)) continue;

            this.lastSentMeta.put(entry.getIndex(), entry.getValue());
            if (changed == null) changed = new ArrayList<>();
            changed.add(entry);
        }

        if (changed == null) return false;

        this.passenger.sendPacketsToViewers(
            new WrapperPlayServerEntityMetadata(this.passenger.getEntityId(), changed)
        );
        return true;
    }

    /**
     * Relational placeholders are resolved per viewer in the outgoing packet
     * handler, so the text must keep being sent even when the base text is
     * unchanged, otherwise those placeholders would never update.
     */
    private boolean needsPerViewerResend(@NotNull EntityData<?> entry) {
        return entry.getIndex() == TEXT_DISPLAY_TEXT_INDEX
            && entry.getValue() instanceof TextComponent text
            && NameTags.getInstance().getConfig().getBoolean("options.relative-placeholders-support")
            && ComponentUtils.contains(text, RELATIVE_ARG_PREFIX);
    }

    /**
     * Center-billboarded tags are pitched down so the F3 debug target doesn't
     * get in the way. Only sends rotation packets when the pitch is wrong.
     */
    public void ensureRotation() {
        float wantedPitch = wantedPitch();
        if (this.passenger.getLocation().getPitch() != wantedPitch) {
            this.passenger.rotateHead(0f, wantedPitch);
        }
    }

    private float wantedPitch() {
        return getMeta().getBillboardConstraints() == AbstractDisplayMeta.BillboardConstraints.CENTER ? 90f : 0f;
    }

    public void sendPassengerPacket(Player target) {
        PacketEvents.getAPI()
            .getPlayerManager()
            .sendPacket(target, getPassengersPacket());
    }

    public PacketWrapper<?> getPassengersPacket() {
        int[] previousPackets = NameTags.getInstance()
            .getEntityManager()
            .getLastSentPassengers(getBukkitEntity().getEntityId())
            .orElseGet(() -> {
                int[] bukkitPassengers = this.bukkitEntity.getPassengers()
                    .stream()
                    .mapToInt(Entity::getEntityId)
                    .toArray();

                int[] passengers = Arrays.copyOf(bukkitPassengers, bukkitPassengers.length + 1);
                passengers[passengers.length - 1] = getPassenger().getEntityId();

                return passengers;
            });

        return new WrapperPlayServerSetPassengers(bukkitEntity.getEntityId(), previousPackets);
    }

    public @NotNull Entity getBukkitEntity() {
        return bukkitEntity;
    }

    public @NotNull WrapperEntity getPassenger() {
        return passenger;
    }

    public @NotNull Location updateLocation() {
        org.bukkit.Location bukkitLocation = bukkitEntity.getLocation();
        bukkitLocation.setY(bukkitEntity.getBoundingBox().getMaxY());

        Location location = SpigotConversionUtil.fromBukkitLocation(bukkitLocation);

        location.setYaw(0f);
        // Bake the pitch into the location so spawn packets already carry it
        // and we don't need to send rotation packets on every refresh.
        location.setPitch(wantedPitch());

        this.passenger.setLocation(location);

        return location;
    }

    public void destroy() {
        this.passenger.despawn();
        this.getTraits().destroy();
    }
}
