package com.badbones69.crazyvouchers.network;

import ch.jalu.configme.SettingsManager;
import com.badbones69.crazyvouchers.CrazyVouchers;
import com.badbones69.crazyvouchers.config.ConfigManager;
import com.badbones69.crazyvouchers.config.types.ConfigKeys;
import com.ryderbelserion.fusion.core.api.enums.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Codes redeemed with /vouchers redeem, kept in a database shared by every server of a network instead of users.yml.
 * <p>
 * Only active when SHNetwork is installed and {@code reseau.actif} is true, otherwise {@link #create} returns null and
 * the plugin behaves exactly as upstream. No SHNetwork class is used, its presence only switches the mode on.
 * <p>
 * Every database call runs on a dedicated thread. A code is claimed with a single INSERT on its primary key (uuid, code)
 * and the global limit of a code with a conditional UPDATE, so two servers can never both grant the same use.
 */
public final class SharedCodes {

    public enum Outcome {
        GRANTED,
        ALREADY_USED,
        LIMIT_REACHED
    }

    private static final String CODES_TABLE = "crazyvouchers_codes";
    private static final String LIMITS_TABLE = "crazyvouchers_code_limits";

    private final CrazyVouchers plugin;
    private final String url;
    private final Properties properties;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "CrazyVouchers-shared-codes");

        thread.setDaemon(true);

        return thread;
    });

    private SharedCodes(@NotNull final CrazyVouchers plugin, @NotNull final String url, @NotNull final String user, @NotNull final String password, final int timeout) {
        this.plugin = plugin;
        this.url = url;

        this.properties = new Properties();

        this.properties.setProperty("user", user);
        this.properties.setProperty("password", password);
        this.properties.setProperty("connectTimeout", String.valueOf(timeout));
        this.properties.setProperty("socketTimeout", String.valueOf(timeout));
    }

    /**
     * @return the shared store, or null when this server must keep using users.yml.
     */
    public static @Nullable SharedCodes create(@NotNull final CrazyVouchers plugin) {
        final SettingsManager config = ConfigManager.getConfig();

        if (!config.getProperty(ConfigKeys.network_enabled)) return null;

        if (!plugin.getServer().getPluginManager().isPluginEnabled("SHNetwork")) return null;

        final String url = config.getProperty(ConfigKeys.network_url);
        final String user = config.getProperty(ConfigKeys.network_user);
        final String password = config.getProperty(ConfigKeys.network_password);

        if (user.isBlank() || password.isBlank() || "CHANGE_ME".equals(password)) {
            plugin.getFusion().log(Level.WARNING, "SHNetwork is installed but reseau.base-de-donnees is not filled in: codes stay in users.yml on this server.");

            return null;
        }

        final SharedCodes codes = new SharedCodes(plugin, url, user, password, config.getProperty(ConfigKeys.network_timeout));

        codes.executor.execute(() -> {
            try {
                codes.createTables();

                plugin.getFusion().log(Level.INFO, "Codes are shared with the other servers of the network (database %s).", codes.url);
            } catch (final SQLException exception) {
                plugin.getFusion().log(Level.ERROR, "Cannot create the shared code tables, redeeming a code will be refused until the database answers: %s", exception.getMessage());
            }
        });

        if (plugin.getCrazyManager().getVouchers().stream().anyMatch(voucher -> voucher.useLimiter())) {
            plugin.getFusion().log(Level.WARNING, "A voucher uses limiter: the number of uses per player is not shared between servers, only codes are.");
        }

        if (config.getProperty(ConfigKeys.dupe_protection)) {
            plugin.getFusion().log(Level.WARNING, "dupe-protection is on: the list of used vouchers (data.yml) is not shared between servers, only codes are.");
        }

        return codes;
    }

    public @NotNull CompletableFuture<Outcome> claim(@NotNull final UUID uuid, @NotNull final String code, final boolean limited, final int limit) {
        final CompletableFuture<Outcome> future = new CompletableFuture<>();

        this.executor.execute(() -> {
            try {
                future.complete(claimNow(uuid, code, limited, limit));
            } catch (final SQLException exception) {
                future.completeExceptionally(exception);
            }
        });

        return future;
    }

    /**
     * Gives the claim back, used when the redeem event is cancelled or the player left before the reward.
     */
    public void release(@NotNull final UUID uuid, @NotNull final String code) {
        this.executor.execute(() -> {
            try (final Connection connection = connect(); final PreparedStatement statement = connection.prepareStatement("DELETE FROM " + CODES_TABLE + " WHERE uuid = ? AND code = ?")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, code);

                statement.executeUpdate();
            } catch (final SQLException exception) {
                this.plugin.getFusion().log(Level.ERROR, "Cannot release the code %s of %s: %s", code, uuid, exception.getMessage());
            }
        });
    }

    public void close() {
        this.executor.shutdown();
    }

    private @NotNull Outcome claimNow(@NotNull final UUID uuid, @NotNull final String code, final boolean limited, final int limit) throws SQLException {
        try (final Connection connection = connect()) {
            connection.setAutoCommit(false);

            try {
                try (final PreparedStatement statement = connection.prepareStatement("INSERT IGNORE INTO " + CODES_TABLE + " (uuid, code) VALUES (?, ?)")) {
                    statement.setString(1, uuid.toString());
                    statement.setString(2, code);

                    if (statement.executeUpdate() == 0) {
                        connection.rollback();

                        return Outcome.ALREADY_USED;
                    }
                }

                if (limited) {
                    try (final PreparedStatement statement = connection.prepareStatement("INSERT IGNORE INTO " + LIMITS_TABLE + " (code, remaining) VALUES (?, ?)")) {
                        statement.setString(1, code);
                        statement.setInt(2, limit);

                        statement.executeUpdate();
                    }

                    try (final PreparedStatement statement = connection.prepareStatement("UPDATE " + LIMITS_TABLE + " SET remaining = remaining - 1 WHERE code = ? AND remaining > 0")) {
                        statement.setString(1, code);

                        if (statement.executeUpdate() == 0) {
                            connection.rollback();

                            return Outcome.LIMIT_REACHED;
                        }
                    }
                }

                connection.commit();

                return Outcome.GRANTED;
            } catch (final SQLException exception) {
                connection.rollback();

                throw exception;
            }
        }
    }

    private void createTables() throws SQLException {
        try (final Connection connection = connect(); final Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + CODES_TABLE + " ("
                    + "uuid CHAR(36) NOT NULL, "
                    + "code VARCHAR(190) NOT NULL, "
                    + "claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (uuid, code)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + LIMITS_TABLE + " ("
                    + "code VARCHAR(190) NOT NULL, "
                    + "remaining INT NOT NULL, "
                    + "PRIMARY KEY (code)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    private @NotNull Connection connect() throws SQLException {
        return DriverManager.getConnection(this.url, this.properties);
    }
}
