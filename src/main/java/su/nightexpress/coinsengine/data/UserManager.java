package su.nightexpress.coinsengine.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.coinsengine.CoinsEnginePlugin;
import su.nightexpress.coinsengine.api.currency.Currency;
import su.nightexpress.coinsengine.data.impl.CoinsUser;
import su.nightexpress.coinsengine.config.Config;
import su.nightexpress.coinsengine.util.UuidResolver;
import su.nightexpress.nightcore.db.AbstractUserManager;

import java.util.UUID;
import java.util.function.Consumer;

public class UserManager extends AbstractUserManager<CoinsEnginePlugin, CoinsUser> {

    public UserManager(@NotNull CoinsEnginePlugin plugin, @NotNull DataHandler dataHandler) {
        super(plugin, dataHandler);
    }

    @Override
    @NotNull
    public CoinsUser create(@NotNull UUID uuid, @NotNull String name) {
        return CoinsUser.create(uuid, name);
    }

    /**
     * Finds a user by name; if missing and Experimental.Auto_Register_Users is enabled,
     * attempts to resolve UUID by name (online UUID via public API, then offline UUID fallback),
     * creates and saves the user, and finally returns it to the provided consumer on the next tick.
     */
    public void manageOrAutoCreateUser(@NotNull String name, @NotNull Consumer<@Nullable CoinsUser> consumer) {
        this.manageUser(name, existing -> {
            if (existing != null || !Config.isAutoRegisterUsersEnabled()) {
                consumer.accept(existing);
                return;
            }

            this.plugin.getFoliaScheduler().runAsync(() -> {
                UuidResolver.ResolvedIdentity identity = UuidResolver.resolvePreferOnline(name);
                if (identity == null) {
                    this.plugin.warn("Auto-register: Failed to resolve UUID for '" + name + "'.");
                    this.plugin.runNextTick(() -> consumer.accept(null));
                    return;
                }

                CoinsUser user = this.getOrFetch(identity.uuid());
                if (user == null) {
                    user = this.create(identity.uuid(), identity.exactName());
                } else if (!user.getName().equals(identity.exactName())) {
                    user.setName(identity.exactName());
                }

                this.save(user);

                final CoinsUser finalUser = user;
                this.plugin.getRedisSyncManager().ifPresent(redis -> redis.publishUserBalance(finalUser));

                this.plugin.runNextTick(() -> consumer.accept(finalUser));
            });
        });
    }

    public void synchronize() {
        // Do not synchronize data if operations are disabled to prevent data loss/clash.
        if (!this.plugin.getCurrencyManager().canPerformOperations()) return;

        this.getLoaded().forEach(this::handleSynchronization);
    }

    public void handleSynchronization(@NotNull CoinsUser user) {
        if (user.isAutoSavePlanned() || !user.isAutoSyncReady()) return;

        CoinsUser fresh = this.getFromDatabase(user.getId());
        if (fresh == null) return;

        for (Currency currency : this.plugin.getCurrencyManager().getCurrencies()) {
            if (!currency.isSynchronizable()) continue;

            double balance = fresh.getBalance(currency);
            user.getBalance().set(currency, balance); // Bypass balance event call.
        }

        this.plugin.getRedisSyncManager().ifPresent(redis -> {
            redis.publishUserBalance(user);
        });
    }
}
