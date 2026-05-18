package com.gourmet.kitchen;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class KitchenRepository {

    private final String url;
    private final String user;
    private final String password;

    public KitchenRepository() {
        String host = System.getenv("DB_HOST") != null
                ? System.getenv("DB_HOST") : "localhost";
        String port = System.getenv("DB_PORT") != null
        ? System.getenv("DB_PORT") : "5432";
this.url = "jdbc:postgresql://" + host + ":" + port + "/kitchen_db";
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
                CREATE TABLE IF NOT EXISTS tickets (
                    order_id VARCHAR(255) PRIMARY KEY,
                    status   VARCHAR(50)  NOT NULL
                )
                """;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.execute();
            System.out.println("[KitchenRepository] Table ready.");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to init table", e);
        }
    }

    // Called during happy path — creates a new ticket
    public void createTicket(String orderId) {
        String sql = """
                INSERT INTO tickets (order_id, status)
                VALUES (?, 'CREATED')
                ON CONFLICT (order_id)
                DO UPDATE SET status = 'CREATED'
                """;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, orderId);
            stmt.executeUpdate();
            System.out.println("[KitchenRepository] Ticket CREATED for " + orderId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create ticket", e);
        }
    }

    // Called during compensation — undoes the ticket creation
    public void rejectTicket(String orderId) {
        String sql = """
                UPDATE tickets
                SET status = 'REJECTED'
                WHERE order_id = ?
                """;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, orderId);
            stmt.executeUpdate();
            System.out.println("[KitchenRepository] Ticket REJECTED for " + orderId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to reject ticket", e);
        }
    }
}
