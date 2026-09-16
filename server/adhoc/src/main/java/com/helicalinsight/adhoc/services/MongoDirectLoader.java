package com.helicalinsight.adhoc.services;

import com.google.gson.JsonObject;
import com.helicalinsight.datasource.GsonUtility;
import com.helicalinsight.datasource.MongoConnectionProvider;
import com.helicalinsight.datasource.nosql.NoSQLLoader;
import com.helicalinsight.efw.framework.utils.ApplicationContextAccessor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * MongoDirectLoader provides direct MongoDB connectivity support for Helical Insight.
 * Unlike the deprecated {@link MongoDrillLoader} which routes through Apache Drill,
 * this implementation connects directly to MongoDB using the MongoDB Java Driver.
 *
 * <p>This class extends {@link NoSQLLoader} and implements both connection testing
 * and middleware loading for MongoDB data sources. It is registered as a Spring
 * component with the bean name "com.helicalinsight.nosql.mongodb".</p>
 *
 * <p>Usage: When a user creates a MongoDB data source in the Helical Insight admin panel
 * with driver name "com.helicalinsight.nosql.mongodb", this loader is automatically
 * resolved via {@link com.helicalinsight.efw.utility.NoSqlUtils#getNoSqlImplementation(String)}</p>
 *
 * @author MongoDB Integration
 */
@Component("com.helicalinsight.nosql.mongodb")
@Scope("prototype")
public class MongoDirectLoader extends NoSQLLoader {

    private static final Logger logger = LoggerFactory.getLogger(MongoDirectLoader.class);

    /**
     * Loads MongoDB connection configuration to the middleware.
     * Stores the connection metadata so it can be retrieved later for query execution.
     *
     * @param formDataJson JSON object containing the MongoDB connection details:
     *                     - userName: MongoDB username
     *                     - password: MongoDB password
     *                     - jdbcUrl: MongoDB connection URI (e.g., mongodb://host:port/database)
     *                     - name: Data source name
     *                     - theId: Data source ID
     *                     - database: Database name
     *                     - collection: Default collection name
     * @return true if the connection was successfully configured
     */
    @Override
    public boolean loadToMiddleWare(JsonObject formDataJson) {
        logger.info("Loading MongoDB direct connection to middleware");

        String username = GsonUtility.optString(formDataJson, "userName");
        String password = GsonUtility.optString(formDataJson, "password");
        String jdbcUrl = GsonUtility.optString(formDataJson, "jdbcUrl");
        String database = GsonUtility.optString(formDataJson, "database");
        String collection = GsonUtility.optString(formDataJson, "collection");
        String storageName = GsonUtility.optString(formDataJson, "name");
        String theId = GsonUtility.optString(formDataJson, "theId");

        if (StringUtils.isBlank(jdbcUrl)) {
            logger.warn("MongoDB connection URI is blank, using default");
            jdbcUrl = "mongodb://localhost:27017";
        }

        logger.info("MongoDB direct connection configured - name: {}, id: {}, database: {}, collection: {}",
                storageName, theId, database, collection);

        // Verify the connection is valid by testing it
        try {
            MongoConnectionProvider provider = ApplicationContextAccessor.getBean(MongoConnectionProvider.class);
            boolean isValid = provider.testConnection(jdbcUrl, username, password, database, null);
            if (!isValid) {
                logger.warn("MongoDB connection test failed during loadToMiddleWare for: {}", storageName);
            }
        } catch (Exception e) {
            logger.warn("Could not verify MongoDB connection during load: {}", e.getMessage());
            // Don't fail the load - the connection details are still saved
        }

        return true;
    }

    /**
     * Tests the connection to a MongoDB instance.
     * Uses the MongoDB Java Driver to attempt a ping command against the database.
     *
     * @param formData JSON object containing the MongoDB connection details:
     *                 - jdbcUrl: MongoDB connection URI
     *                 - userName: MongoDB username
     *                 - password: MongoDB password
     *                 - database or databaseName: Database name
     *                 - authMechanism: Authentication mechanism (optional)
     *                 - timeOut: Connection timeout (optional)
     *                 - maxWait: Max wait time (optional)
     * @return true if the connection is successful
     */
    @Override
    public boolean testConnection(JsonObject formData) {
        String uri = GsonUtility.optString(formData, "jdbcUrl");
        String database = GsonUtility.optString(formData, "database");
        String username = GsonUtility.optString(formData, "userName");
        String password = GsonUtility.optString(formData, "password");
        String authMechanism = GsonUtility.optString(formData, "authMechanism");

        if (StringUtils.isEmpty(database)) {
            database = GsonUtility.optString(formData, "databaseName");
        }

        if (StringUtils.isBlank(uri)) {
            // Try to construct URI from host and port
            String host = GsonUtility.optString(formData, "host");
            String port = GsonUtility.optString(formData, "port");
            if (StringUtils.isBlank(host)) {
                host = "localhost";
            }
            if (StringUtils.isBlank(port)) {
                port = "27017";
            }
            uri = "mongodb://" + host + ":" + port;
            if (StringUtils.isNotBlank(database)) {
                uri = uri + "/" + database;
            }
        }

        logger.info("Testing MongoDB connection - URI: {}, database: {}, username: {}",
                uri, database, username);

        try {
            MongoConnectionProvider provider = ApplicationContextAccessor.getBean(MongoConnectionProvider.class);
            return provider.testConnection(uri, username, password, database, authMechanism);
        } catch (Exception e) {
            logger.error("MongoDB connection test failed: {}", e.getMessage(), e);
            throw new RuntimeException("MongoDB connection test failed: " + e.getMessage(), e);
        }
    }
}
