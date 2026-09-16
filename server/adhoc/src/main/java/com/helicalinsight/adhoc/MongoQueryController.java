package com.helicalinsight.adhoc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.helicalinsight.adhoc.services.MongoQueryExecutor;
import com.helicalinsight.datasource.GsonUtility;
import com.helicalinsight.datasource.MongoConnectionProvider;
import com.helicalinsight.datasource.model.DSTypeNoSQL;
import com.helicalinsight.datasource.service.GlobalConnectionService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * MongoQueryController provides REST endpoints for MongoDB operations within Helical Insight.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code POST /mongo/query} - Execute a MongoDB query against a configured data source</li>
 *   <li>{@code POST /mongo/collections} - List collections in a MongoDB database</li>
 *   <li>{@code POST /mongo/testConnection} - Test a MongoDB connection</li>
 * </ul>
 *
 * @author MongoDB Integration
 */
@Controller
public class MongoQueryController {

    private static final Logger logger = LoggerFactory.getLogger(MongoQueryController.class);

    @Autowired
    private MongoQueryExecutor mongoQueryExecutor;

    @Autowired
    private MongoConnectionProvider mongoConnectionProvider;

    @Autowired
    private GlobalConnectionService globalConnectionService;

    /**
     * Executes a MongoDB query against a configured data source.
     *
     * @param dataSourceId  The global data source ID
     * @param collection    The MongoDB collection to query
     * @param filter        MongoDB filter document as JSON string (optional)
     * @param projection    MongoDB projection document as JSON string (optional)
     * @param sort          MongoDB sort document as JSON string (optional)
     * @param limit         Maximum number of documents to return (optional, default: 1000)
     * @param skip          Number of documents to skip (optional, default: 0)
     * @return JSON response containing the query results
     */
    @RequestMapping(value = "/mongo/query", method = RequestMethod.POST,
            produces = {"application/json"})
    public ResponseEntity<?> executeQuery(
            @RequestParam("dataSourceId") int dataSourceId,
            @RequestParam(value = "collection", required = false) String collection,
            @RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "projection", required = false) String projection,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "limit", required = false, defaultValue = "1000") int limit,
            @RequestParam(value = "skip", required = false, defaultValue = "0") int skip) {

        try {
            DSTypeNoSQL noSqlConnection = globalConnectionService.getNoSQLConnectionById(dataSourceId);
            if (noSqlConnection == null) {
                return errorResponse("Data source not found with ID: " + dataSourceId);
            }

            String connectionUri = noSqlConnection.getUrl();
            String username = noSqlConnection.getUsername();
            String password = noSqlConnection.getPassword();
            String database = noSqlConnection.getDatabaseName();

            // Use collection from request, fall back to datasource default
            if (StringUtils.isBlank(collection)) {
                collection = noSqlConnection.getCollection();
            }

            JsonArray results = mongoQueryExecutor.executeQuery(
                    connectionUri, username, password, database,
                    collection, filter, projection, sort, limit, skip);

            JsonObject response = new JsonObject();
            response.addProperty("status", 1);
            response.add("data", results);
            response.addProperty("count", results.size());

            return ResponseEntity.ok(response.toString());

        } catch (Exception e) {
            logger.error("Error executing MongoDB query: {}", e.getMessage(), e);
            return errorResponse("Error executing MongoDB query: " + e.getMessage());
        }
    }

    /**
     * Lists all collections in a MongoDB database for the given data source.
     *
     * @param dataSourceId The global data source ID
     * @return JSON response containing the list of collection names
     */
    @RequestMapping(value = "/mongo/collections", method = RequestMethod.POST,
            produces = {"application/json"})
    public ResponseEntity<?> listCollections(@RequestParam("dataSourceId") int dataSourceId) {

        try {
            DSTypeNoSQL noSqlConnection = globalConnectionService.getNoSQLConnectionById(dataSourceId);
            if (noSqlConnection == null) {
                return errorResponse("Data source not found with ID: " + dataSourceId);
            }

            String connectionUri = noSqlConnection.getUrl();
            String username = noSqlConnection.getUsername();
            String password = noSqlConnection.getPassword();
            String database = noSqlConnection.getDatabaseName();

            JsonArray collections = mongoQueryExecutor.listCollections(
                    connectionUri, username, password, database);

            JsonObject response = new JsonObject();
            response.addProperty("status", 1);
            response.add("collections", collections);

            return ResponseEntity.ok(response.toString());

        } catch (Exception e) {
            logger.error("Error listing MongoDB collections: {}", e.getMessage(), e);
            return errorResponse("Error listing collections: " + e.getMessage());
        }
    }

    /**
     * Tests a MongoDB connection with the provided parameters.
     *
     * @param formData JSON string containing connection parameters:
     *                 - jdbcUrl: MongoDB connection URI
     *                 - userName: MongoDB username
     *                 - password: MongoDB password
     *                 - database: Database name
     *                 - authMechanism: Authentication mechanism (optional)
     * @return JSON response indicating connection success or failure
     */
    @RequestMapping(value = "/mongo/testConnection", method = RequestMethod.POST,
            produces = {"application/json"})
    public ResponseEntity<?> testConnection(@RequestParam("formData") String formData) {

        try {
            JsonObject formJson = new Gson().fromJson(formData, JsonObject.class);

            String uri = GsonUtility.optString(formJson, "jdbcUrl");
            String username = GsonUtility.optString(formJson, "userName");
            String password = GsonUtility.optString(formJson, "password");
            String database = GsonUtility.optString(formJson, "database");
            String authMechanism = GsonUtility.optString(formJson, "authMechanism");

            if (StringUtils.isBlank(uri)) {
                return errorResponse("MongoDB connection URI (jdbcUrl) is required");
            }

            boolean isConnected = mongoConnectionProvider.testConnection(
                    uri, username, password, database, authMechanism);

            JsonObject response = new JsonObject();
            if (isConnected) {
                response.addProperty("status", 1);
                response.addProperty("message", "MongoDB connection test is successful.");
            } else {
                response.addProperty("status", 0);
                response.addProperty("message", "MongoDB connection test failed. Please check the connection details.");
            }

            return ResponseEntity.ok(response.toString());

        } catch (Exception e) {
            logger.error("Error testing MongoDB connection: {}", e.getMessage(), e);
            return errorResponse("Error testing MongoDB connection: " + e.getMessage());
        }
    }

    /**
     * Builds an error response with the given message.
     */
    private ResponseEntity<?> errorResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("status", 0);
        response.addProperty("message", message);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response.toString());
    }
}
