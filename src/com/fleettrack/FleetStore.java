package com.fleettrack;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class FleetStore implements AutoCloseable {
    private final Connection conn;

    public FleetStore(String dbPath) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initSchema();
        seedInitialData();
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
            st.execute("PRAGMA busy_timeout=5000;");
            st.execute("""
                CREATE TABLE IF NOT EXISTS riders (
                    rider_code TEXT PRIMARY KEY,
                    rider_name TEXT NOT NULL,
                    vehicle_type TEXT NOT NULL,
                    pos_x REAL NOT NULL,
                    pos_y REAL NOT NULL,
                    status TEXT NOT NULL DEFAULT 'AVAILABLE',
                    active_order_id INTEGER,
                    completed_count INTEGER NOT NULL DEFAULT 0
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS deliveries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tracking_code TEXT NOT NULL UNIQUE,
                    customer_name TEXT NOT NULL,
                    pickup_hub TEXT NOT NULL,
                    drop_zone TEXT NOT NULL,
                    pickup_x REAL NOT NULL,
                    pickup_y REAL NOT NULL,
                    drop_x REAL NOT NULL,
                    drop_y REAL NOT NULL,
                    assigned_rider TEXT,
                    status TEXT NOT NULL DEFAULT 'UNASSIGNED',
                    sla_mins INTEGER NOT NULL DEFAULT 25,
                    created_at_ms INTEGER NOT NULL,
                    delivered_at_ms INTEGER
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_deliveries_status ON deliveries(status);");
        }
    }

    private void seedInitialData() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM riders")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }

        Object[][] riders = {
            {"RDR-01", "Arjun V", "E-Bike", 18.0, 24.0},
            {"RDR-02", "Karthik M", "Scooter", 48.0, 32.0},
            {"RDR-03", "Divya R", "E-Bike", 76.0, 22.0},
            {"RDR-04", "Suresh K", "Van", 30.0, 74.0},
            {"RDR-05", "Meena S", "Scooter", 82.0, 78.0}
        };

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO riders (rider_code, rider_name, vehicle_type, pos_x, pos_y, status) VALUES (?, ?, ?, ?, ?, 'AVAILABLE')")) {
            for (Object[] r : riders) {
                ps.setString(1, (String) r[0]);
                ps.setString(2, (String) r[1]);
                ps.setString(3, (String) r[2]);
                ps.setDouble(4, (Double) r[3]);
                ps.setDouble(5, (Double) r[4]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        // Seed 2 dispatched deliveries and 1 unassigned express order ready for dispatch
        createOrder("Pranav Nair", "T-Nagar Hub", "Adyar Sector 4", 45.0, 35.0, 84.0, 68.0, 25, true);
        createOrder("Lakshmi Menon", "Guindy Cloud Kitchen", "Velachery Main", 22.0, 65.0, 58.0, 86.0, 20, true);
        createOrder("Rohan Das", "Anna Nagar DarkStore", "Kilpauk Garden", 74.0, 25.0, 35.0, 20.0, 15, false);
    }

    public synchronized String createOrder(
            String customerName, String pickupHub, String dropZone,
            double pickupX, double pickupY, double dropX, double dropY,
            int slaMins, boolean autoDispatch) throws SQLException {
        conn.setAutoCommit(false);
        long orderId;
        String trackingCode;
        try {
            String tempCode = "TMP-" + System.nanoTime();
            try (PreparedStatement ps = conn.prepareStatement(
                    """
                    INSERT INTO deliveries (tracking_code, customer_name, pickup_hub, drop_zone, pickup_x, pickup_y, drop_x, drop_y, status, sla_mins, created_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'UNASSIGNED', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, tempCode);
                ps.setString(2, customerName);
                ps.setString(3, pickupHub);
                ps.setString(4, dropZone);
                ps.setDouble(5, clampCoord(pickupX));
                ps.setDouble(6, clampCoord(pickupY));
                ps.setDouble(7, clampCoord(dropX));
                ps.setDouble(8, clampCoord(dropY));
                ps.setInt(9, Math.max(5, slaMins));
                ps.setLong(10, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    orderId = keys.next() ? keys.getLong(1) : 1L;
                }
            }

            trackingCode = String.format("TRK-%04d", 1000 + orderId);
            try (PreparedStatement upd = conn.prepareStatement("UPDATE deliveries SET tracking_code = ? WHERE id = ?")) {
                upd.setString(1, trackingCode);
                upd.setLong(2, orderId);
                upd.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }

        if (autoDispatch) {
            return dispatchNearestRider(orderId);
        }
        return String.format("{\"ok\":true,\"orderId\":%d,\"trackingCode\":\"%s\",\"status\":\"UNASSIGNED\"}", orderId, trackingCode);
    }

    public synchronized String dispatchNearestRider(long orderId) throws SQLException {
        conn.setAutoCommit(false);
        try {
            double pickupX, pickupY;
            String status;
            String trackingCode;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT tracking_code, pickup_x, pickup_y, status FROM deliveries WHERE id = ?")) {
                ps.setLong(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return "{\"ok\":false,\"error\":\"Delivery order not found\"}";
                    }
                    trackingCode = rs.getString("tracking_code");
                    pickupX = rs.getDouble("pickup_x");
                    pickupY = rs.getDouble("pickup_y");
                    status = rs.getString("status");
                }
            }

            if (!"UNASSIGNED".equals(status)) {
                conn.rollback();
                return "{\"ok\":false,\"error\":\"Order " + JsonUtil.escape(trackingCode) + " is already " + status + "\"}";
            }

            // Select closest AVAILABLE rider by Euclidean distance to pickup hub
            String bestRider = null;
            String bestRiderName = null;
            double bestDistance = Double.MAX_VALUE;

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT rider_code, rider_name, pos_x, pos_y FROM riders WHERE status = 'AVAILABLE'")) {
                while (rs.next()) {
                    double dist = Math.hypot(rs.getDouble("pos_x") - pickupX, rs.getDouble("pos_y") - pickupY);
                    if (dist < bestDistance) {
                        bestDistance = dist;
                        bestRider = rs.getString("rider_code");
                        bestRiderName = rs.getString("rider_name");
                    }
                }
            }

            if (bestRider == null) {
                conn.rollback();
                return "{\"ok\":false,\"error\":\"No available riders in fleet\"}";
            }

            try (PreparedStatement updD = conn.prepareStatement(
                    "UPDATE deliveries SET assigned_rider = ?, status = 'PICKING_UP' WHERE id = ?")) {
                updD.setString(1, bestRider);
                updD.setLong(2, orderId);
                updD.executeUpdate();
            }

            try (PreparedStatement updR = conn.prepareStatement(
                    "UPDATE riders SET status = 'ON_DELIVERY', active_order_id = ? WHERE rider_code = ?")) {
                updR.setLong(1, orderId);
                updR.setString(2, bestRider);
                updR.executeUpdate();
            }

            conn.commit();
            return String.format(
                "{\"ok\":true,\"orderId\":%d,\"trackingCode\":\"%s\",\"riderCode\":\"%s\",\"riderName\":\"%s\",\"distanceKm\":%.2f}",
                orderId, JsonUtil.escape(trackingCode), JsonUtil.escape(bestRider), JsonUtil.escape(bestRiderName), bestDistance * 0.12
            );
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public synchronized String stepSimulation(double stepUnits) throws SQLException {
        conn.setAutoCommit(false);
        int progressed = 0;
        int completedNow = 0;
        try {
            String sql = """
                SELECT r.rider_code, r.pos_x, r.pos_y,
                       d.id AS order_id, d.status, d.pickup_x, d.pickup_y, d.drop_x, d.drop_y
                FROM riders r
                JOIN deliveries d ON r.active_order_id = d.id
                WHERE r.status = 'ON_DELIVERY'
            """;
            List<Object[]> activeRows = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    activeRows.add(new Object[]{
                        rs.getString("rider_code"),
                        rs.getDouble("pos_x"),
                        rs.getDouble("pos_y"),
                        rs.getLong("order_id"),
                        rs.getString("status"),
                        rs.getDouble("pickup_x"),
                        rs.getDouble("pickup_y"),
                        rs.getDouble("drop_x"),
                        rs.getDouble("drop_y")
                    });
                }
            }

            for (Object[] row : activeRows) {
                String riderCode = (String) row[0];
                double rx = (Double) row[1];
                double ry = (Double) row[2];
                long orderId = (Long) row[3];
                String status = (String) row[4];
                double targetX = "PICKING_UP".equals(status) ? (Double) row[5] : (Double) row[7];
                double targetY = "PICKING_UP".equals(status) ? (Double) row[6] : (Double) row[8];

                double dx = targetX - rx;
                double dy = targetY - ry;
                double dist = Math.hypot(dx, dy);
                progressed++;

                if (dist <= stepUnits) {
                    if ("PICKING_UP".equals(status)) {
                        try (PreparedStatement updR = conn.prepareStatement("UPDATE riders SET pos_x = ?, pos_y = ? WHERE rider_code = ?")) {
                            updR.setDouble(1, targetX);
                            updR.setDouble(2, targetY);
                            updR.setString(3, riderCode);
                            updR.executeUpdate();
                        }
                        try (PreparedStatement updD = conn.prepareStatement("UPDATE deliveries SET status = 'IN_TRANSIT' WHERE id = ?")) {
                            updD.setLong(1, orderId);
                            updD.executeUpdate();
                        }
                    } else {
                        finishOrderLocked(orderId, riderCode, targetX, targetY);
                        completedNow++;
                    }
                } else {
                    double nx = rx + (dx / dist) * stepUnits;
                    double ny = ry + (dy / dist) * stepUnits;
                    try (PreparedStatement updR = conn.prepareStatement("UPDATE riders SET pos_x = ?, pos_y = ? WHERE rider_code = ?")) {
                        updR.setDouble(1, nx);
                        updR.setDouble(2, ny);
                        updR.setString(3, riderCode);
                        updR.executeUpdate();
                    }
                }
            }

            conn.commit();
            return String.format("{\"ok\":true,\"progressed\":%d,\"completedNow\":%d}", progressed, completedNow);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public synchronized String completeDelivery(long orderId) throws SQLException {
        conn.setAutoCommit(false);
        try {
            String status;
            String riderCode;
            double dropX, dropY;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, assigned_rider, drop_x, drop_y FROM deliveries WHERE id = ?")) {
                ps.setLong(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return "{\"ok\":false,\"error\":\"Order not found\"}";
                    }
                    status = rs.getString("status");
                    riderCode = rs.getString("assigned_rider");
                    dropX = rs.getDouble("drop_x");
                    dropY = rs.getDouble("drop_y");
                }
            }

            if ("DELIVERED".equals(status) || riderCode == null) {
                conn.rollback();
                return "{\"ok\":false,\"error\":\"Order is not actively assigned to a rider\"}";
            }

            finishOrderLocked(orderId, riderCode, dropX, dropY);
            conn.commit();
            return "{\"ok\":true,\"orderId\":" + orderId + "}";
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private void finishOrderLocked(long orderId, String riderCode, double finalX, double finalY) throws SQLException {
        try (PreparedStatement updD = conn.prepareStatement(
                "UPDATE deliveries SET status = 'DELIVERED', delivered_at_ms = ? WHERE id = ?")) {
            updD.setLong(1, System.currentTimeMillis());
            updD.setLong(2, orderId);
            updD.executeUpdate();
        }
        try (PreparedStatement updR = conn.prepareStatement(
                "UPDATE riders SET status = 'AVAILABLE', active_order_id = NULL, pos_x = ?, pos_y = ?, completed_count = completed_count + 1 WHERE rider_code = ?")) {
            updR.setDouble(1, finalX);
            updR.setDouble(2, finalY);
            updR.setString(3, riderCode);
            updR.executeUpdate();
        }
    }

    private double clampCoord(double v) {
        return Math.max(5.0, Math.min(95.0, v));
    }

    public synchronized String getDashboardStateJson() throws SQLException {
        int totalRiders = 0, availableRiders = 0, activeOrders = 0, deliveredCount = 0, slaBreaches = 0;
        long now = System.currentTimeMillis();

        List<String> riderJsons = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM riders ORDER BY rider_code ASC")) {
            while (rs.next()) {
                totalRiders++;
                String status = rs.getString("status");
                if ("AVAILABLE".equals(status)) availableRiders++;
                riderJsons.add(String.format(
                    "{\"riderCode\":\"%s\",\"riderName\":\"%s\",\"vehicleType\":\"%s\",\"x\":%.1f,\"y\":%.1f,\"status\":\"%s\",\"activeOrderId\":%d,\"completedCount\":%d}",
                    JsonUtil.escape(rs.getString("rider_code")),
                    JsonUtil.escape(rs.getString("rider_name")),
                    JsonUtil.escape(rs.getString("vehicle_type")),
                    rs.getDouble("pos_x"),
                    rs.getDouble("pos_y"),
                    JsonUtil.escape(status),
                    rs.getLong("active_order_id"),
                    rs.getInt("completed_count")
                ));
            }
        }

        List<String> deliveryJsons = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM deliveries ORDER BY CASE status WHEN 'UNASSIGNED' THEN 1 WHEN 'PICKING_UP' THEN 2 WHEN 'IN_TRANSIT' THEN 3 ELSE 4 END, id DESC LIMIT 15")) {
            while (rs.next()) {
                String status = rs.getString("status");
                long createdMs = rs.getLong("created_at_ms");
                long endMs = "DELIVERED".equals(status) ? rs.getLong("delivered_at_ms") : now;
                double elapsedMins = Math.max(0.0, (endMs - createdMs) / 60000.0);
                int slaMins = rs.getInt("sla_mins");
                boolean breached = elapsedMins > slaMins;

                if (!"DELIVERED".equals(status)) {
                    activeOrders++;
                    if (breached) slaBreaches++;
                } else {
                    deliveredCount++;
                }

                deliveryJsons.add(String.format(
                    "{\"id\":%d,\"trackingCode\":\"%s\",\"customerName\":\"%s\",\"pickupHub\":\"%s\",\"dropZone\":\"%s\",\"pickupX\":%.1f,\"pickupY\":%.1f,\"dropX\":%.1f,\"dropY\":%.1f,\"assignedRider\":\"%s\",\"status\":\"%s\",\"slaMins\":%d,\"elapsedMins\":%.1f,\"slaBreached\":%b}",
                    rs.getLong("id"),
                    JsonUtil.escape(rs.getString("tracking_code")),
                    JsonUtil.escape(rs.getString("customer_name")),
                    JsonUtil.escape(rs.getString("pickup_hub")),
                    JsonUtil.escape(rs.getString("drop_zone")),
                    rs.getDouble("pickup_x"),
                    rs.getDouble("pickup_y"),
                    rs.getDouble("drop_x"),
                    rs.getDouble("drop_y"),
                    JsonUtil.escape(rs.getString("assigned_rider") != null ? rs.getString("assigned_rider") : ""),
                    JsonUtil.escape(status),
                    slaMins,
                    elapsedMins,
                    breached
                ));
            }
        }

        return String.format(
            "{\"metrics\":{\"availableRiders\":%d,\"totalRiders\":%d,\"activeOrders\":%d,\"delivered\":%d,\"slaBreaches\":%d},\"riders\":[%s],\"deliveries\":[%s]}",
            availableRiders, totalRiders, activeOrders, deliveredCount, slaBreaches,
            String.join(",", riderJsons),
            String.join(",", deliveryJsons)
        );
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }
}
