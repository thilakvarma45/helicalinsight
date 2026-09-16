package com.helicalinsight.adhoc.services;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.helicalinsight.datasource.MongoConnectionProvider;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * MongoQueryExecutor executes MongoDB queries and returns results as JSON.
 * This service integrates with Helical Insight's data pipeline to enable
 * MongoDB data to be used in reports and dashboards.
 *
 * <p>Supports MongoDB native query format (JSON documents) for filtering,
 * projection, sorting, and pagination.</p>
 *
 * <p>Example query specification:</p>
 * <pre>
 * {
 *   "collection": "users",
 *   "filter": {"age": {"$gt": 25}},
 *   "projection": {"name": 1, "age": 1},
 *   "sort": {"name": 1},
 *   "limit": 100
 * }
 * </pre>
 *
 * @author MongoDB Integration
 */
@Service
public class MongoQueryExecutor {

    private static final Logger logger = LoggerFactory.getLogger(MongoQueryExecutor.class);

    private static final int DEFAULT_LIMIT = 1000;
    private static final int MAX_LIMIT = 10000;

    @Autowired
    private MongoConnectionProvider connectionProvider;

    /**
     * Executes a MongoDB query and returns the results as a JSON array.
     *
     * @param connectionUri MongoDB connection URI
     * @param username      MongoDB username
     * @param password      MongoDB password
     * @param database      Database name
     * @param collection    Collection name
     * @param filterJson    MongoDB filter document as JSON string (optional)
     * @param projectionJson MongoDB projection document as JSON string (optional)
     * @param sortJson      MongoDB sort document as JSON string (optional)
     * @param limit         Maximum number of documents to return (default: 1000)
     * @param skip          Number of documents to skip (default: 0)
     * @return JsonArray containing the query results
     */
    public JsonArray executeQuery(String connectionUri, String username, String password,
                                   String database, String collection, String filterJson,
                                   String projectionJson, String sortJson,
                                   int limit, int skip) {

        if (StringUtils.isBlank(collection)) {
            throw new IllegalArgumentException("Collection name is required for MongoDB queries");
        }

        // Apply default and max limits
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
        if (skip < 0) {
            skip = 0;
        }

        logger.info("Executing MongoDB query - database: {}, collection: {}, limit: {}, skip: {}",
                database, collection, limit, skip);

        MongoDatabase mongoDatabase = connectionProvider.getMongoDatabase(
                connectionUri, username, password, database, null);

        MongoCollection<Document> mongoCollection = mongoDatabase.getCollection(collection);

        // Build the query
        Document filter = StringUtils.isNotBlank(filterJson)
                ? Document.parse(filterJson) : new Document();
        Document projection = StringUtils.isNotBlank(projectionJson)
                ? Document.parse(projectionJson) : null;
        Document sort = StringUtils.isNotBlank(sortJson)
                ? Document.parse(sortJson) : null;

        FindIterable<Document> findIterable = mongoCollection.find(filter);

        if (projection != null) {
            findIterable = findIterable.projection(projection);
        }
        if (sort != null) {
            findIterable = findIterable.sort(sort);
        }
        findIterable = findIterable.skip(skip).limit(limit);

        // Convert results to JSON array
        JsonArray results = new JsonArray();
        Gson gson = new Gson();

        try (MongoCursor<Document> cursor = findIterable.iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                JsonObject jsonObj = gson.fromJson(doc.toJson(), JsonObject.class);
                results.add(jsonObj);
            }
        }

        logger.info("MongoDB query returned {} documents", results.size());
        return results;
    }

    /**
     * Executes a MongoDB query using a query specification JSON object.
     *
     * @param connectionUri MongoDB connection URI
     * @param username      MongoDB username
     * @param password      MongoDB password
     * @param database      Database name
     * @param querySpec     JSON object containing query specification:
     *                      - collection (required): Collection name
     *                      - filter (optional): MongoDB filter document
     *                      - projection (optional): Fields to include/exclude
     *                      - sort (optional): Sort specification
     *                      - limit (optional): Max documents to return
     *                      - skip (optional): Documents to skip
     * @return JsonArray containing the query results
     */
    public JsonArray executeQuery(String connectionUri, String username, String password,
                                   String database, JsonObject querySpec) {

        String collection = querySpec.has("collection")
                ? querySpec.get("collection").getAsString() : null;
        String filterJson = querySpec.has("filter")
                ? querySpec.get("filter").toString() : null;
        String projectionJson = querySpec.has("projection")
                ? querySpec.get("projection").toString() : null;
        String sortJson = querySpec.has("sort")
                ? querySpec.get("sort").toString() : null;
        int limit = querySpec.has("limit")
                ? querySpec.get("limit").getAsInt() : DEFAULT_LIMIT;
        int skip = querySpec.has("skip")
                ? querySpec.get("skip").getAsInt() : 0;

        return executeQuery(connectionUri, username, password, database,
                collection, filterJson, projectionJson, sortJson, limit, skip);
    }

    /**
     * Counts documents in a MongoDB collection matching a filter.
     *
     * @param connectionUri MongoDB connection URI
     * @param username      MongoDB username
     * @param password      MongoDB password
     * @param database      Database name
     * @param collection    Collection name
     * @param filterJson    MongoDB filter document as JSON string (optional)
     * @return Number of matching documents
     */
    public long countDocuments(String connectionUri, String username, String password,
                                String database, String collection, String filterJson) {

        if (StringUtils.isBlank(collection)) {
            throw new IllegalArgumentException("Collection name is required");
        }

        MongoDatabase mongoDatabase = connectionProvider.getMongoDatabase(
                connectionUri, username, password, database, null);

        MongoCollection<Document> mongoCollection = mongoDatabase.getCollection(collection);

        Document filter = StringUtils.isNotBlank(filterJson)
                ? Document.parse(filterJson) : new Document();

        return mongoCollection.countDocuments(filter);
    }

    /**
     * Lists all collection names in a MongoDB database.
     *
     * @param connectionUri MongoDB connection URI
     * @param username      MongoDB username
     * @param password      MongoDB password
     * @param database      Database name
     * @return JsonArray of collection names
     */
    public JsonArray listCollections(String connectionUri, String username, String password,
                                      String database) {

        MongoDatabase mongoDatabase = connectionProvider.getMongoDatabase(
                connectionUri, username, password, database, null);

        JsonArray collections = new JsonArray();
        for (String name : mongoDatabase.listCollectionNames()) {
            collections.add(name);
        }

        logger.info("Listed {} collections in database: {}", collections.size(), database);
        return collections;
    }
}
