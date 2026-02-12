package com.geminispoofer;

import org.bukkit.Location;

import java.util.UUID;

public class FakePlayer {
    private final String name;
    private final String skinName;
    private final UUID uuid;
    private final int entityId;
    private Location location;
    private int ping = 42;

    public FakePlayer(String name, String skinName, UUID uuid, int entityId, Location location) {
        this.name = name;
        this.skinName = skinName;
        this.uuid = uuid;
        this.entityId = entityId;
        this.location = location.clone();
    }

    public String getName() {
        return name;
    }

    public String getSkinName() {
        return skinName;
    }

    public UUID getUuid() {
        return uuid;
    }

    public int getEntityId() {
        return entityId;
    }

    public Location getLocation() {
        return location.clone();
    }

    public void setLocation(Location location) {
        this.location = location.clone();
    }

    public int getPing() {
        return ping;
    }

    public void setPing(int ping) {
        this.ping = ping;
    }
}
