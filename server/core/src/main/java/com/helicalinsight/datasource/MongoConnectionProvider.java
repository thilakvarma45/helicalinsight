package com.helicalinsight.datasource;

import com.mongodb.*;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MongoConnectionProvider manages MongoDB client connections.
 * Provides thread-safe access to MongoClient instances with connection pooling.
 * Connections are cached by their connection URI to avoid creating duplicate clients.
 *
 * @author MongoDB Integration
 */
@Component
public class MongoConnectionProvider {

    private static final Logger logger = LoggerFactory.getLogger(MongoConnectionProvider.class);

    /**
     * Cache of MongoClient instances keyed by connection URI.
     * Prevents creating multiple clients for the same MongoDB instance.
     */
    private static final ConcurrentHashMap<String, MongoClient> clientCache = new ConcurrentHashMap<>();

    /**
     * Creates or retrieves a cached MongoClient for the given connection parameters.
     *
     * @param connectionUri MongoDB connection URI (e.g., mongodb://host:port/database)
     * @param username      MongoDB username (can be null for unauthenticated connections)
     * @param password      MongoDB password (can be null for unauthenticated connections)
     * @param database      MongoDB database name for authentication
     * @param authMechanism Authentication mechanism (SCRAM-SHA-1, SCRAM-SHA-256, PLAIN, etc.)
     * @return A MongoClient instance
     */
    public MongoClient getMongoClient(String connectionUri, String username, String password,
                                       String database, String authMechanism) {

        String cacheKey = buildCacheKey(connectionUri, username, database);

        return clientCache.computeIfAbsent(cacheKey, key -> {
            logger.info("Creating new MongoClient for URI: {}", connectionUri);
            return createMongoClient(connectionUri, username, password, database, authMechanism);
        });
    }

    /**
     * Gets a MongoDatabase instance from the given parameters.
     *
     * @param connectionUri MongoDB connection URI
     * @param username      MongoDB username
     * @param password      MongoDB password
     * @param database      Database name
     * @param authMechanism Authentication mechanism
     * @return MongoDatabase instance
     */
    public MongoDatabase getMongoDatabase(String connectionUri, String username, String password,
                                           String database, String authMechanism) {
        MongoClient client = getMongoClient(connectionUri, username, password, database, authMechanism);

        // Extract database name from URI if not provided
        String dbName = database;
        if (StringUtils.isBlank(dbName)) {
            dbName = extractDatabaseFromUri(connectionUri);
        }

        if (StringUtils.isBlank(dbName)) {
            throw new IllegalArgumentException("Database name must be provided either in the URI or as a parameter");
        }

        return client.getDatabase(dbName);
    }

    /**
     * Tests a MongoDB connection by executing a ping command.
     *
     * @param connectionUri MongoDB connection URI
     * @param username      MongoDB username
     * @param password      MongoDB password
     * @param database      Database name
     * @param authMechanism Authentication mechanism
     * @return true if the connection is successful, false otherwise
     */
    public boolean testConnection(String connectionUri, String username, String password,
                                   String database, String authMechanism) {
        MongoClient client = null;
        try {
            client = createMongoClient(connectionUri, username, password, database, authMechanism);

            String dbName = database;
            if (StringUtils.isBlank(dbName)) {
                dbName = extractDatabaseFromUri(connectionUri);
            }
            if (StringUtils.isBlank(dbName)) {
                dbName = "admin";
            }

            MongoDatabase db = client.getDatabase(dbName);
            // Execute a ping command to test the connection
            org.bson.Document pingResult = db.runCommand(new org.bson.Document("ping", 1));
            logger.info("MongoDB connection test successful: {}", pingResult.toJson());
            return true;
        } catch (Exception e) {
            logger.error("MongoDB connection test failed: {}", e.getMessage(), e);
            return false;
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception e) {
                    logger.warn("Error closing test MongoClient: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Creates a new MongoClient with the given parameters.
     */
    private MongoClient createMongoClient(String connectionUri, String username, String password,
                                           String database, String authMechanism) {
        try {
            if (StringUtils.isNotBlank(connectionUri) && connectionUri.startsWith("mongodb")) {
                // If credentials are provided but not in the URI, build a full connection string
                if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)
                        && !connectionUri.contains("@")) {
                    String authDb = StringUtils.isNotBlank(database) ? database : "admin";
                    String authPart = username + ":" + password + "@";
                    String fullUri = connectionUri.replace("mongodb://", "mongodb://" + authPart);

                    if (StringUtils.isNotBlank(authMechanism)) {
                        String separator = fullUri.contains("?") ? "&" : "?";
                        fullUri = fullUri + separator + "authMechanism=" + authMechanism
                                + "&authSource=" + authDb;
                    }

                    return MongoClients.create(fullUri);
                }
                return MongoClients.create(connectionUri);
            }

            // Fallback: build connection from host:port
            return MongoClients.create("mongodb://localhost:27017");

        } catch (Exception e) {
            logger.error("Failed to create MongoClient: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create MongoDB connection: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the database name from a MongoDB URI.
     * Example: mongodb://host:27017/mydb -> mydb
     */
    private String extractDatabaseFromUri(String uri) {
        if (StringUtils.isBlank(uri)) {
            return null;
        }
        try {
            // Remove query parameters
            String cleanUri = uri.contains("?") ? uri.substring(0, uri.indexOf("?")) : uri;
            // Get the last path segment
            int lastSlash = cleanUri.lastIndexOf("/");
            if (lastSlash >= 0 && lastSlash < cleanUri.length() - 1) {
                String dbName = cleanUri.substring(lastSlash + 1);
                // Skip if it looks like a host:port
                if (!dbName.contains(":")) {
                    return dbName;
                }
            }
        } catch (Exception e) {
            logger.warn("Could not extract database from URI: {}", uri);
        }
        return null;
    }

    /**
     * Builds a cache key for the client cache.
     */
    private String buildCacheKey(String uri, String username, String database) {
        return uri + "|" + (username != null ? username : "") + "|" + (database != null ? database : "");
    }

    /**
     * Removes a cached MongoClient and closes it.
     *
     * @param connectionUri The connection URI key
     * @param username      The username key
     * @param database      The database key
     */
    public void closeConnection(String connectionUri, String username, String database) {
        String cacheKey = buildCacheKey(connectionUri, username, database);
        MongoClient client = clientCache.remove(cacheKey);
        if (client != null) {
            try {
                client.close();
                logger.info("Closed and removed cached MongoClient for: {}", connectionUri);
            } catch (Exception e) {
                logger.warn("Error closing MongoClient: {}", e.getMessage());
            }
        }
    }

    /**
     * Closes all cached MongoClient instances. Called during application shutdown.
     */
    public void closeAllConnections() {
        clientCache.forEach((key, client) -> {
            try {
                client.close();
            } catch (Exception e) {
                logger.warn("Error closing MongoClient: {}", e.getMessage());
            }
        });
        clientCache.clear();
        logger.info("All cached MongoClient instances closed");
    }
}
