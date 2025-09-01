package su.nightexpress.coinsengine.user;

import org.jetbrains.annotations.NotNull;
import su.nightexpress.coinsengine.api.currency.Currency;

import java.math.BigDecimal;

public class BalanceLookup {

    private final UserBalance balance;
    private final Currency currency;

    public BalanceLookup(@NotNull UserBalance balance, @NotNull Currency currency) {
        this.balance = balance;
        this.currency = currency;
    }

    public void clear() {
        this.balance.clear(this.currency);
    }

    public boolean has(double amount) {
        return this.balance.has(this.currency, amount);
    }

    public boolean has(@NotNull BigDecimal amount) {
        return this.balance.has(this.currency, amount);
    }

    public double balance() {
        return this.balance.get(this.currency);
    }

    @NotNull
    public BigDecimal balanceAsBigDecimal() {
        return this.balance.getAsBigDecimal(this.currency);
    }

    public void add(double amount) {
        this.balance.add(this.currency, amount);
    }

    public void add(@NotNull BigDecimal amount) {
        this.balance.add(this.currency, amount);
    }

    public void remove(double amount) {
        this.balance.remove(this.currency, amount);
    }

    public void remove(@NotNull BigDecimal amount) {
        this.balance.remove(this.currency, amount);
    }

    public void set(double amount) {
        this.balance.set(this.currency, amount);
    }

    public void set(@NotNull BigDecimal amount) {
        this.balance.set(this.currency, amount);
    }
}
