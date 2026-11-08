package pl.prison.laundry;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Praca "Pralnia" na serwer więzienny.
 *
 * 1. Skrzynia ZRODLOWA  - nieskończony zapas ciemnoszarych części skórzanej zbroi.
 * 2. Gracz farbuje je na biało (kafelek craftingu / kocioł + barwnik).
 * 3. Skrzynia ODBIORU   - gracz wkłada białe ubrania, po X sekundach dostaje wiadomość na czacie
 *                          i może je wyjąć. Zawartość jest osobna dla każdego gracza.
 */
public final class LaundryPlugin extends JavaPlugin implements Listener {

    private enum Kind { SOURCE, OUTPUT }

    private enum State { EMPTY, WASHING, READY }

    private static final int OUT_SIZE = 27;

    private static final Material[] ARMOR = {
            Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE,
            Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS
    };

    /** Akcje, które tylko zabierają przedmioty ze skrzyni. */
    private static final Set<InventoryAction> TAKE = EnumSet.of(
            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_ONE, InventoryAction.PICKUP_SOME,
            InventoryAction.MOVE_TO_OTHER_INVENTORY,
            InventoryAction.DROP_ONE_SLOT, InventoryAction.DROP_ALL_SLOT);

    /** Akcje, które wkładają przedmiot do klikniętego slotu. */
    private static final Set<InventoryAction> PLACE = EnumSet.of(
            InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE,
            InventoryAction.PLACE_SOME, InventoryAction.SWAP_WITH_CURSOR,
            InventoryAction.HOTBAR_SWAP);

    private static final class LaundryHolder implements InventoryHolder {
        final Kind kind;
        final UUID owner;
        Inventory inventory;

        LaundryHolder(Kind kind, UUID owner) {
            this.kind = kind;
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class Session {
        State state = State.EMPTY;
        ItemStack[] items = new ItemStack[OUT_SIZE];
        long readyAt;
        BukkitTask task;
    }

    private record BlockPos(String world, int x, int y, int z) {
        static BlockPos of(Block b) {
            return new BlockPos(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> openOutput = new HashSet<>();

    private File dataFile;
    private BlockPos sourcePos;
    private BlockPos outputPos;

    private Color srcColor;
    private Color dstColor;
    private int tolerance;
    private int washTicks;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        loadSettings();
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // zamknij otwarte skrzynie odbioru, żeby zapisać ich zawartość
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (openOutput.contains(p.getUniqueId())) {
                p.closeInventory();
            }
        }
        for (Session s : sessions.values()) {
            if (s.task != null) s.task.cancel();
        }
        saveData();
    }

    private void loadSettings() {
        reloadConfig();
        srcColor = parseColor(getConfig().getString("colors.source", "474F52"), Color.GRAY);
        dstColor = parseColor(getConfig().getString("colors.target", "F9FFFE"), Color.WHITE);
        tolerance = Math.max(0, getConfig().getInt("colors.tolerance", 20));
        washTicks = Math.max(1, getConfig().getInt("wash-seconds", 3)) * 20;
    }

    private Color parseColor(String hex, Color fallback) {
        try {
            return Color.fromRGB(Integer.parseInt(hex.replace("#", "").trim(), 16) & 0xFFFFFF);
        } catch (NumberFormatException ex) {
            getLogger().warning("Niepoprawny kolor '" + hex + "', używam domyślnego.");
            return fallback;
        }
    }

    // ------------------------------------------------------------------ messages

    private String raw(String key) {
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages." + key, key));
    }

    private String msg(String key) {
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.prefix", ""))
                + raw(key);
    }

    // ------------------------------------------------------------------ items

    private ItemStack makeUniform(Material type) {
        ItemStack item = new ItemStack(type);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(srcColor);
        item.setItemMeta(meta);
        return item;
    }

    private static boolean isEmpty(ItemStack it) {
        return it == null || it.getType().isAir();
    }

    /** Czy to część skórzanej zbroi w kolorze białym (z tolerancją)? */
    private boolean isCleanUniform(ItemStack it) {
        if (isEmpty(it)) return false;
        boolean armor = false;
        for (Material m : ARMOR) {
            if (m == it.getType()) {
                armor = true;
                break;
            }
        }
        if (!armor) return false;
        if (!(it.getItemMeta() instanceof LeatherArmorMeta meta)) return false;
        Color c = meta.getColor();
        return Math.abs(c.getRed() - dstColor.getRed()) <= tolerance
                && Math.abs(c.getGreen() - dstColor.getGreen()) <= tolerance
                && Math.abs(c.getBlue() - dstColor.getBlue()) <= tolerance;
    }

    // ------------------------------------------------------------------ opening GUIs

    private void openSource(Player p) {
        LaundryHolder holder = new LaundryHolder(Kind.SOURCE, p.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, 9, raw("title-source"));
        holder.inventory = inv;
        for (int i = 0; i < ARMOR.length; i++) {
            inv.setItem(2 + i, makeUniform(ARMOR[i]));
        }
        p.openInventory(inv);
    }

    private void openOutput(Player p) {
        Session s = sessions.computeIfAbsent(p.getUniqueId(), k -> new Session());
        LaundryHolder holder = new LaundryHolder(Kind.OUTPUT, p.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, OUT_SIZE, raw("title-output"));
        holder.inventory = inv;
        inv.setContents(s.items);
        openOutput.add(p.getUniqueId());
        p.openInventory(inv);
        if (s.state == State.WASHING) {
            p.sendMessage(msg("washing").replace("{s}", String.valueOf(remainingSeconds(s))));
        }
    }

    private int remainingSeconds(Session s) {
        return Math.max(1, (int) Math.ceil((s.readyAt - System.currentTimeMillis()) / 1000.0));
    }

    // ------------------------------------------------------------------ washing

    private void startWashing(UUID id, Session s) {
        s.state = State.WASHING;
        s.readyAt = System.currentTimeMillis() + washTicks * 50L;
        s.task = Bukkit.getScheduler().runTaskLater(this, () -> finishWashing(id), washTicks);
        saveData();
    }

    private void finishWashing(UUID id) {
        Session s = sessions.get(id);
        if (s == null || s.state != State.WASHING) return;
        s.state = State.READY;
        s.task = null;
        saveData();
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            p.sendMessage(msg("ready"));
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        BlockPos pos = BlockPos.of(b);
        boolean isSource = pos.equals(sourcePos);
        boolean isOutput = pos.equals(outputPos);
        if (!isSource && !isOutput) return;

        e.setCancelled(true); // nie otwieraj prawdziwej skrzyni
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (isSource) openSource(e.getPlayer());
        else openOutput(e.getPlayer());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        BlockPos pos = BlockPos.of(e.getBlock());
        if ((pos.equals(sourcePos) || pos.equals(outputPos)) && !e.getPlayer().hasPermission("pralnia.admin")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(msg("no-break"));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s != null && s.state == State.READY) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) p.sendMessage(msg("ready-reminder"));
            }, 40L);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getInventory();
        if (!(top.getHolder() instanceof LaundryHolder holder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null) return; // klik poza oknem

        boolean topClick = e.getRawSlot() < top.getSize();
        InventoryAction action = e.getAction();

        // ---- skrzynia źródłowa: tylko do podglądu / pobierania wzorów
        if (holder.kind == Kind.SOURCE) {
            e.setCancelled(true);
            ItemStack clicked = e.getCurrentItem();
            if (topClick && !isEmpty(clicked)) {
                ItemStack give = makeUniform(clicked.getType());
                if (!p.getInventory().addItem(give).isEmpty()) {
                    p.sendMessage(msg("inventory-full"));
                }
            }
            return;
        }

        // ---- skrzynia odbioru
        Session s = sessions.computeIfAbsent(holder.owner, k -> new Session());

        switch (s.state) {
            case WASHING -> {
                if (topClick || action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                        || action == InventoryAction.COLLECT_TO_CURSOR) {
                    e.setCancelled(true);
                    p.sendMessage(msg("washing").replace("{s}", String.valueOf(remainingSeconds(s))));
                }
            }
            case READY -> {
                // można tylko zabierać
                if (topClick) {
                    if (!TAKE.contains(action)) e.setCancelled(true);
                } else if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                    e.setCancelled(true);
                }
            }
            case EMPTY -> {
                if (topClick) {
                    if (PLACE.contains(action)) {
                        ItemStack incoming = action == InventoryAction.HOTBAR_SWAP
                                ? hotbarItem(p, e)
                                : e.getCursor();
                        // HOTBAR_SWAP z pustym slotem = zwykłe zabranie
                        boolean pureTake = action == InventoryAction.HOTBAR_SWAP && isEmpty(incoming);
                        if (!pureTake && !isCleanUniform(incoming)) {
                            e.setCancelled(true);
                            p.sendMessage(msg("only-clean"));
                        }
                    } else if (!TAKE.contains(action)) {
                        e.setCancelled(true);
                    }
                } else if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                    if (!isCleanUniform(e.getCurrentItem())) {
                        e.setCancelled(true);
                        p.sendMessage(msg("only-clean"));
                    }
                }
            }
        }
    }

    private ItemStack hotbarItem(Player p, InventoryClickEvent e) {
        if (e.getClick() == ClickType.SWAP_OFFHAND) {
            return p.getInventory().getItemInOffHand();
        }
        int button = e.getHotbarButton();
        return button < 0 ? null : p.getInventory().getItem(button);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getInventory();
        if (!(top.getHolder() instanceof LaundryHolder holder)) return;
        boolean touchesTop = e.getRawSlots().stream().anyMatch(i -> i < top.getSize());
        if (!touchesTop) return;

        if (holder.kind == Kind.SOURCE) {
            e.setCancelled(true);
            return;
        }
        Session s = sessions.computeIfAbsent(holder.owner, k -> new Session());
        if (s.state != State.EMPTY || !isCleanUniform(e.getOldCursor())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof LaundryHolder holder)) return;
        if (holder.kind != Kind.OUTPUT) return;

        openOutput.remove(holder.owner);
        Session s = sessions.get(holder.owner);
        if (s == null || s.state == State.WASHING) return;

        ItemStack[] contents = e.getInventory().getContents();
        s.items = new ItemStack[OUT_SIZE];
        boolean has = false;
        for (int i = 0; i < contents.length && i < OUT_SIZE; i++) {
            s.items[i] = contents[i];
            if (!isEmpty(contents[i])) has = true;
        }

        if (s.state == State.EMPTY && has) {
            startWashing(holder.owner, s);
            if (e.getPlayer() instanceof Player p) {
                p.sendMessage(msg("started").replace("{seconds}", String.valueOf(washTicks / 20)));
            }
        } else if (s.state == State.READY && !has) {
            s.state = State.EMPTY;
            saveData();
        } else {
            saveData();
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("pralnia.admin")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " <setsource|setoutput|reload>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                loadSettings();
                sender.sendMessage(msg("reloaded"));
            }
            case "setsource", "setoutput" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Tę komendę może użyć tylko gracz.");
                    return true;
                }
                Block b = p.getTargetBlockExact(6);
                if (b == null || !(b.getState() instanceof Container)) {
                    p.sendMessage(msg("look-at-chest"));
                    return true;
                }
                BlockPos pos = BlockPos.of(b);
                if (args[0].equalsIgnoreCase("setsource")) {
                    sourcePos = pos;
                    p.sendMessage(msg("source-set"));
                } else {
                    outputPos = pos;
                    p.sendMessage(msg("output-set"));
                }
                saveData();
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/" + label + " <setsource|setoutput|reload>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("pralnia.admin")) {
            return List.of("setsource", "setoutput", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    // ------------------------------------------------------------------ persistence

    private void writePos(YamlConfiguration d, String key, BlockPos pos) {
        if (pos == null) return;
        d.set(key + ".world", pos.world());
        d.set(key + ".x", pos.x());
        d.set(key + ".y", pos.y());
        d.set(key + ".z", pos.z());
    }

    private BlockPos readPos(YamlConfiguration d, String key) {
        if (!d.isConfigurationSection(key)) return null;
        return new BlockPos(d.getString(key + ".world", "world"),
                d.getInt(key + ".x"), d.getInt(key + ".y"), d.getInt(key + ".z"));
    }

    private void saveData() {
        YamlConfiguration d = new YamlConfiguration();
        writePos(d, "source", sourcePos);
        writePos(d, "output", outputPos);
        for (Map.Entry<UUID, Session> en : sessions.entrySet()) {
            Session s = en.getValue();
            List<ItemStack> list = new ArrayList<>();
            for (ItemStack it : s.items) {
                if (!isEmpty(it)) list.add(it);
            }
            if (list.isEmpty() && s.state == State.EMPTY) continue;
            String base = "players." + en.getKey();
            d.set(base + ".state", s.state.name());
            d.set(base + ".items", list);
        }
        try {
            d.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Nie udało się zapisać data.yml: " + ex.getMessage());
        }
    }

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration d = YamlConfiguration.loadConfiguration(dataFile);
        sourcePos = readPos(d, "source");
        outputPos = readPos(d, "output");

        ConfigurationSection players = d.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Session s = new Session();
                State saved = State.valueOf(players.getString(key + ".state", "EMPTY"));
                int i = 0;
                for (Object o : players.getList(key + ".items", List.of())) {
                    if (o instanceof ItemStack item && i < OUT_SIZE) s.items[i++] = item;
                }
                if (i == 0) continue;
                // pranie przerwane restartem traktujemy jako zakończone
                s.state = saved == State.WASHING ? State.READY : saved;
                sessions.put(id, s);
            } catch (IllegalArgumentException ignored) {
                // uszkodzony wpis - pomijamy
            }
        }
    }
}
