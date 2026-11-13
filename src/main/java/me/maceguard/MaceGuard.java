package me.maceguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MaceGuard extends JavaPlugin implements Listener {

    /** Entities that can take an item out of a player's hand. */
    private static final Set<String> STASH_ENTITIES = Set.of(
            "ITEM_FRAME", "GLOW_ITEM_FRAME", "ARMOR_STAND", "ALLAY", "MANNEQUIN", "COPPER_GOLEM");

    private NamespacedKey maceKey;
    private final Set<UUID> warned = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // The same key RiftboundMaces writes on every mace item
        maceKey = NamespacedKey.fromString("riftboundmaces:mace_id");
        if (maceKey == null) {
            getLogger().severe("Could not build the mace key. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        startChecker();
    }

    private void startChecker() {
        long ticks = Math.max(2, getConfig().getInt("check-interval-seconds", 10)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::checkAll, 100L, ticks);
    }

    // ------------------------------------------------------------ helpers

    private boolean isMace(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(maceKey);
    }

    private boolean bypass(Player p) {
        return p.hasPermission("maceguard.bypass");
    }

    private boolean hotbarHasMace(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (isMace(inv.getItem(i))) return true;
        }
        return isMace(inv.getItemInOffHand());
    }

    private static boolean isStashBlock(String type) {
        return type.endsWith("_SHELF")
                || type.equals("CHISELED_BOOKSHELF")
                || type.equals("DECORATED_POT")
                || type.equals("LECTERN")
                || type.equals("JUKEBOX")
                || type.equals("FLOWER_POT")
                || type.equals("BREWING_STAND")
                || type.endsWith("CAMPFIRE");
    }

    private void deny(Player p) {
        p.sendActionBar(Component.text("You can't store your mace there.", NamedTextColor.RED));
    }

    // ------------------------------------------------------------ blocks (shelves etc.)

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || !isStashBlock(b.getType().name())) return;
        Player p = e.getPlayer();
        if (bypass(p)) return;

        // 1) putting the mace you are holding into the block
        boolean holding = isMace(e.getItem());
        // 2) powered shelves swap with your whole hotbar, so any mace in the hotbar counts
        boolean poweredSwap = (b.getBlockPower() > 0 || b.isBlockIndirectlyPowered()) && hotbarHasMace(p);

        if (holding || poweredSwap) {
            e.setCancelled(true);
            deny(p);
        }
    }

    // ------------------------------------------------------------ entities (frames, allay, ...)

    private void handleEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (bypass(p)) return;
        if (!isMace(p.getInventory().getItem(e.getHand()))) return;
        if (!STASH_ENTITIES.contains(e.getRightClicked().getType().name().toUpperCase(Locale.ROOT))) return;
        e.setCancelled(true);
        deny(p);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        handleEntity(e);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityInteractAt(PlayerInteractAtEntityEvent e) {
        handleEntity(e);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        Player p = e.getPlayer();
        if (bypass(p)) return;
        if (isMace(e.getPlayerItem()) || isMace(e.getArmorStandItem())) {
            e.setCancelled(true);
            deny(p);
        }
    }

    // ------------------------------------------------------------ death: never drop a mace

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isMace);
    }

    // ------------------------------------------------------------ duplicate maces

    private void checkAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (bypass(p)) continue;
            checkPlayer(p);
        }
    }

    private int enchantCount(ItemStack item) {
        return item.getEnchantments().size();
    }

    private void checkPlayer(Player p) {
        PlayerInventory inv = p.getInventory();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isMace(inv.getItem(i))) slots.add(i);
        }
        if (slots.size() <= 1) {
            warned.remove(p.getUniqueId());
            return;
        }

        String policy = getConfig().getString("duplicate-policy", "off").toLowerCase(Locale.ROOT);
        if (!policy.equals("keep-fresh") && !policy.equals("keep-enchanted")) {
            if (warned.add(p.getUniqueId())) {
                getLogger().warning(p.getName() + " is holding " + slots.size()
                        + " maces at once (duplicate-policy is off, nothing removed).");
            }
            return;
        }

        int keep = slots.get(0);
        for (int slot : slots) {
            int a = enchantCount(inv.getItem(slot));
            int b = enchantCount(inv.getItem(keep));
            if (policy.equals("keep-fresh") ? a < b : a > b) keep = slot;
        }
        int removed = 0;
        for (int slot : slots) {
            if (slot != keep) {
                inv.setItem(slot, null);
                removed++;
            }
        }
        p.sendMessage("\u00a7cYou were holding more than one mace, so " + removed + " extra was removed.");
        getLogger().warning("Removed " + removed + " extra mace(s) from " + p.getName() + " (" + policy + ").");
    }

    // ------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("\u00a7aMaceGuard config reloaded.");
            return true;
        }
        sender.sendMessage("\u00a7eUsage: /maceguard reload");
        return true;
    }
}
