package su.nightexpress.coinsengine.currency;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.coinsengine.CoinsEnginePlugin;
import su.nightexpress.coinsengine.Placeholders;
import su.nightexpress.coinsengine.api.currency.Currency;
import su.nightexpress.coinsengine.api.currency.CurrencyOperation;
import su.nightexpress.coinsengine.api.currency.OperationResult;
import su.nightexpress.coinsengine.command.currency.CurrencyCommands;
import su.nightexpress.coinsengine.config.Config;
import su.nightexpress.coinsengine.config.Lang;
import su.nightexpress.coinsengine.currency.impl.ConfigCurrency;
import su.nightexpress.coinsengine.currency.operation.impl.ExchangeOperation;
import su.nightexpress.coinsengine.currency.operation.impl.SendOperation;
import su.nightexpress.coinsengine.data.DataHandler;
import su.nightexpress.coinsengine.data.impl.CoinsUser;
import su.nightexpress.coinsengine.data.impl.CurrencySettings;
import su.nightexpress.coinsengine.hook.vault.VaultHook;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.manager.AbstractManager;
import su.nightexpress.nightcore.util.FileUtil;
import su.nightexpress.nightcore.util.Plugins;
import su.nightexpress.nightcore.util.StringUtil;
import su.nightexpress.nightcore.util.bukkit.NightItem;
import su.nightexpress.nightcore.util.placeholder.Replacer;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

public class CurrencyManager extends AbstractManager<CoinsEnginePlugin> {

    private final Map<String, Currency> currencyMap;

    private boolean        operationsAllowed;
    private CurrencyLogger logger;

    public CurrencyManager(@NotNull CoinsEnginePlugin plugin) {
        super(plugin);
        this.currencyMap = new HashMap<>();
        this.allowOperations();
    }

    @Override
    protected void onLoad() {
        this.createDefaults();

        for (File file : FileUtil.getConfigFiles(this.getDirectory())) {
            ConfigCurrency currency = new ConfigCurrency(plugin, file);
            this.loadCurrency(currency);
        }

        this.loadLogger();
    }

    @Override
    protected void onShutdown() {
        if (Plugins.hasVault()) {
            VaultHook.shutdown();
        }
        this.getCurrencies().forEach(this::unregisterCurrency);
        this.currencyMap.clear();

        if (this.logger != null) this.logger.shutdown();
        this.disableOperations();
    }

    private void createDefaults() {
        File dir = new File(this.getDirectory());
        if (dir.exists()) return;

        boolean needEconomy = Plugins.hasVault() && !VaultHook.hasEconomy();

        this.createCurrency("coins", currency -> {
            currency.setSymbol("⛂");
            currency.setIcon(NightItem.fromType(Material.SUNFLOWER));
            currency.setDecimal(false);
        });

        this.createCurrency("money", currency -> {
            currency.setSymbol("$");
            currency.setFormat(Placeholders.CURRENCY_SYMBOL + Placeholders.GENERIC_AMOUNT);
            currency.setFormat(currency.getFormat());
            currency.setIcon(NightItem.fromType(Material.GOLD_NUGGET));
            currency.setDecimal(true);
            currency.setVaultEconomy(needEconomy);
        });
    }

    private void loadCurrency(@NotNull ConfigCurrency currency) {
        if (currency.load()) {
            this.registerCurrency(currency);
        }
    }

    private void loadLogger() {
        try {
            this.logger = new CurrencyLogger(this.plugin);
            this.plugin.getFoliaScheduler().runTimerAsync(this::writeLogs, 0L, Config.LOGS_WRITE_INTERVAL.get());
        }
        catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    private void writeLogs() {
        this.logger.write();
    }

    @NotNull
    public String getDirectory() {
        return this.plugin.getDataFolder() + Config.DIR_CURRENCIES;
    }

    @NotNull
    public CurrencyLogger getLogger() {
        return this.logger;
    }

    public void registerCurrency(@NotNull Currency currency) {
        if (this.getCurrency(currency.getId()) != null) {
            this.plugin.error("Could not register duplicated currency: '" + currency.getId() + "'!");
            return;
        }

        if (DataHandler.isCurrencyColumnCached(currency)) {
            this.plugin.error("Currency '" + currency.getId() + "' tried to use column '" + currency.getColumnName() + "' which is already used by other currency!");
            return;
        }

        this.plugin.getDataHandler().onCurrencyRegister(currency);

        boolean isEconomy = false;

        if (currency.isVaultEconomy() && this.getVaultCurrency().isEmpty()) {
            if (Plugins.hasVault()) {
                VaultHook.setup(this.plugin, currency);
                isEconomy = true;
            }
            else {
                this.plugin.error("Found Vault Economy currency, but Vault is not installed!");
            }
        }

        CurrencyCommands.loadCommands(currency, isEconomy);

        this.currencyMap.put(currency.getId(), currency);
        this.plugin.info("Currency registered: '" + currency.getId() + "'.");
    }

    public boolean unregisterCurrency(@NotNull Currency currency) {
        return this.unregisterCurrency(currency.getId());
    }

    public boolean unregisterCurrency(@NotNull String id) {
        Currency currency = this.currencyMap.remove(id);
        if (currency == null) return false;

        CurrencyCommands.unloadForCurrency(currency);

        if (currency.isVaultEconomy()) {
            CurrencyCommands.unloadForEconomy();
        }

        this.plugin.getDataHandler().onCurrencyUnload(currency);
        this.plugin.info("Currency unregistered: '" + currency.getId() + "'.");
        return true;
    }

    public boolean isRegistered(@NotNull String id) {
        return this.currencyMap.containsKey(id.toLowerCase());
    }

    @Nullable
    public Currency getCurrency(@NotNull String id) {
        return this.currencyMap.get(id.toLowerCase());
    }

    @NotNull
    public Map<String, Currency> getCurrencyMap() {
        return this.currencyMap;
    }

    @NotNull
    public List<String> getCurrencyIds() {
        return new ArrayList<>(this.currencyMap.keySet());
    }

    @NotNull
    public Optional<Currency> getVaultCurrency() {
        return this.getCurrencies().stream().filter(Currency::isVaultEconomy).findFirst();
    }

    @NotNull
    public Collection<Currency> getCurrencies() {
        return new HashSet<>(this.currencyMap.values());
    }

    @NotNull
    public ConfigCurrency createCurrency(@NotNull String id, @NotNull Consumer<ConfigCurrency> consumer) {
        File file = new File(this.getDirectory(), FileConfig.withExtension(id));
        ConfigCurrency currency = new ConfigCurrency(this.plugin, file);
        currency.setName(StringUtil.capitalizeUnderscored(id));
        currency.setFormat(Placeholders.GENERIC_AMOUNT + Placeholders.CURRENCY_SYMBOL);
        currency.setFormatShort(Placeholders.GENERIC_AMOUNT + Placeholders.CURRENCY_SYMBOL);
        currency.setCommandAliases(new String[]{id});
        currency.setPermissionRequired(false);
        currency.setTransferAllowed(true);
        currency.setStartValue(0);
        currency.setMaxValue(-1);
        currency.setExchangeAllowed(true);
        consumer.accept(currency);
        currency.save();
        return currency;
    }

    public void allowOperations() {
        this.operationsAllowed = true;
    }

    public void disableOperations() {
        this.operationsAllowed = false;
    }

    public boolean canPerformOperations() {
        return this.operationsAllowed;
    }

    public boolean performOperation(@NotNull CurrencyOperation operation) {
        if (!this.canPerformOperations()) return false;

        OperationResult result = operation.perform();

        if (operation.isLoggable()) {
            if (Config.LOGS_TO_CONSOLE.get()) {
                this.plugin.info(result.getLog());
            }
            if (Config.LOGS_TO_FILE.get()) {
                this.logger.addOperation(result);
            }

            this.plugin.getRedisSyncManager().ifPresent(redis -> {
                redis.publishTransactionLog(result.getLog());
            });
        }

        if (!result.isSuccess()) {
            return false;
        }

        this.plugin.getUserManager().save(operation.getUser());

        this.plugin.getRedisSyncManager().ifPresent(redis -> {
            redis.publishUserBalance(operation.getUser());
        });

        return true;
    }

    public boolean createCurrency(@NotNull CommandSender sender, @NotNull String name, @NotNull String symbol, boolean decimals) {
        String id = StringUtil.transformForID(name);
        if (id.isBlank()) {
            Lang.CURRENCY_CREATE_BAD_NAME.getMessage().send(sender);
            return false;
        }

        if (this.isRegistered(id)) {
            Lang.CURRENCY_CREATE_DUPLICATED.getMessage().send(sender);
            return false;
        }

        ConfigCurrency created = this.createCurrency(id, currency -> {
            currency.setSymbol(symbol);
            currency.setIcon(NightItem.fromType(Material.EMERALD));
            currency.setDecimal(decimals);
        });

        this.loadCurrency(created);
        Lang.CURRENCY_CREATE_SUCCESS.getMessage().send(sender, replacer -> replacer.replace(created.replacePlaceholders()));
        return true;
    }

    public void resetBalances(@NotNull CommandSender sender) {
        this.resetBalances(sender, null);
    }

    public void resetBalances(@NotNull CommandSender sender, @Nullable Currency currency) {
        if (!this.canPerformOperations()) {
            Lang.RESET_ALL_START_BLOCKED.getMessage().send(sender);
            return;
        }

        this.plugin.runTaskAsync(task -> {
            this.disableOperations();
            if (currency == null) {
                Lang.RESET_ALL_STARTED_GLOBAL.getMessage().send(sender);
                plugin.getDataHandler().resetBalances();
                plugin.getUserManager().getLoaded().forEach(CoinsUser::resetBalance);
                Lang.RESET_ALL_COMPLETED_GLOBAL.getMessage().send(sender);
            }
            else {
                Lang.RESET_ALL_STARTED_CURRENCY.getMessage().send(sender, replacer -> replacer.replace(currency.replacePlaceholders()));
                plugin.getDataHandler().resetBalances(currency);
                plugin.getUserManager().getLoaded().forEach(user -> user.resetBalance(currency));
                Lang.RESET_ALL_COMPLETED_CURRENCY.getMessage().send(sender, replacer -> replacer.replace(currency.replacePlaceholders()));
            }
            this.allowOperations();
        });
    }

    public void showBalance(@NotNull CommandSender sender, @NotNull Currency currency) {
        this.showBalance(sender, sender.getName(), currency);
    }

    public void showBalance(@NotNull CommandSender sender, @NotNull String name, @NotNull Currency currency) {
        boolean isOwn = sender.getName().equalsIgnoreCase(name);

        plugin.getUserManager().manageUser(name, user -> {
            if (user == null) {
                this.plugin.getRedisSyncManager().ifPresent(redis ->
                    redis.requestUserCreation(name, redis.getNodeId())
                );

                this.plugin.getFoliaScheduler().runLater(() -> {
                    this.plugin.getUserManager().manageUser(name, retryUser -> {
                        if (retryUser == null) {
                            Lang.ERROR_INVALID_PLAYER.getMessage(this.plugin).send(sender);
                            return;
                        }
                        displayBalance(sender, currency, retryUser, isOwn);
                    });
                }, 40L);
                return;
            }

            displayBalance(sender, currency, user, isOwn);
        });
    }

    private void displayBalance(@NotNull CommandSender sender, @NotNull Currency currency, @NotNull CoinsUser user, boolean isOwn) {
        currency.sendPrefixed((isOwn ? Lang.CURRENCY_BALANCE_DISPLAY_OWN : Lang.CURRENCY_BALANCE_DISPLAY_OTHERS), sender, replacer -> replacer
            .replace(currency.replacePlaceholders())
            .replace(Placeholders.PLAYER_NAME, user.getName())
            .replace(Placeholders.GENERIC_BALANCE, currency.format(user.getBalance(currency)))
        );
    }

    public void showWallet(@NotNull Player player) {
        this.showWallet(player, player.getName());
    }

    public void showWallet(@NotNull CommandSender sender, @NotNull String name) {
        this.showWallet(sender, name, 1, Config.WALLET_ENTRIES_PER_PAGE.get());
    }

    public void showWallet(@NotNull CommandSender sender, @NotNull String name, int page, int limit) {
        boolean isOwn = sender.getName().equalsIgnoreCase(name);

        this.plugin.getUserManager().manageUser(name, user -> {
            if (user == null) {
                Lang.ERROR_INVALID_PLAYER.getMessage(this.plugin).send(sender);
                return;
            }

            List<Currency> allowed = this.getCurrencies().stream()
                .filter(currency -> !(sender instanceof Player p) || currency.hasPermission(p))
                .sorted(Comparator.comparing(Currency::getId))
                .toList();

            final int pageSize = limit <= 0 ? Config.WALLET_ENTRIES_PER_PAGE.get() : limit;
            int total = allowed.size();
            int maxPage = Math.max(1, (int) Math.ceil(total / (double) pageSize));
            int safePage = Math.max(1, Math.min(page, maxPage));
            int fromIndex = Math.max(0, (safePage - 1) * pageSize);
            int toIndex = Math.min(total, fromIndex + pageSize);

            String previous = Replacer.create()
                .replace(Placeholders.PLAYER_NAME, user.getName())
                .replace(Placeholders.GENERIC_VALUE, safePage - 1)
                .replace(Placeholders.GENERIC_AMOUNT, pageSize)
                .apply((safePage > 1 ? Lang.WALLET_LIST_PREVIOUS_PAGE_ACTIVE : Lang.WALLET_LIST_PREVIOUS_PAGE_INACTIVE).getString());

            String next = Replacer.create()
                .replace(Placeholders.PLAYER_NAME, user.getName())
                .replace(Placeholders.GENERIC_VALUE, safePage + 1)
                .replace(Placeholders.GENERIC_AMOUNT, pageSize)
                .apply((safePage < maxPage ? Lang.WALLET_LIST_NEXT_PAGE_ACTIVE : Lang.WALLET_LIST_NEXT_PAGE_INACTIVE).getString());

            (isOwn ? Lang.CURRENCY_WALLET_OWN : Lang.CURRENCY_WALLET_OTHERS).getMessage().send(sender, replacer -> replacer
                .replace(Placeholders.GENERIC_ENTRY, list -> {
                    for (int i = fromIndex; i < toIndex; i++) {
                        Currency currency = allowed.get(i);
                        list.add(Replacer.create()
                            .replace(currency.replacePlaceholders())
                            .replace(Placeholders.GENERIC_BALANCE, currency.format(user.getBalance(currency)))
                            .apply(Lang.CURRENCY_WALLET_ENTRY.getString())
                        );
                    }
                })
                .replace(Placeholders.GENERIC_PREVIOUS_PAGE, previous)
                .replace(Placeholders.GENERIC_NEXT_PAGE, next)
                .replace(Placeholders.GENERIC_CURRENT, safePage)
                .replace(Placeholders.GENERIC_MAX, maxPage)
                .replace(Placeholders.PLAYER_NAME, user.getName())
            );
        });
    }

    public void togglePayments(@NotNull Player player, @NotNull Currency currency) {
        this.togglePayments(player, player.getName(), currency, false);
    }

    public void togglePayments(@NotNull CommandSender sender, @NotNull String name, @NotNull Currency currency, boolean silent) {
        boolean isOwn = sender.getName().equalsIgnoreCase(name);

        this.plugin.getUserManager().manageUser(name, user -> {
            if (user == null) {
                this.plugin.getRedisSyncManager().ifPresent(redis ->
                    redis.requestUserCreation(name, redis.getNodeId())
                );

                this.plugin.getFoliaScheduler().runLater(() -> {
                    this.plugin.getUserManager().manageUser(name, retryUser -> {
                        if (retryUser == null) {
                            Lang.ERROR_INVALID_PLAYER.getMessage(this.plugin).send(sender);
                            return;
                        }
                        executeTogglePayments(sender, currency, retryUser, isOwn, silent);
                    });
                }, 40L);
                return;
            }

            executeTogglePayments(sender, currency, user, isOwn, silent);
        });
    }

    private void executeTogglePayments(@NotNull CommandSender sender, @NotNull Currency currency, @NotNull CoinsUser user, boolean isOwn, boolean silent) {
        CurrencySettings settings = user.getSettings(currency);
        settings.setPaymentsEnabled(!settings.isPaymentsEnabled());
        plugin.getUserManager().save(user);

        if (!isOwn) {
            currency.sendPrefixed(Lang.COMMAND_CURRENCY_PAYMENTS_TARGET, sender, replacer -> replacer
                .replace(currency.replacePlaceholders())
                .replace(Placeholders.PLAYER_NAME, user.getName())
                .replace(Placeholders.GENERIC_STATE, Lang.getEnabledOrDisabled(settings.isPaymentsEnabled()))
            );
        }

        Player target = user.getPlayer();
        if (!silent && target != null) {
            currency.sendPrefixed(Lang.COMMAND_CURRENCY_PAYMENTS_TOGGLE, target, replacer -> replacer
                .replace(currency.replacePlaceholders())
                .replace(Placeholders.GENERIC_STATE, Lang.getEnabledOrDisabled(settings.isPaymentsEnabled()))
            );
        } else if (!silent) {
            this.plugin.getRedisSyncManager().ifPresent(redis ->
                redis.publishCurrencyOperation(
                    user.getId(),
                    currency.getId(),
                    "PAYMENTS_TOGGLE",
                    settings.isPaymentsEnabled() ? 1.0 : 0.0,
                    0.0
                )
            );
        }
    }

    public boolean sendCurrency(@NotNull Player from, @NotNull String targetName, @NotNull Currency currency, double rawAmount) {
        double amount = currency.floorIfNeeded(rawAmount);
        if (amount <= 0D) return false;

        if (from.getName().equalsIgnoreCase(targetName)) {
            Lang.ERROR_COMMAND_NOT_YOURSELF.getMessage().send(from);
            return false;
        }

        double minAmount = currency.getMinTransferAmount();
        if (minAmount > 0 && amount < minAmount) {
            currency.sendPrefixed(Lang.CURRENCY_SEND_ERROR_TOO_LOW, from, replacer -> replacer.replace(Placeholders.GENERIC_AMOUNT, currency.format(minAmount)));
            return false;
        }

        CoinsUser fromUser = plugin.getUserManager().getOrFetch(from);
        if (amount > fromUser.getBalance(currency)) {
            currency.sendPrefixed(Lang.CURRENCY_SEND_ERROR_NOT_ENOUGH, from, replacer -> replacer.replace(currency.replacePlaceholders()));
            return false;
        }

        this.plugin.getUserManager().manageUser(targetName, targetUser -> {
            if (targetUser == null) {
                this.plugin.getRedisSyncManager().ifPresent(redis ->
                    redis.requestUserCreation(targetName, redis.getNodeId())
                );

                this.plugin.getFoliaScheduler().runLater(() -> {
                    this.plugin.getUserManager().manageUser(targetName, retryUser -> {
                        if (retryUser == null) {
                            Lang.ERROR_INVALID_PLAYER.getMessage(this.plugin).send(from);
                            return;
                        }
                        executeSendOperation(currency, amount, retryUser, from, fromUser);
                    });
                }, 40L);
                return;
            }

            executeSendOperation(currency, amount, targetUser, from, fromUser);
        });

        return true;
    }

    private void executeSendOperation(@NotNull Currency currency, double amount, @NotNull CoinsUser targetUser, @NotNull Player from, @NotNull CoinsUser fromUser) {
        CurrencySettings settings = targetUser.getSettings(currency);
        if (!settings.isPaymentsEnabled()) {
            currency.sendPrefixed(Lang.CURRENCY_SEND_ERROR_NO_PAYMENTS, from, replacer -> replacer
                .replace(Placeholders.PLAYER_NAME, targetUser.getName())
                .replace(currency.replacePlaceholders())
            );
            return;
        }

        SendOperation operation = new SendOperation(currency, amount, targetUser, from, fromUser);
        this.performOperation(operation);
        this.plugin.getUserManager().save(fromUser);
    }

    public boolean exchange(@NotNull Player player, @NotNull Currency from, @NotNull Currency to, double initAmount) {
        if (!from.isExchangeAllowed()) {
            from.sendPrefixed(Lang.CURRENCY_EXCHANGE_ERROR_DISABLED, player, replacer -> replacer.replace(from.replacePlaceholders()));
            return false;
        }

        double amount = from.floorIfNeeded(initAmount);
        if (amount <= 0D) {
            from.sendPrefixed(Lang.CURRENCY_EXCHANGE_ERROR_LOW_AMOUNT, player, replacer -> replacer.replace(from.replacePlaceholders()));
            return false;
        }

        CoinsUser user = this.plugin.getUserManager().getOrFetch(player);
        if (user.getBalance(from) < amount) {
            from.sendPrefixed(Lang.CURRENCY_EXCHANGE_ERROR_LOW_BALANCE, player, replacer -> replacer
                .replace(from.replacePlaceholders())
                .replace(Placeholders.GENERIC_AMOUNT, from.format(amount))
            );
            return false;
        }

        if (!from.canExchangeTo(to)) {
            from.sendPrefixed(Lang.CURRENCY_EXCHANGE_ERROR_NO_RATE, player, replacer -> replacer
                .replace(from.replacePlaceholders())
                .replace(Placeholders.GENERIC_NAME, to.getName())
            );
            return false;
        }

        double result = from.getExchangeResult(to, amount);
        if (result <= 0D) {
            from.sendPrefixed(Lang.CURRENCY_EXCHANGE_ERROR_LOW_AMOUNT, player, replacer -> replacer.replace(from.replacePlaceholders()));
            return false;
        }

        double newBalance = user.getBalance(to) + result;
        if (!to.isUnderLimit(newBalance)) {
            to.sendPrefixed(Lang.CURRENCY_EXCHANGE_ERROR_LIMIT_EXCEED, player, replacer -> replacer
                .replace(Placeholders.GENERIC_AMOUNT, to.format(result))
                .replace(Placeholders.GENERIC_MAX, to.format(to.getMaxValue()))
            );
            return false;
        }

        ExchangeOperation operation = new ExchangeOperation(from, amount, user, player, to);
        this.performOperation(operation);
        return true;
    }
}
