package clre20.vaultBank;

import clre20.vaultBank.command.Commands;
import clre20.vaultBank.config.Messages;
import clre20.vaultBank.config.PluginConfig;
import clre20.vaultBank.currency.Alerts;
import clre20.vaultBank.currency.Cheques;
import clre20.vaultBank.currency.Circulation;
import clre20.vaultBank.currency.CurrencyItems;
import clre20.vaultBank.currency.Signer;
import clre20.vaultBank.currency.Teller;
import clre20.vaultBank.economy.Baltop;
import clre20.vaultBank.economy.Bank;
import clre20.vaultBank.economy.Interest;
import clre20.vaultBank.economy.TheosisImporter;
import clre20.vaultBank.economy.VaultEconomy;
import clre20.vaultBank.gui.ChatInput;
import clre20.vaultBank.gui.MenuListener;
import clre20.vaultBank.pack.PackService;
import clre20.vaultBank.service.Actions;
import clre20.vaultBank.storage.Database;
import clre20.vaultBank.update.UpdateChecker;
import clre20.vaultBank.update.UpdateNotifier;
import clre20.vaultBank.util.Money;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class VaultBank extends JavaPlugin implements Listener {

    private final PluginConfig config = new PluginConfig(this);
    private final Messages messages = new Messages(this);
    private Database database;
    private Bank bank;
    private Baltop baltop;
    private Interest interest;
    private Circulation circulation;
    private Cheques cheques;
    private CurrencyItems items;
    private Teller teller;
    private Alerts alerts;
    private Actions actions;
    private ChatInput chatInput;
    private PackService packs;
    private UpdateChecker updates;
    private UpdateNotifier updateNotifier;
    private VaultEconomy vaultEconomy;
    private Signer signer;

    private volatile boolean economyActive;
    private boolean loadFailed;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final java.util.Map<java.util.UUID, Long> cooldowns = new java.util.HashMap<>();

    // ---------- 生命週期 ----------

    /**
     * 在 onLoad 就載入資料並向 Vault 註冊，比其他插件的 onEnable 早，
     * 那些在啟用時就抓經濟提供者的插件也能抓到 VaultBank。
     */
    @Override
    public void onLoad() {
        try {
            if (!getDataFolder().isDirectory() && !getDataFolder().mkdirs()) {
                throw new IllegalStateException("無法建立資料夾 " + getDataFolder());
            }
            config.load();
            messages.load();
            signer = Signer.load(new File(getDataFolder(), "secret.key"), getLogger());
            database = new Database(new File(getDataFolder(), "data.db"), getLogger());
            database.open();
            bank = new Bank(this, database);
            bank.load();
            circulation = new Circulation(this, database);
            circulation.load();
            cheques = new Cheques(database);
            cheques.load();
            TheosisImporter.runIfNeeded(this, database, bank);
            vaultEconomy = new VaultEconomy(this);
            getServer().getServicesManager().register(Economy.class, vaultEconomy, this, ServicePriority.High);
            economyActive = true;
        } catch (Throwable t) {
            loadFailed = true;
            economyActive = false;
            getLogger().log(Level.SEVERE, "VaultBank 載入失敗，經濟功能不會啟用", t);
        }
    }

    @Override
    public void onEnable() {
        if (loadFailed) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getServer().getPluginManager().getPlugin("TheosisEconomy") != null) {
            // 兩個經濟插件同時改餘額會出錯：VaultBank 退出 Vault，只保留指令顯示提示
            economyActive = false;
            getServer().getServicesManager().unregister(Economy.class, vaultEconomy);
            getLogger().severe(messages.console("theosis-installed"));
        }

        updates = new UpdateChecker(getPluginMeta().getVersion());
        updateNotifier = new UpdateNotifier(this);
        baltop = new Baltop(this);
        interest = new Interest(this, database);
        alerts = new Alerts(this);
        items = new CurrencyItems(this, signer);
        items.rebuild();
        teller = new Teller(this);
        actions = new Actions(this);
        chatInput = new ChatInput(this);
        packs = new PackService(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(chatInput, this);
        pm.registerEvents(packs, this);
        pm.registerEvents(updateNotifier, this);

        Commands commands = new Commands(this);
        for (String name : new String[]{"vaultbank", "money", "eco", "baltop", "pay"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(commands);
                cmd.setTabCompleter(commands);
            }
        }

        for (var p : Bukkit.getOnlinePlayers()) {
            bank.getOrCreate(p.getUniqueId(), p.getName());
        }
        startTasks();
        if (economyActive) {
            packs.rebuild();
        }
        updateNotifier.checkOnStartup();
        getLogger().info(messages.console("enabled", "accounts", bank.size()));
    }

    @Override
    public void onDisable() {
        if (loadFailed) {
            if (database != null) {
                database.close();
            }
            return;
        }
        MenuListener.closeAll();
        if (chatInput != null) {
            chatInput.clear();
        }
        stopTasks();
        if (packs != null) {
            packs.unregister();
        }
        getServer().getServicesManager().unregisterAll(this);
        economyActive = false;
        if (bank != null) {
            bank.flushSync();
        }
        if (circulation != null) {
            circulation.flushAsync();
        }
        if (cheques != null) {
            cheques.flushSync();
        }
        if (database != null) {
            database.close();
        }
        if (updates != null) {
            updates.shutdown();
        }
    }

    /** /vb reload。 */
    public void reload() {
        config.load();
        messages.load();
        items.rebuild();
        stopTasks();
        startTasks();
        if (economyActive) {
            packs.rebuild();
        }
    }

    private void startTasks() {
        long save = config.saveIntervalSeconds() * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            // 支票兌現紀錄先寫，再寫餘額：中途當機只會少入帳，不會讓支票能再兌現一次
            cheques.flushAsync();
            bank.flushAsync();
            circulation.flushAsync();
        }, save, save));
        long top = config.baltopIntervalSeconds() * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> database.call(() -> {
            baltop.rebuild();
            return null;
        }), 1L, top));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            long cutoff = System.currentTimeMillis() - config.logRetentionDays() * 86_400_000L;
            database.call(() -> database.cleanup(cutoff));
        }, 20L * 60, 20L * 3600));
        long interestTick = Long.getLong("vaultbank.debug.interestIntervalSeconds") != null ? 20L * 10 : 20L * 60;
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, interest::tick, interestTick, interestTick));
    }

    private void stopTasks() {
        for (BukkitTask t : tasks) {
            t.cancel();
        }
        tasks.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        bank.getOrCreate(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    // ---------- 存檔順序（防止當機時複製） ----------

    /**
     * 存入之後：先存玩家檔（物品已拿走），再寫資料庫（入帳）。中途當機只會少入帳，不會物品與餘額兩邊都有。
     */
    public void persistAfterDeposit(org.bukkit.entity.Player player) {
        player.saveData();
        cheques.flushAsync();
        bank.flushAsync();
        circulation.flushAsync();
    }

    /**
     * 提取或開支票之後：先寫資料庫（扣款），寫完再存玩家檔（拿到物品）。中途當機只會少拿到物品。
     */
    public void persistAfterWithdraw(org.bukkit.entity.Player player) {
        java.util.UUID id = player.getUniqueId();
        cheques.flushAsync();
        bank.flushAsync();
        circulation.flushAsync();
        database.call(() -> null).thenRun(() -> Bukkit.getScheduler().runTask(this, () -> {
            org.bukkit.entity.Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.saveData();
            }
        }));
    }

    /** 同一玩家的存提、開票、查紀錄的間隔（毫秒），避免洗指令塞爆 I/O。回傳 true 表示太快。 */
    public boolean tooFast(org.bukkit.command.CommandSender sender) {
        if (sender instanceof org.bukkit.entity.Player p && cooling(p)) {
            messages.send(p, "error.too-fast");
            return true;
        }
        return false;
    }

    /** 同上，但不發訊息（呼叫端自己顯示）。 */
    public boolean cooling(org.bukkit.entity.Player p) {
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < config.actionCooldownMs()) {
            return true;
        }
        cooldowns.put(p.getUniqueId(), now);
        return false;
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

    // ---------- 共用 ----------

    /** 金額顯示，例如「1,234.5 元」。 */
    public String format(BigDecimal amount) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        return messages.plain("currency.format", "amount", Money.format(v, config.decimalPlaces()));
    }

    public boolean economyActive() {
        return economyActive;
    }

    public PluginConfig config() { return config; }
    public Messages messages() { return messages; }
    public Database database() { return database; }
    public Bank bank() { return bank; }
    public Baltop baltop() { return baltop; }
    public Circulation circulation() { return circulation; }
    public Cheques cheques() { return cheques; }
    public CurrencyItems items() { return items; }
    public Teller teller() { return teller; }
    public Alerts alerts() { return alerts; }
    public Actions actions() { return actions; }
    public ChatInput chatInput() { return chatInput; }
    public PackService packs() { return packs; }
    public UpdateChecker updates() { return updates; }
    public UpdateNotifier updateNotifier() { return updateNotifier; }
}
