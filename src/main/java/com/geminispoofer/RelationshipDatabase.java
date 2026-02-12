package com.geminispoofer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages the SQLite database for the relationship/memory system.
 * Stores relationships, topics, summaries, and entity facts in a separate
 * database file from the main chat log.
 */
public class RelationshipDatabase {
    private Connection connection;
    private final Object dbLock = new Object();
    private final Logger logger;

    public RelationshipDatabase(Logger logger) {
        this.logger = logger;
    }

    /**
     * Initializes the database connection and creates tables if they don't exist.
     *
     * @param dbPath path to the SQLite database file
     * @throws SQLException if database initialization fails
     */
    public void init(String dbPath) throws SQLException {
        Path path = Path.of(dbPath);
        Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (Exception e) {
                throw new SQLException("Failed to create database directory for relationships", e);
            }
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("PRAGMA journal_mode=WAL");
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS relationships (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "entity_a TEXT NOT NULL," +
                    "entity_b TEXT NOT NULL," +
                    "type_a TEXT NOT NULL," +
                    "type_b TEXT NOT NULL," +
                    "sentiment REAL DEFAULT 0.0," +
                    "interaction_count INTEGER DEFAULT 0," +
                    "last_interaction TIMESTAMP," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE(entity_a, entity_b)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS relationship_topics (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "relationship_id INTEGER REFERENCES relationships(id)," +
                    "topic TEXT NOT NULL," +
                    "mention_count INTEGER DEFAULT 1," +
                    "last_mentioned TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS relationship_summaries (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "relationship_id INTEGER REFERENCES relationships(id)," +
                    "summary TEXT NOT NULL," +
                    "generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS entity_facts (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "entity_name TEXT NOT NULL," +
                    "fact TEXT NOT NULL," +
                    "confidence REAL DEFAULT 1.0," +
                    "source_relationship_id INTEGER REFERENCES relationships(id)," +
                    "learned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );
        }
    }

    /**
     * Closes the database connection.
     */
    public void close() {
        if (connection == null) {
            return;
        }
        synchronized (dbLock) {
            try {
                connection.close();
            } catch (SQLException e) {
                logger.warning("Failed to close relationship database: " + e.getMessage());
            }
        }
    }

    // ── Relationship CRUD ────────────────────────────────────────────────

    /**
     * Ensures entity_a is always lexicographically first to avoid duplicate
     * pairs stored in different order.
     */
    private static String[] normalizePair(String a, String b) {
        String la = a.toLowerCase();
        String lb = b.toLowerCase();
        if (la.compareTo(lb) <= 0) {
            return new String[]{la, lb};
        }
        return new String[]{lb, la};
    }

    /**
     * Inserts or updates a relationship between two entities, incrementing the
     * interaction count by the given weight.
     *
     * @return the relationship id
     */
    public int upsertRelationship(String entityA, String typeA,
                                   String entityB, String typeB,
                                   double weight) throws SQLException {
        String[] pair = normalizePair(entityA, entityB);
        // Ensure types match the normalized order
        String tA = pair[0].equals(entityA.toLowerCase()) ? typeA : typeB;
        String tB = pair[0].equals(entityA.toLowerCase()) ? typeB : typeA;

        synchronized (dbLock) {
            // Try to update existing
            try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE relationships SET interaction_count = interaction_count + ?, " +
                    "last_interaction = CURRENT_TIMESTAMP WHERE entity_a = ? AND entity_b = ?"
            )) {
                stmt.setDouble(1, weight);
                stmt.setString(2, pair[0]);
                stmt.setString(3, pair[1]);
                int updated = stmt.executeUpdate();
                if (updated > 0) {
                    return getRelationshipId(pair[0], pair[1]);
                }
            }

            // Insert new
            try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO relationships (entity_a, entity_b, type_a, type_b, " +
                    "interaction_count, last_interaction) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                Statement.RETURN_GENERATED_KEYS
            )) {
                stmt.setString(1, pair[0]);
                stmt.setString(2, pair[1]);
                stmt.setString(3, tA);
                stmt.setString(4, tB);
                stmt.setInt(5, (int) Math.ceil(weight));
                stmt.executeUpdate();
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getInt(1);
                    }
                }
            }
        }
        return -1;
    }

    private int getRelationshipId(String normalizedA, String normalizedB) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT id FROM relationships WHERE entity_a = ? AND entity_b = ?"
        )) {
            stmt.setString(1, normalizedA);
            stmt.setString(2, normalizedB);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                }
            }
        }
        return -1;
    }

    /**
     * Gets all relationships for a given entity, ordered by interaction count descending.
     */
    public List<RelationshipRow> getRelationshipsFor(String entity, int limit) throws SQLException {
        String lower = entity.toLowerCase();
        List<RelationshipRow> results = new ArrayList<>();
        synchronized (dbLock) {
            try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT id, entity_a, entity_b, type_a, type_b, sentiment, interaction_count, " +
                    "last_interaction FROM relationships " +
                    "WHERE entity_a = ? OR entity_b = ? " +
                    "ORDER BY interaction_count DESC LIMIT ?"
            )) {
                stmt.setString(1, lower);
                stmt.setString(2, lower);
                stmt.setInt(3, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        results.add(new RelationshipRow(
                            rs.getInt("id"),
                            rs.getString("entity_a"),
                            rs.getString("entity_b"),
                            rs.getString("type_a"),
                            rs.getString("type_b"),
                            rs.getDouble("sentiment"),
                            rs.getInt("interaction_count"),
                            rs.getString("last_interaction")
                        ));
                    }
                }
            }
        }
        return results;
    }

    /**
     * Gets the interaction count between two entities (normalized pair).
     */
    public int getInteractionCount(String entityA, String entityB) throws SQLException {
        String[] pair = normalizePair(entityA, entityB);
        synchronized (dbLock) {
            try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT interaction_count FROM relationships WHERE entity_a = ? AND entity_b = ?"
            )) {
                stmt.setString(1, pair[0]);
                stmt.setString(2, pair[1]);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("interaction_count");
                    }
                }
            }
        }
        return 0;
    }

    // ── Topics ───────────────────────────────────────────────────────────

    /**
     * Replaces all topics for a relationship with the given list.
     * Keeps top N topics by mention count.
     */
    public void replaceTopics(int relationshipId, List<String> topics, int maxTopics) throws SQLException {
        synchronized (dbLock) {
            // Delete existing topics
            try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM relationship_topics WHERE relationship_id = ?"
            )) {
                stmt.setInt(1, relationshipId);
                stmt.executeUpdate();
            }

            // Insert new topics (capped at maxTopics)
            int count = 0;
            for (String topic : topics) {
                if (count >= maxTopics) break;
                try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO relationship_topics (relationship_id, topic, mention_count, last_mentioned) " +
                        "VALUES (?, ?, 1, CURRENT_TIMESTAMP)"
                )) {
                    stmt.setInt(1, relationshipId);
                    stmt.setString(2, topic.trim().toLowerCase());
                    stmt.executeUpdate();
                }
                count++;
            }
        }
    }

    /**
     * Gets all topics for a relationship.
     */
    public List<String> getTopics(int relationshipId) throws SQLException {
        List<String> topics = new ArrayList<>();
        synchronized (dbLock) {
            try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT topic FROM relationship_topics WHERE relationship_id = ? " +
                    "ORDER BY mention_count DESC"
            )) {
                stmt.setInt(1, relationshipId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        topics.add(rs.getString("topic"));
                    }
                }
            }
        }
        return topics;
    }

    // ── Summaries ────────────────────────────────────────────────────────

    /**
     * Saves a new relationship summary (replaces previous for same relationship).
     */
    public void saveSummary(int relationshipId, String summary) throws SQLException {
        synchronized (dbLock) {
            // Delete old summaries for this relationship
            try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM relationship_summaries WHERE relationship_id = ?"
            )) {
                stmt.setInt(1, relationshipId);
                stmt.executeUpdate();
            }

            // Insert new
            try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO relationship_summaries (relationship_id, summary, generated_at) " +
                    "VALUES (?, ?, CURRENT_TIMESTAMP)"
            )) {
                stmt.setInt(1, relationshipId);
                stmt.setString(2, summary);
                stmt.executeUpdate();
            }
        }
    }

    /**
     * Gets the most recent summary for a relationship, or null.
     */
    public String getSummary(int relationshipId) throws SQLException {
        synchronized (dbLock) {
            try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT summary FROM relationship_summaries WHERE relationship_id = ? " +
                    "ORDER BY generated_at DESC LIMIT 1"
            )) {
                stmt.setInt(1, relationshipId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("summary");
                    }
                }
            }
        }
        return null;
    }

    // ── Entity Facts ─────────────────────────────────────────────────────

    /**
     * Adds a fact about an entity. Avoids exact duplicates.
     */
    public void addEntityFact(String entityName, String fact, double confidence,
                               int sourceRelationshipId) throws SQLException {
        String lower = entityName.toLowerCase();
        synchronized (dbLock) {
            // Check for duplicate
            try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT id FROM entity_facts WHERE entity_name = ? AND fact = ?"
            )) {
                stmt.setString(1, lower);
                stmt.setString(2, fact);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return; // Already exists
                    }
                }
            }

            try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO entity_facts (entity_name, fact, confidence, " +
                    "source_relationship_id, learned_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)"
            )) {
                stmt.setString(1, lower);
                stmt.setString(2, fact);
                stmt.setDouble(3, confidence);
                stmt.setInt(4, sourceRelationshipId);
                stmt.executeUpdate();
            }
        }
    }

    /**
     * Gets all known facts about an entity.
     */
    public List<String> getEntityFacts(String entityName) throws SQLException {
        String lower = entityName.toLowerCase();
        List<String> facts = new ArrayList<>();
        synchronized (dbLock) {
            try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT fact FROM entity_facts WHERE entity_name = ? ORDER BY confidence DESC, learned_at DESC"
            )) {
                stmt.setString(1, lower);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        facts.add(rs.getString("fact"));
                    }
                }
            }
        }
        return facts;
    }

    // ── Decay ────────────────────────────────────────────────────────────

    /**
     * Decays stale relationships: reduces interaction_count for relationships
     * not updated in decayDays, and removes those below minimumThreshold.
     */
    public int decayStaleRelationships(int decayDays, double decayRate, int minimumThreshold) throws SQLException {
        int removed = 0;
        synchronized (dbLock) {
            // Decay interaction counts for stale relationships
            try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE relationships SET interaction_count = CAST(interaction_count * (1.0 - ?) AS INTEGER) " +
                    "WHERE last_interaction < datetime('now', '-' || ? || ' days')"
            )) {
                stmt.setDouble(1, decayRate);
                stmt.setInt(2, decayDays);
                stmt.executeUpdate();
            }

            // Get IDs of relationships to remove
            List<Integer> toRemove = new ArrayList<>();
            try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT id FROM relationships WHERE interaction_count < ?"
            )) {
                stmt.setInt(1, minimumThreshold);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        toRemove.add(rs.getInt("id"));
                    }
                }
            }

            // Remove related data and the relationships themselves
            for (int id : toRemove) {
                try (PreparedStatement stmt = connection.prepareStatement(
                    "DELETE FROM relationship_topics WHERE relationship_id = ?"
                )) {
                    stmt.setInt(1, id);
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = connection.prepareStatement(
                    "DELETE FROM relationship_summaries WHERE relationship_id = ?"
                )) {
                    stmt.setInt(1, id);
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = connection.prepareStatement(
                    "DELETE FROM entity_facts WHERE source_relationship_id = ?"
                )) {
                    stmt.setInt(1, id);
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = connection.prepareStatement(
                    "DELETE FROM relationships WHERE id = ?"
                )) {
                    stmt.setInt(1, id);
                    stmt.executeUpdate();
                }
                removed++;
            }
        }
        return removed;
    }

    // ── Data record ──────────────────────────────────────────────────────

    public record RelationshipRow(
        int id,
        String entityA,
        String entityB,
        String typeA,
        String typeB,
        double sentiment,
        int interactionCount,
        String lastInteraction
    ) {
        /**
         * Returns the "other" entity name given one entity.
         */
        public String otherEntity(String self) {
            String lower = self.toLowerCase();
            if (entityA.equals(lower)) return entityB;
            return entityA;
        }
    }
}
