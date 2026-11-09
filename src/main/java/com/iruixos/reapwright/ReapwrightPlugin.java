package com.iruixos.reapwright;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class ReapwrightPlugin extends JavaPlugin implements Listener {
    private final java.util.Map<org.bukkit.Location, Integer> customBrews = new java.util.HashMap<>();
    private final java.util.Set<org.bukkit.Location> watchedStands = new java.util.HashSet<>();
    private boolean warnedProgressApi = false;
    private Set<Material> allowedTools;
    private Set<Material> enabledCrops;
    private int harvestWidth;

    @Override public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tickBrewing, 1L, 1L);
        printStartupBanner();
    }

    private void printStartupBanner() {
        String version = getPluginMeta().getVersion();
        String minecraftVersion = org.bukkit.Bukkit.getMinecraftVersion();
        String[] lines = {
            "[R] Reapwright v" + version,
            "    by iruixos",
            "    Paper " + minecraftVersion + " / MC " + minecraftVersion
        };
        int contentWidth = 0;
        for (String line : lines) contentWidth = Math.max(contentWidth, line.length());
        int innerWidth = contentWidth + 2;
        String border = "+" + "-".repeat(innerWidth) + "+";
        getLogger().info(" ");
        getLogger().info("  " + border);
        for (String line : lines) {
            getLogger().info("  | " + line + " ".repeat(contentWidth - line.length()) + " |");
        }
        getLogger().info("  " + border);
        getLogger().info(" ");
    }

    private Set<Material> materials(String key) {
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (String name : getConfig().getStringList(key)) {
            Material material = Material.matchMaterial(name);
            if (material == null) getLogger().warning("Unknown material in " + key + ": " + name);
            else result.add(material);
        }
        return result;
    }

    private void loadSettings() {
        reloadConfig();
        allowedTools = materials("allowed-tools");
        enabledCrops = materials("enabled-crops");
        harvestWidth = Math.max(1, Math.min(9, getConfig().getInt("harvest-area-size", 1)));
    }

    @Override public void onDisable() { customBrews.clear(); watchedStands.clear(); }

    // Composter support: mimic the standard potato chance, without changing global vanilla recipes.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCompost(org.bukkit.event.player.PlayerInteractEvent event) {
        if (!getConfig().getBoolean("poisonous-potatoes.composting.enabled", true)) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.COMPOSTER) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != Material.POISONOUS_POTATO) return;
        if (!(block.getBlockData() instanceof org.bukkit.block.data.Levelled level)) return;
        if (level.getLevel() >= 8) return;
        event.setCancelled(true);
        if (player.getGameMode() != GameMode.CREATIVE) {
            if (held.getAmount() == 1) player.getInventory().setItemInMainHand(null);
            else held.setAmount(held.getAmount() - 1);
        }
        double chance = Math.max(0, Math.min(1, getConfig().getDouble("poisonous-potatoes.composting.chance", 0.65)));
        boolean success = ThreadLocalRandom.current().nextDouble() < chance;
        if (success) {
            level.setLevel(level.getLevel() + 1);
            block.setBlockData(level);
        }
        block.getWorld().playEffect(block.getLocation(), org.bukkit.Effect.COMPOSTER_FILL_ATTEMPT,
                success ? 1 : 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStandOpen(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.BREWING_STAND)
            watchedStands.add(event.getClickedBlock().getLocation());
    }

    // Vanilla BrewingStandMenu rejects poisonous potatoes in its ingredient slot.
    // Handle cursor placement ourselves, before vanilla slot validation.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotatoIngredientClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!getConfig().getBoolean("poisonous-potatoes.brewing.enabled", true)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof org.bukkit.block.BrewingStand stand)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        watchedStands.add(stand.getLocation());
        org.bukkit.inventory.Inventory top = event.getView().getTopInventory();
        // Brewing stand slots: 0-2 bottles, 3 ingredient, 4 fuel.
        if (event.getRawSlot() == 3) {
            ItemStack cursor = event.getCursor();
            if (cursor == null || cursor.getType() != Material.POISONOUS_POTATO) return;
            if (event.getClick() != org.bukkit.event.inventory.ClickType.LEFT
                    && event.getClick() != org.bukkit.event.inventory.ClickType.RIGHT) return;
            ItemStack existing = top.getItem(3);
            if (existing != null && !existing.isEmpty() && !existing.isSimilar(cursor)) return;
            int space = existing == null || existing.isEmpty() ? cursor.getMaxStackSize()
                    : existing.getMaxStackSize() - existing.getAmount();
            if (space <= 0) return;
            int moved = event.getClick() == org.bukkit.event.inventory.ClickType.RIGHT ? 1
                    : Math.min(space, cursor.getAmount());
            event.setCancelled(true);
            ItemStack placed = cursor.clone();
            placed.setAmount((existing == null || existing.isEmpty() ? 0 : existing.getAmount()) + moved);
            top.setItem(3, placed);
            ItemStack remainder = cursor.clone();
            remainder.setAmount(cursor.getAmount() - moved);
            player.setItemOnCursor(remainder.getAmount() == 0 ? null : remainder);
            return;
        }
        // Shift-click from player inventory: route poisonous potatoes into ingredient slot.
        if (event.getRawSlot() >= top.getSize() && event.isShiftClick()
                && event.getCurrentItem() != null
                && event.getCurrentItem().getType() == Material.POISONOUS_POTATO) {
            ItemStack source = event.getCurrentItem();
            ItemStack existing = top.getItem(3);
            if (existing != null && !existing.isEmpty() && !existing.isSimilar(source)) return;
            int space = existing == null || existing.isEmpty() ? source.getMaxStackSize()
                    : existing.getMaxStackSize() - existing.getAmount();
            if (space <= 0) return;
            event.setCancelled(true);
            int moved = Math.min(space, source.getAmount());
            ItemStack placed = source.clone();
            placed.setAmount((existing == null || existing.isEmpty() ? 0 : existing.getAmount()) + moved);
            top.setItem(3, placed);
            ItemStack remainder = source.clone();
            remainder.setAmount(source.getAmount() - moved);
            event.setCurrentItem(remainder.getAmount() == 0 ? null : remainder);
        }
    }

    // Dragging a potato across the ingredient slot is blocked by vanilla; handle
    // a drag that targets only the ingredient slot, without altering other slots.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotatoIngredientDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!getConfig().getBoolean("poisonous-potatoes.brewing.enabled", true)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof org.bukkit.block.BrewingStand stand)) return;
        if (!event.getRawSlots().equals(java.util.Set.of(3))) return;
        ItemStack cursor = event.getOldCursor();
        if (cursor == null || cursor.getType() != Material.POISONOUS_POTATO) return;
        ItemStack existing = event.getView().getTopInventory().getItem(3);
        if (existing != null && !existing.isEmpty()) return;
        ItemStack placed = event.getNewItems().get(3);
        if (placed == null || placed.getType() != Material.POISONOUS_POTATO) return;
        event.setCancelled(true);
        watchedStands.add(stand.getLocation());
        // InventoryDragEvent forbids inventory modifications in-handler.
        ItemStack placedCopy = placed.clone();
        ItemStack cursorCopy = event.getCursor() == null ? null : event.getCursor().clone();
        getServer().getScheduler().runTask(this, () -> {
            if (!(event.getWhoClicked() instanceof Player player) || !player.isOnline()) return;
            event.getView().getTopInventory().setItem(3, placedCopy);
            player.setItemOnCursor(cursorCopy);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStandInventory(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof org.bukkit.block.BrewingStand stand)
            watchedStands.add(stand.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperMove(org.bukkit.event.inventory.InventoryMoveItemEvent event) {
        if (event.getDestination().getHolder() instanceof org.bukkit.block.BrewingStand stand)
            watchedStands.add(stand.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStandPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.BREWING_STAND)
            watchedStands.add(event.getBlockPlaced().getLocation());
    }

    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        for (org.bukkit.block.BlockState state : event.getChunk().getTileEntities()) {
            if (state instanceof org.bukkit.block.BrewingStand) watchedStands.add(state.getLocation());
        }
    }

    private boolean awkward(ItemStack item) {
        return item != null && item.getType() == Material.POTION
                && item.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta meta
                && meta.getBasePotionType() == org.bukkit.potion.PotionType.AWKWARD;
    }

    // Send the brewing GUI property to viewers only. Do not update the brewing
    // block state: Minecraft does not recognize poisonous potatoes as an
    // ingredient and can reset the native timer or overwrite custom results.
    private void showCustomProgress(org.bukkit.block.BrewingStand stand, int ticks) {
        for (org.bukkit.entity.HumanEntity viewer : stand.getInventory().getViewers()) {
            if (!(viewer instanceof Player player)) continue;
            org.bukkit.inventory.InventoryView view = player.getOpenInventory();
            if (view.getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.BREWING) continue;
            if (!view.getTopInventory().equals(stand.getInventory())) continue;
            try {
                // Bukkit exposes this client-only container property on HumanEntity.
                // Reflective invocation tolerates API variants where the method
                // moved between HumanEntity and InventoryView.
                Class<?> propertyType = Class.forName("org.bukkit.inventory.InventoryView$Property");
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object brewTime = java.lang.Enum.valueOf((Class<? extends Enum>) propertyType.asSubclass(Enum.class), "BREW_TIME");
                try {
                    player.getClass().getMethod("setWindowProperty", propertyType, int.class)
                            .invoke(player, brewTime, ticks);
                } catch (NoSuchMethodException missingOnPlayer) {
                    view.getClass().getMethod("setProperty", propertyType, int.class)
                            .invoke(view, brewTime, ticks);
                }
            } catch (ReflectiveOperationException | IllegalArgumentException ex) {
                if (!warnedProgressApi) {
                    warnedProgressApi = true;
                    getLogger().warning("Could not send brewing progress to client via Bukkit inventory API: " + ex);
                }
            }
        }
    }

    private void tickBrewing() {
        if (!getConfig().getBoolean("poisonous-potatoes.brewing.enabled", true)) {
            customBrews.clear();
            return;
        }
        java.util.Iterator<org.bukkit.Location> iterator = watchedStands.iterator();
        while (iterator.hasNext()) {
            org.bukkit.Location location = iterator.next();
            if (location.getWorld() == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                customBrews.remove(location); iterator.remove(); continue;
            }
            if (!(location.getBlock().getState() instanceof org.bukkit.block.BrewingStand stand)) {
                customBrews.remove(location); iterator.remove(); continue;
            }
            org.bukkit.inventory.BrewerInventory inv = stand.getInventory();
            ItemStack ingredient = inv.getIngredient();
            boolean potato = ingredient != null && ingredient.getType() == Material.POISONOUS_POTATO;
            boolean hasAwkward = false;
            for (int slot = 0; slot < 3; slot++) if (awkward(inv.getItem(slot))) hasAwkward = true;
            if (!potato || !hasAwkward) {
                if (customBrews.remove(location) != null) showCustomProgress(stand, 0);
                continue;
            }
            Integer remaining = customBrews.get(location);
            if (remaining == null) {
                // Leave vanilla recipes and their animation entirely alone.
                if (stand.getBrewingTime() > 0) continue;
                int fuel = stand.getFuelLevel();
                if (fuel <= 0) {
                    ItemStack fuelStack = inv.getFuel();
                    if (fuelStack == null || fuelStack.getType() != Material.BLAZE_POWDER) continue;
                    if (fuelStack.getAmount() == 1) inv.setFuel(null);
                    else fuelStack.setAmount(fuelStack.getAmount() - 1);
                    fuel = 20;
                }
                // Fuel-level changes require an update to the block state, but
                // must happen BEFORE potion inventory writes.
                stand.setFuelLevel(fuel - 1);
                stand.update(false, false);
                remaining = 400;
            }
            remaining--;
            if (remaining > 0) {
                customBrews.put(location, remaining);
                showCustomProgress(stand, remaining);
                continue;
            }
            customBrews.remove(location);
            // Do not update the block state here: the live inventory must not
            // be overwritten by a stale BlockState snapshot.
            inv = ((org.bukkit.block.BrewingStand) location.getBlock().getState()).getInventory();
            ingredient = inv.getIngredient();
            if (ingredient == null || ingredient.getType() != Material.POISONOUS_POTATO) {
                showCustomProgress(stand, 0);
                continue;
            }
            int converted = 0;
            for (int slot = 0; slot < 3; slot++) {
                ItemStack potion = inv.getItem(slot);
                if (!awkward(potion)) continue;
                ItemStack result = potion.clone();
                org.bukkit.inventory.meta.PotionMeta meta = (org.bukkit.inventory.meta.PotionMeta) result.getItemMeta();
                meta.setBasePotionType(org.bukkit.potion.PotionType.POISON);
                result.setItemMeta(meta);
                inv.setItem(slot, result);
                converted++;
            }
            if (converted > 0) {
                if (ingredient.getAmount() == 1) inv.setIngredient(null);
                else ingredient.setAmount(ingredient.getAmount() - 1);
                location.getWorld().playEffect(location, org.bukkit.Effect.BREWING_STAND_BREW, 0);
            }
            showCustomProgress(stand, 0);
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("Usage: /" + label + " reload");
            return true;
        }
        if (!sender.hasPermission("reapwright.admin")) {
            sender.sendMessage("You do not have permission.");
            return true;
        }
        loadSettings();
        sender.sendMessage("Reapwright configuration reloaded.");
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("reapwright.use") || player.getGameMode() == GameMode.SPECTATOR) return;
        Block block = event.getClickedBlock();
        if (block == null || !enabledCrops.contains(block.getType())) return;
        if (!(block.getBlockData() instanceof Ageable crop) || crop.getAge() < crop.getMaximumAge()) return;

        PlayerInventory inventory = player.getInventory();
        ItemStack hoe = inventory.getItemInMainHand();
        if (!allowedTools.contains(hoe.getType())) return;

        // Select a square independently of the player's facing direction.
        // For even widths, evaluate all possible offsets containing the clicked crop
        // and choose the square with the most mature, connected crops of the same type.
        // The clicked crop is always the first harvested.
        List<Block> candidates = selectConnectedArea(block, harvestWidth);
        int harvested = 0;
        for (Block candidate : candidates) {
            if (!allowedTools.contains(inventory.getItemInMainHand().getType())) break;
            if (!(candidate.getBlockData() instanceof Ageable candidateAge)
                    || candidateAge.getAge() < candidateAge.getMaximumAge()) continue;
            if (harvestOne(player, inventory, hoe, candidate, candidateAge)) harvested++;
        }
        if (harvested > 0) event.setCancelled(true);
    }

    /**
     * Find the size-by-size square containing the clicked crop that captures the
     * greatest number of orthogonally connected, mature crops of its type.
     * Every possible square origin is considered, for both odd and even sizes.
     * Ties prefer the square whose center is nearest the clicked crop, followed
     * by a deterministic offset order. The clicked crop is always first.
     */
    private List<Block> selectConnectedArea(Block origin, int size) {
        Material type = origin.getType();
        List<Block> best = List.of(origin);
        int bestCenterDistance = Integer.MAX_VALUE;
        org.bukkit.block.BlockFace[] directions = {
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.EAST,
                org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.WEST
        };

        // For a square to contain the clicked crop, its lower offset along each
        // axis must be in [1 - size, 0]. This applies equally to odd/even sizes.
        for (int startX = 1 - size; startX <= 0; startX++) {
            for (int startZ = 1 - size; startZ <= 0; startZ++) {
                Set<Block> eligible = new java.util.HashSet<>();
                for (int x = startX; x < startX + size; x++) {
                    for (int z = startZ; z < startZ + size; z++) {
                        Block candidate = origin.getRelative(x, 0, z);
                        if (candidate.getType() != type) continue;
                        if (candidate.getBlockData() instanceof Ageable age
                                && age.getAge() == age.getMaximumAge()) eligible.add(candidate);
                    }
                }

                if (!eligible.remove(origin)) continue;
                List<Block> connected = new ArrayList<>();
                java.util.ArrayDeque<Block> queue = new java.util.ArrayDeque<>();
                queue.add(origin);
                while (!queue.isEmpty()) {
                    Block current = queue.removeFirst();
                    connected.add(current);
                    for (org.bukkit.block.BlockFace direction : directions) {
                        Block neighbor = current.getRelative(direction);
                        if (eligible.remove(neighbor)) queue.addLast(neighbor);
                    }
                }

                // Doubled coordinates avoid floating-point arithmetic for even sizes.
                int centerX2 = 2 * startX + size - 1;
                int centerZ2 = 2 * startZ + size - 1;
                int centerDistance = centerX2 * centerX2 + centerZ2 * centerZ2;
                if (connected.size() > best.size()
                        || (connected.size() == best.size() && centerDistance < bestCenterDistance)) {
                    best = connected;
                    bestCenterDistance = centerDistance;
                }
            }
        }
        return best;
    }

    private boolean harvestOne(Player player, PlayerInventory inventory, ItemStack hoe, Block block, Ageable crop) {
        Material replantItem = switch (block.getType()) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            case NETHER_WART -> Material.NETHER_WART;
            default -> null;
        };
        if (replantItem == null) return false;

        // Query the server's native block loot logic with the actual enchanted hoe.
        Collection<ItemStack> rawDrops = block.getDrops(hoe, player);
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack drop : rawDrops) drops.add(drop.clone());

        // Vanilla Fortune boosts seeds for wheat and beetroot, not the edible crop.
        // Add 0..Fortune-level extra produce, preserving native seed drops.
        if (getConfig().getBoolean("fortune-boost-produce", true)) {
            Material produce = switch (block.getType()) {
                case WHEAT -> Material.WHEAT;
                case BEETROOTS -> Material.BEETROOT;
                default -> null;
            };
            int fortune = hoe.getEnchantmentLevel(Enchantment.FORTUNE);
            if (produce != null && fortune > 0) {
                int bonus = ThreadLocalRandom.current().nextInt(fortune + 1);
                if (bonus > 0) drops.add(new ItemStack(produce, bonus));
            }
        }

        // Reserve a replanting item from harvested drops, before using inventory.
        boolean paid = takeOne(drops, replantItem);
        boolean fromInventory = false;
        if (!paid && getConfig().getBoolean("use-inventory-for-replanting", true)) {
            fromInventory = inventory.containsAtLeast(new ItemStack(replantItem), 1);
            paid = fromInventory;
        }
        if (!paid) return false; // No free replanting; leave the crop untouched.
        if (fromInventory && !removeOne(inventory, replantItem)) return false;
        Ageable replanted = (Ageable) crop.clone();
        replanted.setAge(0);
        block.setBlockData(replanted, true);
        for (ItemStack drop : drops) {
            if (getConfig().getBoolean("insert-drops-into-inventory", true)) {
                Map<Integer, ItemStack> overflow = inventory.addItem(drop);
                for (ItemStack extra : overflow.values())
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), extra);
            } else {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
            }
        }
        if (getConfig().getBoolean("consume-durability", true) && player.getGameMode() != GameMode.CREATIVE)
            damageHoe(player, hoe);
        return true;
    }


    private boolean takeOne(List<ItemStack> drops, Material material) {
        for (int i = 0; i < drops.size(); i++) {
            ItemStack stack = drops.get(i);
            if (stack.getType() == material && stack.getAmount() > 0) {
                if (stack.getAmount() == 1) drops.remove(i);
                else stack.setAmount(stack.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    private boolean removeOne(PlayerInventory inventory, Material material) {
        // Remove from storage only; don't accidentally consume the held hoe or armor.
        for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() == material && item.getAmount() > 0) {
                if (item.getAmount() == 1) inventory.setItem(slot, null);
                else { item.setAmount(item.getAmount() - 1); inventory.setItem(slot, item); }
                return true;
            }
        }
        return false;
    }

    private void damageHoe(Player player, ItemStack hoe) {
        int unbreaking = hoe.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) return;
        if (!(hoe.getItemMeta() instanceof Damageable damageable)) return;
        int newDamage = damageable.getDamage() + 1;
        if (newDamage >= hoe.getType().getMaxDurability()) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        damageable.setDamage(newDamage);
        hoe.setItemMeta(damageable);
        player.getInventory().setItemInMainHand(hoe);
    }
}
