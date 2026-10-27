package com.colonysmp.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A colony's money. Every change is a {@link Txn} with a reason; the recent ones are kept for menus and new ones
 * wait to be appended to the database. Balances never go negative: a payment that can't be made fails.
 */
public final class Treasury {

    public static final int KEEP_RECENT = 200;

    private long balance;
    private long nextId = 1;
    private final Deque<Txn> recent = new ArrayDeque<>();
    private final List<Txn> unsaved = new ArrayList<>();
    /** Today's and yesterday's totals per type, for the economy summary. */
    private long statsDay = Long.MIN_VALUE;
    private final Map<TxnType, Long> today = new EnumMap<>(TxnType.class);
    private final Map<TxnType, Long> yesterday = new EnumMap<>(TxnType.class);

    public long balance() {
        return balance;
    }

    public boolean canAfford(long amount) {
        return amount <= balance;
    }

    /** Money in. Returns the transaction (amount must be positive). */
    public Txn credit(long amount, TxnType type, String memo, long time, long day) {
        if (amount <= 0) return null;
        balance += amount;
        return record(type, amount, memo, time, day);
    }

    /** Money out, only if the whole amount is there. Returns null if it isn't. */
    public Txn debit(long amount, TxnType type, String memo, long time, long day) {
        if (amount <= 0) return null;
        if (amount > balance) return null;
        balance -= amount;
        return record(type, -amount, memo, time, day);
    }

    /** Pays as much as possible of an amount; returns what was actually paid. */
    public long debitUpTo(long amount, TxnType type, String memo, long time, long day) {
        long pay = Math.min(amount, balance);
        if (pay <= 0) return 0;
        debit(pay, type, memo, time, day);
        return pay;
    }

    /** Admin: sets the balance, recording the difference. */
    public void set(long value, String memo, long time, long day) {
        long diff = Math.max(0, value) - balance;
        if (diff == 0) return;
        balance = Math.max(0, value);
        record(TxnType.ADMIN, diff, memo, time, day);
    }

    private Txn record(TxnType type, long amount, String memo, long time, long day) {
        Txn t = new Txn(nextId++, time, day, type, amount, balance, memo == null ? "" : memo);
        recent.addFirst(t);
        while (recent.size() > KEEP_RECENT) recent.removeLast();
        unsaved.add(t);
        roll(day);
        today.merge(type, amount, Long::sum);
        return t;
    }

    private void roll(long day) {
        if (day == statsDay) return;
        yesterday.clear();
        if (day == statsDay + 1) yesterday.putAll(today);
        today.clear();
        statsDay = day;
    }

    /** Signed totals per type so far today. */
    public Map<TxnType, Long> today(long day) {
        roll(day);
        return new EnumMap<>(today);
    }

    /** Signed totals per type for the whole of yesterday. */
    public Map<TxnType, Long> yesterday(long day) {
        roll(day);
        return new EnumMap<>(yesterday);
    }

    public List<Txn> recent() {
        return new ArrayList<>(recent);
    }

    /** Transactions not yet written to the database; clears the list. */
    public List<Txn> drainUnsaved() {
        List<Txn> out = new ArrayList<>(unsaved);
        unsaved.clear();
        return out;
    }

    /** Puts transactions back if a save failed, so they're written next time. */
    public void requeue(List<Txn> txns) {
        unsaved.addAll(0, txns);
    }

    // ───────────── loading ─────────────

    public void load(long balance, long nextId, List<Txn> recentNewestFirst) {
        this.balance = Math.max(0, balance);
        this.nextId = Math.max(1, nextId);
        recent.clear();
        for (Txn t : recentNewestFirst) {
            recent.addLast(t);
            this.nextId = Math.max(this.nextId, t.id() + 1);
        }
    }

    public long nextId() {
        return nextId;
    }
}
