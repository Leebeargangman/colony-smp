package com.colonysmp.store;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/** Marks an open inventory as a page of a colony's Central State Chest. */
public final class StorageHolder implements InventoryHolder {

    public final String colonyId;
    public final int page;
    Inventory inventory;

    StorageHolder(String colonyId, int page) {
        this.colonyId = colonyId;
        this.page = page;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
