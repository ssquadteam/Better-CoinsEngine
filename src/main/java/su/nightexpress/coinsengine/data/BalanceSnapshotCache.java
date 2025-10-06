package su.nightexpress.coinsengine.data;

import org.jetbrains.annotations.NotNull;
import su.nightexpress.coinsengine.api.currency.Currency;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight, thread-safe snapshot cache of user balances per currency.
 * - Reads are lock-free and constant-time.
 * - Writers update after successful operations or external sync (Redis).
 * - Intended to serve Vault/other API calls on the main thread without DB hits.
 */
public class BalanceSnapshotCache {

    // userId -> (currencyId -> balance)
    private final Map<UUID, Map<String, Double>> balances = new ConcurrentHashMap<>();

    public double getBalance(@NotNull UUID userId, @NotNull String currencyId) {
        Map<String, Double> map = balances.get(userId);
        if (map == null) return 0D;
        return map.getOrDefault(currencyId, 0D);
    }

    public void setBalance(@NotNull UUID userId, @NotNull String currencyId, double value) {
        balances.computeIfAbsent(userId, id -> new ConcurrentHashMap<>()).put(currencyId, value);
    }

    public void setBalances(@NotNull UUID userId, @NotNull Map<String, Double> newBalances) {
        balances.compute(userId, (id, old) -> {
            if (old == null) return new ConcurrentHashMap<>(newBalances);
            old.clear();
            old.putAll(newBalances);
            return old;
        });
    }

    public void updateFromUser(@NotNull UUID userId, @NotNull Iterable<Currency> currencies, java.util.function.Function<Currency, Double> balanceProvider) {
        Map<String, Double> map = balances.computeIfAbsent(userId, id -> new ConcurrentHashMap<>());
        for (Currency c : currencies) {
            map.put(c.getId(), balanceProvider.apply(c));
        }
    }
}
