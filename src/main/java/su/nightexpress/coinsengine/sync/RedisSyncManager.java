package su.nightexpress.coinsengine.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import su.nightexpress.nightcore.lib.redis.jedis.DefaultJedisClientConfig;
import su.nightexpress.nightcore.lib.redis.jedis.HostAndPort;
import su.nightexpress.nightcore.lib.redis.jedis.Jedis;
import su.nightexpress.nightcore.lib.redis.jedis.JedisPool;
import su.nightexpress.nightcore.lib.redis.jedis.JedisPubSub;
import su.nightexpress.nightcore.lib.commons.pool2.impl.GenericObjectPoolConfig;
import su.nightexpress.coinsengine.CoinsEnginePlugin;
import su.nightexpress.coinsengine.Placeholders;
import su.nightexpress.coinsengine.api.currency.Currency;
import su.nightexpress.coinsengine.config.Config;
import su.nightexpress.coinsengine.config.Lang;
import su.nightexpress.coinsengine.data.impl.CoinsUser;
import su.nightexpress.coinsengine.tops.TopEntry;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis synchronization manager for CoinsEngine
 * Provides cross-server currency balance synchronization, leaderboard sync, and user data sync
 */
public class RedisSyncManager {

    private final CoinsEnginePlugin plugin;
    private JedisPool pool;
    private JedisPubSub subscriber;
    private Thread subscriberThread;

    private final Gson gson;
    private final String nodeId;
    private String channel;
    private volatile boolean active;

    private long balanceSyncInterval;
    private long leaderboardSyncInterval;

    // Cross-server player name cache
    private final Set<String> crossServerPlayerNames = new HashSet<>();

    public RedisSyncManager(@NotNull CoinsEnginePlugin plugin) {
        this.plugin = plugin;
        this.gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

        String nid = Config.REDIS_NODE_ID.get();
        if (nid == null || nid.isBlank()) {
            nid = UUID.randomUUID().toString();
        }
        this.nodeId = nid;
    }

    public void setup() {
        if (!Config.isRedisEnabled()) {
            return;
        }

        String host = Config.REDIS_HOST.get();
        int port = Config.REDIS_PORT.get();
        String password = Config.REDIS_PASSWORD.get();
        boolean ssl = Config.REDIS_SSL.get();
        this.channel = Config.REDIS_CHANNEL.get();

        this.balanceSyncInterval = Config.REDIS_BALANCE_SYNC_INTERVAL.get() * 20L;
        this.leaderboardSyncInterval = Config.REDIS_LEADERBOARD_SYNC_INTERVAL.get() * 20L;

        try {
            DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .password((password == null || password.isEmpty()) ? null : password)
                .ssl(ssl)
                .build();

            this.pool = new JedisPool(new GenericObjectPoolConfig<>(), new HostAndPort(host, port), clientConfig);
            this.active = true;
            this.startSubscriber();
            this.startPeriodicSync();

            this.plugin.info("Redis sync enabled. Channel: " + this.channel + " | NodeId: " + this.nodeId);
        }
        catch (Exception e) {
            this.plugin.error("Failed to initialize Redis: " + e.getMessage());
            this.active = false;
        }
    }

    public void shutdown() {
        this.active = false;
        try {
            if (this.subscriber != null) {
                this.subscriber.unsubscribe();
            }
        }
        catch (Exception ignored) {}
        try {
            if (this.subscriberThread != null) {
                this.subscriberThread.interrupt();
            }
        }
        catch (Exception ignored) {}
        try {
            if (this.pool != null) this.pool.close();
        }
        catch (Exception ignored) {}
    }

    public boolean isActive() {
        return this.pool != null && this.active;
    }

    @NotNull
    public String getNodeId() {
        return this.nodeId;
    }

    /**
     * Start periodic synchronization tasks using Folia-compatible scheduling
     */
    private void startPeriodicSync() {
        if (this.balanceSyncInterval > 0) {
            this.plugin.getFoliaScheduler().runTimerAsync(this::syncAllBalances, 0L, this.balanceSyncInterval);
        }

        if (this.leaderboardSyncInterval > 0) {
            this.plugin.getFoliaScheduler().runTimerAsync(this::syncLeaderboards, 0L, this.leaderboardSyncInterval);
        }

        this.plugin.getFoliaScheduler().runTimerAsync(this::syncPlayerNames, 0L, 600L); // 30 seconds
    }

    /* =========================
       Publisher API
       ========================= */

    /**
     * Publishes user balance update across servers
     */
    public void publishUserBalance(@NotNull CoinsUser user) {
        if (!isActive()) return;

        JsonObject data = new JsonObject();
        data.addProperty("userId", user.getId().toString());
        data.addProperty("userName", user.getName());
        
        JsonObject balances = new JsonObject();
        for (Currency currency : this.plugin.getCurrencyManager().getCurrencies()) {
            balances.addProperty(currency.getId(), user.getBalance(currency));
        }
        data.add("balances", balances);
        
        JsonObject settings = new JsonObject();
        settings.addProperty("hiddenFromTops", user.isHiddenFromTops());
        data.add("settings", settings);

        publish("USER_BALANCE_UPDATE", data);
    }

    /**
     * Publishes currency operation (add, remove, set)
     */
    public void publishCurrencyOperation(@NotNull UUID userId, @NotNull String currencyId, 
                                       @NotNull String operation, double amount, double newBalance) {
        if (!isActive()) return;

        JsonObject data = new JsonObject();
        data.addProperty("userId", userId.toString());
        data.addProperty("currencyId", currencyId);
        data.addProperty("operation", operation);
        data.addProperty("amount", amount);
        data.addProperty("newBalance", newBalance);
        data.addProperty("timestamp", System.currentTimeMillis());

        publish("CURRENCY_OPERATION", data);
    }

    /**
     * Publishes leaderboard data
     */
    public void publishLeaderboard(@NotNull String currencyId, @NotNull Map<String, TopEntry> entries) {
        if (!isActive()) return;

        JsonObject data = new JsonObject();
        data.addProperty("currencyId", currencyId);
        data.add("entries", gson.toJsonTree(entries));
        data.addProperty("timestamp", System.currentTimeMillis());

        publish("LEADERBOARD_UPDATE", data);
    }

    /**
     * Publishes transaction log entry
     */
    public void publishTransactionLog(@NotNull String logEntry) {
        if (!isActive() || !Config.REDIS_SYNC_TRANSACTION_LOGS.get()) return;

        JsonObject data = new JsonObject();
        data.addProperty("logEntry", logEntry);
        data.addProperty("timestamp", System.currentTimeMillis());

        publish("TRANSACTION_LOG", data);
    }

    /**
     * Publishes payment notification across servers
     */
    public void publishPaymentNotification(@NotNull UUID recipientId, @NotNull String senderName,
                                         @NotNull String currencyId, double amount, double newBalance) {
        if (!isActive()) return;

        JsonObject data = new JsonObject();
        data.addProperty("recipientId", recipientId.toString());
        data.addProperty("senderName", senderName);
        data.addProperty("currencyId", currencyId);
        data.addProperty("amount", amount);
        data.addProperty("newBalance", newBalance);
        data.addProperty("timestamp", System.currentTimeMillis());

        publish("PAYMENT_NOTIFICATION", data);
    }

    /**
     * Publishes online player names from this server
     */
    public void publishPlayerNames(@NotNull Set<String> playerNames) {
        if (!isActive()) return;

        JsonObject data = new JsonObject();
        JsonArray namesArray = new JsonArray();
        playerNames.forEach(namesArray::add);
        data.add("playerNames", namesArray);
        data.addProperty("timestamp", System.currentTimeMillis());

        publish("PLAYER_NAMES_UPDATE", data);
    }

    /**
     * Request balance sync for a specific user
     */
    public void requestUserSync(@NotNull UUID userId) {
        if (!isActive()) return;

        JsonObject data = new JsonObject();
        data.addProperty("userId", userId.toString());
        data.addProperty("requestingNode", this.nodeId);

        publish("USER_SYNC_REQUEST", data);
    }

    /**
     * Request user creation for cross-server operations
     */
    public void requestUserCreation(@NotNull String playerName, @NotNull String requestingNode) {
        if (!isActive()) return;

        JsonObject data = new JsonObject();
        data.addProperty("playerName", playerName);
        data.addProperty("requestingNode", requestingNode);

        publish("USER_CREATE_REQUEST", data);
    }

    /**
     * Sync all online player balances
     */
    private void syncAllBalances() {
        if (!isActive()) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            CoinsUser user = this.plugin.getUserManager().getOrFetch(player);
            if (user != null) {
                publishUserBalance(user);
            }
        }
    }

    /**
     * Sync leaderboards for all currencies
     */
    private void syncLeaderboards() {
        if (!isActive() || !this.plugin.getTopManager().isPresent()) return;

        this.plugin.getTopManager().ifPresent(topManager -> {
            for (Currency currency : this.plugin.getCurrencyManager().getCurrencies()) {
                if (currency.isLeaderboardEnabled()) {
                    Map<String, TopEntry> entries = topManager.getTopEntriesMap().get(currency.getId());
                    if (entries != null && !entries.isEmpty()) {
                        publishLeaderboard(currency.getId(), entries);
                    }
                }
            }
        });
    }

    /**
     * Sync online player names from this server
     */
    private void syncPlayerNames() {
        if (!isActive()) return;

        Set<String> localPlayerNames = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            localPlayerNames.add(player.getName());
        }

        if (!localPlayerNames.isEmpty()) {
            publishPlayerNames(localPlayerNames);
        }
    }

    /**
     * Core publish method
     */
    private void publish(@NotNull String type, @NotNull JsonObject data) {
        if (!isActive()) return;

        JsonObject root = new JsonObject();
        root.addProperty("type", type);
        root.addProperty("nodeId", this.nodeId);
        root.add("data", data);

        this.plugin.getFoliaScheduler().runAsync(() -> {
            try (Jedis jedis = this.pool.getResource()) {
                jedis.publish(this.channel, this.gson.toJson(root));
            }
            catch (Exception e) {
                this.plugin.warn("Redis publish failed: " + e.getMessage());
            }
        });
    }

    /* =========================
       Subscriber
       ========================= */

    private void startSubscriber() {
        this.subscriber = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                handleIncoming(message);
            }
        };

        this.subscriberThread = new Thread(() -> {
            try (Jedis jedis = this.pool.getResource()) {
                jedis.subscribe(this.subscriber, this.channel);
            }
            catch (Exception e) {
                this.plugin.error("Redis subscriber error: " + e.getMessage());
            }
            finally {
                this.active = false;
            }
        }, "CoinsEngine-RedisSubscriber");

        this.subscriberThread.setDaemon(true);
        this.subscriberThread.start();
    }

    private void handleIncoming(@NotNull String message) {
        try {
            JsonObject root = gson.fromJson(message, JsonObject.class);
            String sourceNodeId = root.get("nodeId").getAsString();

            if (sourceNodeId.equals(this.nodeId)) {
                return;
            }

            String type = root.get("type").getAsString();
            JsonObject data = root.getAsJsonObject("data");

            switch (type) {
                case "USER_BALANCE_UPDATE" -> applyUserBalanceUpdate(data);
                case "CURRENCY_OPERATION" -> applyCurrencyOperation(data);
                case "LEADERBOARD_UPDATE" -> applyLeaderboardUpdate(data);
                case "TRANSACTION_LOG" -> applyTransactionLog(data);
                case "USER_SYNC_REQUEST" -> handleUserSyncRequest(data);
                case "USER_CREATE_REQUEST" -> handleUserCreateRequest(data);
                case "PAYMENT_NOTIFICATION" -> applyPaymentNotification(data);
                case "PLAYER_NAMES_UPDATE" -> applyPlayerNamesUpdate(data);
                default -> {}
            }
        }
        catch (Exception e) {
            this.plugin.warn("Failed to handle Redis message: " + e.getMessage());
        }
    }

    /* =========================
       Message Handlers
       ========================= */

    private void applyUserBalanceUpdate(@NotNull JsonObject data) {
        UUID userId = UUID.fromString(data.get("userId").getAsString());
        String userName = data.get("userName").getAsString();
        JsonObject balances = data.getAsJsonObject("balances");
        JsonObject settings = data.getAsJsonObject("settings");

        this.plugin.runNextTick(() -> {
            CoinsUser user = this.plugin.getUserManager().getOrFetch(userId);
            if (user == null) {
                user = this.plugin.getUserManager().getOrFetch(userId);
                if (user == null) return;
            }

            for (Currency currency : this.plugin.getCurrencyManager().getCurrencies()) {
                if (balances.has(currency.getId())) {
                    double balance = balances.get(currency.getId()).getAsDouble();
                    user.getBalance().set(currency, balance); // Bypass balance event call
                }
            }

            if (settings.has("hiddenFromTops")) {
                user.setHiddenFromTops(settings.get("hiddenFromTops").getAsBoolean());
            }

            this.plugin.getUserManager().save(user);
        });
    }

    private void applyCurrencyOperation(@NotNull JsonObject data) {
        UUID userId = UUID.fromString(data.get("userId").getAsString());
        String currencyId = data.get("currencyId").getAsString();
        String operation = data.get("operation").getAsString();
        double amount = data.get("amount").getAsDouble();
        double newBalance = data.get("newBalance").getAsDouble();

        this.plugin.runNextTick(() -> {
            Player player = Bukkit.getPlayer(userId);
            if (player == null) return;

            Currency currency = this.plugin.getCurrencyManager().getCurrency(currencyId);
            if (currency == null) return;

            switch (operation) {
                case "ADD_NOTIFY" -> {
                    currency.sendPrefixed(Lang.COMMAND_CURRENCY_GIVE_NOTIFY, player, replacer -> replacer
                        .replace(currency.replacePlaceholders())
                        .replace(Placeholders.GENERIC_AMOUNT, currency.format(amount))
                        .replace(Placeholders.GENERIC_BALANCE, currency.format(newBalance))
                    );
                }
                case "SET_NOTIFY" -> {
                    currency.sendPrefixed(Lang.COMMAND_CURRENCY_SET_NOTIFY, player, replacer -> replacer
                        .replace(currency.replacePlaceholders())
                        .replace(Placeholders.GENERIC_AMOUNT, currency.format(amount))
                        .replace(Placeholders.GENERIC_BALANCE, currency.format(newBalance))
                    );
                }
                case "REMOVE_NOTIFY" -> {
                    currency.sendPrefixed(Lang.COMMAND_CURRENCY_TAKE_NOTIFY, player, replacer -> replacer
                        .replace(currency.replacePlaceholders())
                        .replace(Placeholders.GENERIC_AMOUNT, currency.format(amount))
                        .replace(Placeholders.GENERIC_BALANCE, currency.format(newBalance))
                    );
                }
                case "PAYMENTS_TOGGLE" -> {
                    boolean enabled = amount > 0;
                    currency.sendPrefixed(Lang.COMMAND_CURRENCY_PAYMENTS_TOGGLE, player, replacer -> replacer
                        .replace(currency.replacePlaceholders())
                        .replace(Placeholders.GENERIC_STATE, Lang.getEnabledOrDisabled(enabled))
                    );
                }
            }

            this.plugin.info("Sent cross-server currency notification: " + operation + " " + amount + " " +
                           currencyId + " to " + player.getName());
        });
    }

    private void applyLeaderboardUpdate(@NotNull JsonObject data) {
        if (!this.plugin.getTopManager().isPresent()) return;

        String currencyId = data.get("currencyId").getAsString();
        Map<String, TopEntry> entries = gson.fromJson(data.get("entries"),
            new com.google.gson.reflect.TypeToken<Map<String, TopEntry>>(){}.getType());

        this.plugin.runNextTick(() -> {
            this.plugin.getTopManager().ifPresent(topManager -> {
                topManager.updateExternalTopEntries(currencyId, entries);
                this.plugin.info("Updated leaderboard for currency: " + currencyId + " (" + entries.size() + " entries)");
            });
        });
    }

    private void applyTransactionLog(@NotNull JsonObject data) {
        if (!Config.REDIS_SYNC_TRANSACTION_LOGS.get()) return;

        String logEntry = data.get("logEntry").getAsString();

        this.plugin.runTaskAsync(() -> {
            this.plugin.getCurrencyManager().getLogger().addExternalLogEntry(logEntry);
        });
    }

    private void applyPaymentNotification(@NotNull JsonObject data) {
        UUID recipientId = UUID.fromString(data.get("recipientId").getAsString());
        String senderName = data.get("senderName").getAsString();
        String currencyId = data.get("currencyId").getAsString();
        double amount = data.get("amount").getAsDouble();
        double newBalance = data.get("newBalance").getAsDouble();

        this.plugin.runNextTick(() -> {
            Player recipient = Bukkit.getPlayer(recipientId);
            if (recipient == null) return;

            Currency currency = this.plugin.getCurrencyManager().getCurrency(currencyId);
            if (currency == null) return;

            currency.sendPrefixed(Lang.CURRENCY_SEND_DONE_NOTIFY, recipient, replacer -> replacer
                .replace(currency.replacePlaceholders())
                .replace(Placeholders.GENERIC_AMOUNT, currency.format(amount))
                .replace(Placeholders.GENERIC_BALANCE, currency.format(newBalance))
                .replace(Placeholders.PLAYER_NAME, senderName)
            );

            this.plugin.info("Sent cross-server payment notification to " + recipient.getName() +
                           " from " + senderName + ": " + currency.format(amount));
        });
    }

    private void applyPlayerNamesUpdate(@NotNull JsonObject data) {
        JsonArray namesArray = data.getAsJsonArray("playerNames");

        synchronized (this.crossServerPlayerNames) {
            this.crossServerPlayerNames.clear();

            for (int i = 0; i < namesArray.size(); i++) {
                String playerName = namesArray.get(i).getAsString();
                this.crossServerPlayerNames.add(playerName);
            }
        }
    }

    /**
     * Gets all player names across servers (local + cross-server)
     */
    @NotNull
    public Set<String> getAllPlayerNames() {
        Set<String> allNames = new HashSet<>();

        // Add local players
        for (Player player : Bukkit.getOnlinePlayers()) {
            allNames.add(player.getName());
        }

        // Add cross-server players
        synchronized (this.crossServerPlayerNames) {
            allNames.addAll(this.crossServerPlayerNames);
        }

        return allNames;
    }

    private void handleUserSyncRequest(@NotNull JsonObject data) {
        UUID userId = UUID.fromString(data.get("userId").getAsString());
        String requestingNode = data.get("requestingNode").getAsString();

        this.plugin.runNextTick(() -> {
            CoinsUser user = this.plugin.getUserManager().getOrFetch(userId);
            if (user != null) {
                publishUserBalance(user);
                this.plugin.info("Sent user sync data for " + user.getName() + " to node: " + requestingNode);
            }
        });
    }

    private void handleUserCreateRequest(@NotNull JsonObject data) {
        String playerName = data.get("playerName").getAsString();
        String requestingNode = data.get("requestingNode").getAsString();

        if (requestingNode.equals(this.nodeId)) return;

        this.plugin.runNextTick(() -> {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player != null) {
                CoinsUser user = this.plugin.getUserManager().getOrFetch(player);
                publishUserBalance(user);
                this.plugin.info("Sent user data for cross-server request: " + playerName);
                return;
            }

            if (Config.isAutoRegisterUsersEnabled()) {
                this.plugin.getUserManager().manageOrAutoCreateUser(playerName, created -> {
                    if (created != null) {
                        publishUserBalance(created);
                        this.plugin.info("Auto-registered user for cross-server request: " + playerName);
                    }
                });
            }
        });
    }
}
