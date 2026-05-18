package com.gourmet.order;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.ResultSet;
public class OrderRepository {

    private final String url;
    private final String user;
    private final String password;

    public OrderRepository() {
        String host = System.getenv("DB_HOST") != null
                ? System.getenv("DB_HOST") : "localhost";
        String port = System.getenv("DB_PORT") != null
        ? System.getenv("DB_PORT") : "5432";
this.url = "jdbc:postgresql://" + host + ":" + port + "/orders_db";

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
                CREATE TABLE IF NOT EXISTS orders (
                    order_id VARCHAR(255) PRIMARY KEY,
                    status   VARCHAR(50)  NOT NULL
                )
                """;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.execute();
            System.out.println("[OrderRepository] Table ready.");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to init table", e);
        }
    }

    public void upsertOrder(String orderId, String status) {
        String sql = """
                INSERT INTO orders (order_id, status)
                VALUES (?, ?)
                ON CONFLICT (order_id)
                DO UPDATE SET status = EXCLUDED.status
                """;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, orderId);
            stmt.setString(2, status);
            stmt.executeUpdate();
            System.out.println("[OrderRepository] " + orderId + " -> " + status);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert order", e);
        }
    }

// Returns one order as JSON string
    public String findOrderAsJson(String orderId) {
        String sql = "SELECT order_id, status FROM orders WHERE order_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, orderId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return "{\"orderId\":\"" + rs.getString("order_id")
                        + "\",\"status\":\"" + rs.getString("status") + "\"}";
            }
            return "{\"error\":\"Order not found\"}";
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find order", e);
        }
    }

    // Returns all orders as JSON array
    public String findAllAsJson() {
        String sql = "SELECT order_id, status FROM orders";
        StringBuilder json = new StringBuilder("[");
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            boolean first = true;
            while (rs.next()) {
                if (!first) json.append(",");
                json.append("{\"orderId\":\"")
                    .append(rs.getString("order_id"))
                    .append("\",\"status\":\"")
                    .append(rs.getString("status"))
                    .append("\"}");
                first = false;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find orders", e);
        }
        json.append("]");
        return json.toString();
    }

}
