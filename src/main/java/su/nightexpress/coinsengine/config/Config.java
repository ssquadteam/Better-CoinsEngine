package su.nightexpress.coinsengine.config;

import su.nightexpress.coinsengine.COEFiles;
import su.nightexpress.nightcore.config.ConfigValue;
import su.nightexpress.nightcore.util.Plugins;

import static su.nightexpress.coinsengine.Placeholders.*;
import static su.nightexpress.nightcore.util.text.night.wrapper.TagWrappers.*;

public class Config {

    public static final ConfigValue<Boolean> GENERAL_PLACEHOLDER_API_FOR_CURRENCY_FORMAT = ConfigValue.create("General.PlaceholderAPI_For_Currency_Format",
        true,
        "Sets whether to apply PlaceholderAPI placeholders for currency 'Format' setting.",
        "Allows you to use custom images from Oraxen or ItemsAdder, as well as any other player unrelated placeholders."
    );

    public static final ConfigValue<Boolean> INTEGRATION_VAULT_ENABLED = ConfigValue.create("Integration.Vault.Enabled",
        true,
        "Controls whether Vault integration is enabled.",
        WIKI_VAULT_HOOK
    );

    public static final ConfigValue<String> INTEGRATION_VAULT_ECONOMY_CURRENCY = ConfigValue.create("Integration.Vault.EconomyCurrency",
        "money",
        "Sets a currency used as primary sever economy using the Vault API."
    );

    public static final ConfigValue<Boolean> TOPS_ENABLED = ConfigValue.create("Top.Enabled",
        true,
        "Controls whether Tops feature is enabled.",
        "[*] This feature is required for the 'server balance' placeholders to work.",
        WIKI_TOPS
    );

    public static final ConfigValue<Boolean> TOPS_USE_GUI = ConfigValue.create("Top.Use_GUI",
        true,
        "Controls whether GUI is preferred to display balance leaderboard.",
        "[*] Disable if you want it be text only."
    );

    public static final ConfigValue<Integer> TOPS_ENTRIES_PER_PAGE = ConfigValue.create("Top.Entries_Per_Page",
        10,
        "Sets how many entries displayed per page for currency top commands.",
        "[*] Works only for text leaderboards. GUI settings available in the '" + COEFiles.DIR_MENU + "' directory."
    );

    public static final ConfigValue<Integer> TOPS_UPDATE_INTERVAL = ConfigValue.create("Top.Update_Interval",
        900,
        "Sets update interval (in seconds) for currency top balance lists.",
        "[Asynchronous]",
        "[Default is 900 (15 minutes)]"
    );

    public static final ConfigValue<Boolean> CURRENCY_PREFIX_ENABLED = ConfigValue.create("Currency.Prefix.Enabled",
        true,
        "Controls whether or not currency messages will use custom prefix instead of the plugin's one.",
        WIKI_PREFIXES
    );

    public static final ConfigValue<String> CURRENCY_PREFIX_FORMAT = ConfigValue.create("Currency.Prefix.Format",
        SOFT_YELLOW.wrap(BOLD.wrap(CURRENCY_PREFIX)) + DARK_GRAY.wrap(" » "),
        "Sets custom prefix format for currency messages.",
        "You can use 'Currency' placeholders: " + WIKI_PLACEHOLDERS
    );

    public static final ConfigValue<Boolean> WALLET_ENABLED = ConfigValue.create("Wallet.Enabled",
        true,
        "Controls whether Wallet feature is enabled.",
        WIKI_WALLET
    );

    public static final ConfigValue<String[]> WALLET_ALIASES = ConfigValue.create("Wallet.Command_Aliases",
        new String[]{"wallet"},
        "Command aliases for the Wallet feature."
    );

    public static final ConfigValue<Integer> WALLET_ENTRIES_PER_PAGE = ConfigValue.create("Wallet.Entries_Per_Page",
        10,
        "Sets how many currencies to show per page for /wallet.")
    ;

    public static final ConfigValue<Boolean> MIGRATION_ENABLED = ConfigValue.create("Migration.Enabled",
        true,
        "Controls whether Migration feature is available.",
        "Disable if you don't plan to migrate from other plugins to save some RAM.",
        WIKI_MIGRATION
    );

    public static final ConfigValue<Boolean> LOGS_TO_CONSOLE = ConfigValue.create("Logs.Enabled.Console",
        false,
        "Controls whether currency operations will be logged to console."
    );

    public static final ConfigValue<Boolean> LOGS_TO_FILE = ConfigValue.create("Logs.Enabled.File",
        true,
        "Controls whether currency operations will be logged to a file."
    );

    public static final ConfigValue<String> LOGS_DATE_FORMAT = ConfigValue.create("Logs.DateFormat",
        "dd/MM/yyyy HH:mm:ss",
        "Logs date format."
    );

    public static final ConfigValue<Integer> LOGS_WRITE_INTERVAL = ConfigValue.create("Logs.Write_Interval",
        5,
        "Controls how often currency operations writes to the log file."
    );

    public static final ConfigValue<Boolean> REDIS_ENABLED = ConfigValue.create("Redis.Enabled",
        false,
        "Enable realtime synchronization over Redis pub/sub.",
        "Allows cross-server currency balance synchronization and leaderboard sync."
    );

    public static final ConfigValue<String> REDIS_HOST = ConfigValue.create("Redis.Host",
        "127.0.0.1",
        "Redis server host."
    );

    public static final ConfigValue<Integer> REDIS_PORT = ConfigValue.create("Redis.Port",
        6379,
        "Redis server port."
    );

    public static final ConfigValue<String> REDIS_PASSWORD = ConfigValue.create("Redis.Password",
        "",
        "Redis server password, leave empty if none."
    );

    public static final ConfigValue<Boolean> REDIS_SSL = ConfigValue.create("Redis.SSL",
        false,
        "Use SSL/TLS for Redis connection."
    );

    public static final ConfigValue<String> REDIS_CHANNEL = ConfigValue.create("Redis.Channel",
        "coinsengine:sync",
        "Redis pub/sub channel name used for this plugin."
    );

    public static final ConfigValue<String> REDIS_NODE_ID = ConfigValue.create("Redis.NodeId",
        "",
        "Optional node identifier. If empty, a random UUID is used at runtime."
    );

    public static final ConfigValue<Integer> REDIS_BALANCE_SYNC_INTERVAL = ConfigValue.create("Redis.Sync.Balance_Interval",
        30,
        "Interval (in seconds) for automatic balance synchronization.",
        "Set to 0 to disable periodic sync (only real-time sync on operations)."
    );

    public static final ConfigValue<Integer> REDIS_LEADERBOARD_SYNC_INTERVAL = ConfigValue.create("Redis.Sync.Leaderboard_Interval",
        300,
        "Interval (in seconds) for leaderboard synchronization.",
        "Set to 0 to disable leaderboard sync."
    );

    public static final ConfigValue<Boolean> REDIS_SYNC_USER_DATA = ConfigValue.create("Redis.Sync.User_Data",
        true,
        "Enable synchronization of user settings and preferences."
    );

    public static final ConfigValue<Boolean> REDIS_SYNC_TRANSACTION_LOGS = ConfigValue.create("Redis.Sync.Transaction_Logs",
        true,
        "Enable synchronization of currency operation logs."
    );

    public static final ConfigValue<Boolean> EXPERIMENTAL_AUTO_REGISTER_USERS = ConfigValue.create("Experimental.Auto_Register_Users",
        false,
        "VERY EXPERIMENTAL! May cause issues with cracked/offline-mode servers.",
        "Allows giving coins to players who are not online or registered.",
        "Useful for Tebex and other external stores where player may be offline.",
        "Keep disabled unless you understand and accept the risks."
    );

    public static boolean isTopsEnabled() {
        return TOPS_ENABLED.get();
    }

    public static boolean isWalletEnabled() {
        return WALLET_ENABLED.get();
    }

    public static boolean isMigrationEnabled() {
        return MIGRATION_ENABLED.get();
    }

    public static boolean useCurrencyFormatPAPI() {
        return GENERAL_PLACEHOLDER_API_FOR_CURRENCY_FORMAT.get() && Plugins.hasPlaceholderAPI();
    }

    public static boolean isRedisEnabled() {
        return REDIS_ENABLED.get();
    }

    public static boolean isAutoRegisterUsersEnabled() {
        return EXPERIMENTAL_AUTO_REGISTER_USERS.get();
    }
}
