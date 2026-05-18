package com.gourmet.accounting;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class AccountingRepository {

    private final String url;
    private final String user;
    private final String password;

    public AccountingRepository() {
        String host = System.getenv("DB_HOST") != null
                ? System.getenv("DB_HOST") : "localhost";
        String port = System.getenv("DB_PORT") != null
        ? System.getenv("DB_PORT") : "5432";
this.url = "jdbc:postgresql://" + host + ":" + port + "/accounting_db";
        this.user     = System.getenv("DB_USER") != null
                ? System.getenv("DB_USER") : "postgres";
        this.password = System.getenv("DB_PASS") != null
                ? System.getenv("DB_PASS") : "postgres";
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    public void initTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS payments (
                    order_id   VARCHAR(255) PRIMARY KEY,
                    amount     DOUBLE PRECISION NOT NULL,
                    authorized BOOLEAN NOT NULL
                )
                """;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.execute();
            System.out.println("[AccountingRepository] Table ready.");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to init table", e);
        }
    }

    // Records the payment attempt and returns whether authorized
    // Business rule from the lab: amount < 100 = authorized
    public boolean authorizePayment(String orderId, double amount) {
        boolean authorized = amount < 100;

        String sql = """
                INSERT INTO payments (order_id, amount, authorized)
                VALUES (?, ?, ?)
                ON CONFLICT (order_id)
                DO UPDATE SET amount = EXCLUDED.amount,
                              authorized = EXCLUDED.authorized
                """;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, orderId);
            stmt.setDouble(2, amount);
            stmt.setBoolean(3, authorized);
            stmt.executeUpdate();
            System.out.println("[AccountingRepository] Payment for " + orderId
                    + " amount=" + amount + " authorized=" + authorized);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save payment", e);
        }

        return authorized;
    }
}
