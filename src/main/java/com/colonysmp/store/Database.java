package com.colonysmp.store;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Local SQLite database (plugins/ColonySMP/colonies.db). Every save writes a complete snapshot in one
 * transaction on a background thread, so a crash mid-save never leaves half a colony on disk.
 */
public final class Database {

    public record Row(String id, String owner, String data) {}

    public record ItemRow(String colony, int page, int slot, byte[] item) {}

    public record Snapshot(List<Row> colonies, List<Row> citizens, List<ItemRow> storage, List<Row> wars) {}

    private final Plugin plugin;
    private Connection conn;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ColonySMP-DB");
        t.setDaemon(true);
        return t;
    });

    public Database(Plugin plugin) {
        this.plugin = plugin;
    }

    public void open() throws SQLException {
        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) throw new SQLException("can't create " + dir);
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite driver missing from the server", e);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + new File(dir, "colonies.db").getAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("CREATE TABLE IF NOT EXISTS colonies (id TEXT PRIMARY KEY, data TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS citizens (id TEXT PRIMARY KEY, colony TEXT NOT NULL, data TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS storage (colony TEXT NOT NULL, page INTEGER NOT NULL, slot INTEGER NOT NULL, item BLOB NOT NULL, PRIMARY KEY (colony, page, slot))");
            st.execute("CREATE TABLE IF NOT EXISTS wars (id TEXT PRIMARY KEY, data TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
            st.execute("INSERT OR IGNORE INTO meta (key, value) VALUES ('schema', '1')");
        }
    }

    public Snapshot load() throws SQLException {
        List<Row> colonies = new ArrayList<>(), citizens = new ArrayList<>(), wars = new ArrayList<>();
        List<ItemRow> storage = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id, data FROM colonies")) {
                while (rs.next()) colonies.add(new Row(rs.getString(1), null, rs.getString(2)));
            }
            try (ResultSet rs = st.executeQuery("SELECT id, colony, data FROM citizens")) {
                while (rs.next()) citizens.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
            try (ResultSet rs = st.executeQuery("SELECT colony, page, slot, item FROM storage")) {
                while (rs.next()) storage.add(new ItemRow(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getBytes(4)));
            }
            try (ResultSet rs = st.executeQuery("SELECT id, data FROM wars")) {
                while (rs.next()) wars.add(new Row(rs.getString(1), null, rs.getString(2)));
            }
        }
        return new Snapshot(colonies, citizens, storage, wars);
    }

    public void saveAsync(Snapshot snap) {
        if (writer.isShutdown()) {
            write(snap);
            return;
        }
        writer.submit(() -> write(snap));
    }

    /** Waits for queued writes, then writes this snapshot on the calling thread (shutdown). */
    public void saveNow(Snapshot snap) {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) plugin.getLogger().warning("Database writer didn't finish in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        write(snap);
    }

    private synchronized void write(Snapshot snap) {
        if (conn == null) return;
        try {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM colonies");
                st.executeUpdate("DELETE FROM citizens");
                st.executeUpdate("DELETE FROM storage");
                st.executeUpdate("DELETE FROM wars");
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO colonies (id, data) VALUES (?, ?)")) {
                for (Row r : snap.colonies()) {
                    ps.setString(1, r.id());
                    ps.setString(2, r.data());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO citizens (id, colony, data) VALUES (?, ?, ?)")) {
                for (Row r : snap.citizens()) {
                    ps.setString(1, r.id());
                    ps.setString(2, r.owner());
                    ps.setString(3, r.data());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO storage (colony, page, slot, item) VALUES (?, ?, ?, ?)")) {
                for (ItemRow r : snap.storage()) {
                    ps.setString(1, r.colony());
                    ps.setInt(2, r.page());
                    ps.setInt(3, r.slot());
                    ps.setBytes(4, r.item());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO wars (id, data) VALUES (?, ?)")) {
                for (Row r : snap.wars()) {
                    ps.setString(1, r.id());
                    ps.setString(2, r.data());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            conn.setAutoCommit(auto);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Saving colonies failed", e);
            try {
                conn.rollback();
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    public void close() {
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
        }
        conn = null;
    }
}
