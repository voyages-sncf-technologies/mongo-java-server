package de.bwaldvogel.mongo.backend;

import static de.bwaldvogel.mongo.backend.Constants.ID_FIELD;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.bwaldvogel.mongo.MongoBackend;
import de.bwaldvogel.mongo.MongoCollection;
import de.bwaldvogel.mongo.MongoDatabase;
import de.bwaldvogel.mongo.backend.aggregation.Aggregation;
import de.bwaldvogel.mongo.backend.aggregation.stage.AddFieldsStage;
import de.bwaldvogel.mongo.backend.aggregation.stage.GroupStage;
import de.bwaldvogel.mongo.backend.aggregation.stage.LimitStage;
import de.bwaldvogel.mongo.backend.aggregation.stage.MatchStage;
import de.bwaldvogel.mongo.backend.aggregation.stage.OrderByStage;
import de.bwaldvogel.mongo.backend.aggregation.stage.ProjectStage;
import de.bwaldvogel.mongo.backend.aggregation.stage.SkipStage;
import de.bwaldvogel.mongo.backend.aggregation.stage.UnwindStage;
import de.bwaldvogel.mongo.bson.Document;
import de.bwaldvogel.mongo.exception.MongoServerError;
import de.bwaldvogel.mongo.exception.MongoServerException;
import de.bwaldvogel.mongo.exception.MongoSilentServerException;
import de.bwaldvogel.mongo.exception.NoSuchCollectionException;
import de.bwaldvogel.mongo.exception.NoSuchCommandException;
import de.bwaldvogel.mongo.wire.message.MongoDelete;
import de.bwaldvogel.mongo.wire.message.MongoInsert;
import de.bwaldvogel.mongo.wire.message.MongoQuery;
import de.bwaldvogel.mongo.wire.message.MongoUpdate;
import io.netty.channel.Channel;

public abstract class AbstractMongoDatabase<P> implements MongoDatabase {

    private static final String NAMESPACES_COLLECTION_NAME = "system.namespaces";

    private static final String INDEXES_COLLECTION_NAME = "system.indexes";

    private static final Logger log = LoggerFactory.getLogger(AbstractMongoDatabase.class);

    protected final String databaseName;
    private final MongoBackend backend;

    private final Map<String, MongoCollection<P>> collections = new ConcurrentHashMap<>();

    private final AtomicReference<MongoCollection<P>> indexes = new AtomicReference<>();

    private final Map<Channel, List<Document>> lastResults = new ConcurrentHashMap<>();

    private MongoCollection<P> namespaces;

    protected AbstractMongoDatabase(String databaseName, MongoBackend backend) {
        this.databaseName = databaseName;
        this.backend = backend;
    }

    protected void initializeNamespacesAndIndexes() {
        this.namespaces = openOrCreateCollection(NAMESPACES_COLLECTION_NAME, "name");
        this.collections.put(namespaces.getCollectionName(), namespaces);

        if (this.namespaces.count() > 0) {
            for (Document namespace : namespaces.queryAll()) {
                String name = namespace.get("name").toString();
                log.debug("opening {}", name);
                String collectionName = extractCollectionNameFromNamespace(name);
                MongoCollection<P> collection = openOrCreateCollection(collectionName, ID_FIELD);
                collections.put(collectionName, collection);
                log.debug("opened collection '{}'", collectionName);
            }

            MongoCollection<P> indexCollection = openOrCreateCollection(INDEXES_COLLECTION_NAME, null);
            indexes.set(indexCollection);
            for (Document indexDescription : indexCollection.queryAll()) {
                openOrCreateIndex(indexDescription);
            }
        }
    }

    @Override
    public final String getDatabaseName() {
        return databaseName;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + getDatabaseName() + ")";
    }

    private Document commandDropDatabase() {
        backend.dropDatabase(getDatabaseName());
        Document response = new Document("dropped", getDatabaseName());
        Utils.markOkay(response);
        return response;
    }

    @Override
    public Document handleCommand(Channel channel, String command, Document query) {

        // getlasterror must not clear the last error
        if (command.equalsIgnoreCase("getlasterror")) {
            return commandGetLastError(channel, command, query);
        } else if (command.equalsIgnoreCase("getpreverror")) {
            return commandGetPrevError(channel);
        } else if (command.equalsIgnoreCase("reseterror")) {
            return commandResetError(channel);
        }

        clearLastStatus(channel);

        if (command.equalsIgnoreCase("find")) {
            return commandFind(command, query);
        } else if (command.equalsIgnoreCase("insert")) {
            return commandInsert(channel, command, query);
        } else if (command.equalsIgnoreCase("update")) {
            return commandUpdate(channel, command, query);
        } else if (command.equalsIgnoreCase("delete")) {
            return commandDelete(channel, command, query);
        } else if (command.equalsIgnoreCase("create")) {
            return commandCreate(command, query);
        } else if (command.equalsIgnoreCase("createIndexes")) {
            return commandCreateIndexes(query);
        } else if (command.equalsIgnoreCase("count")) {
            return commandCount(command, query);
        } else if (command.equalsIgnoreCase("aggregate")) {
            return commandAggregate(command, query);
        } else if (command.equalsIgnoreCase("distinct")) {
            MongoCollection<P> collection = resolveCollection(command, query, true);
            return collection.handleDistinct(query);
        } else if (command.equalsIgnoreCase("drop")) {
            return commandDrop(query);
        } else if (command.equalsIgnoreCase("dropDatabase")) {
            return commandDropDatabase();
        } else if (command.equalsIgnoreCase("dbstats")) {
            return commandDatabaseStats();
        } else if (command.equalsIgnoreCase("collstats")) {
            MongoCollection<P> collection = resolveCollection(command, query, true);
            return collection.getStats();
        } else if (command.equalsIgnoreCase("validate")) {
            MongoCollection<P> collection = resolveCollection(command, query, true);
            return collection.validate();
        } else if (command.equalsIgnoreCase("findAndModify")) {
            String collectionName = query.get(command).toString();
            MongoCollection<P> collection = resolveOrCreateCollection(collectionName);
            return collection.findAndModify(query);
        } else if (command.equalsIgnoreCase("listCollections")) {
            return listCollections();
        } else if (command.equalsIgnoreCase("listIndexes")) {
            return listIndexes();
        } else {
            log.error("unknown query: {}", query);
        }
        throw new NoSuchCommandException(command);
    }

    private Document listCollections() {
        List<Document> firstBatch = new ArrayList<>();
        for (Document collection : namespaces.queryAll()) {
            Document collectionDescription = new Document();
            Document collectionOptions = new Document();
            String namespace = (String) collection.get("name");
            String collectionName = extractCollectionNameFromNamespace(namespace);
            collectionDescription.put("name", collectionName);
            collectionDescription.put("options", collectionOptions);
            firstBatch.add(collectionDescription);
        }

        return Utils.cursorResponse(getDatabaseName() + ".$cmd.listCollections", firstBatch);
    }

    private Document listIndexes() {
        MongoCollection<P> indexes = resolveCollection(INDEXES_COLLECTION_NAME, true);

        return Utils.cursorResponse(getDatabaseName() + ".$cmd.listIndexes", indexes.queryAll());
    }

    private synchronized MongoCollection<P> resolveOrCreateCollection(final String collectionName) {
        final MongoCollection<P> collection = resolveCollection(collectionName, false);
        if (collection != null) {
            return collection;
        } else {
            return createCollection(collectionName);
        }
    }

    private Document commandFind(String command, Document query) {

        final List<Document> documents = new ArrayList<>();
        String collectionName = (String) query.get(command);
        MongoCollection<P> collection = resolveCollection(collectionName, false);
        if (collection != null) {
            int numberToSkip = ((Number) query.getOrDefault("skip", 0)).intValue();
            int numberToReturn = ((Number) query.getOrDefault("limit", 0)).intValue();
            Document projection = (Document) query.get("projection");

            Document querySelector = new Document();
            querySelector.put("$query", query.getOrDefault("filter", new Document()));
            querySelector.put("$orderby", query.get("sort"));

            for (Document document : collection.handleQuery(querySelector, numberToSkip, numberToReturn, projection)) {
                documents.add(document);
            }
        }

        return Utils.cursorResponse(getDatabaseName() + "." + collectionName, documents);
    }

    private Document commandInsert(Channel channel, String command, Document query) {
        String collectionName = query.get(command).toString();
        boolean isOrdered = Utils.isTrue(query.get("ordered"));
        log.trace("ordered: {}", isOrdered);

        @SuppressWarnings("unchecked")
        List<Document> documents = (List<Document>) query.get("documents");

        List<Document> writeErrors = new ArrayList<>();
        int n = 0;
        for (Document document : documents) {
            try {
                insertDocuments(channel, collectionName, Collections.singletonList(document));
                n++;
            } catch (MongoServerError e) {
                Document error = new Document();
                error.put("index", Integer.valueOf(n));
                error.put("errmsg", e.getMessageWithoutErrorCode());
                error.put("code", Integer.valueOf(e.getCode()));
                error.putIfNotNull("codeName", e.getCodeName());
                writeErrors.add(error);
            }
        }
        Document result = new Document();
        result.put("n", Integer.valueOf(n));
        if (!writeErrors.isEmpty()) {
            result.put("writeErrors", writeErrors);
        }
        // odd by true: also mark error as okay
        Utils.markOkay(result);
        return result;
    }

    private Document commandUpdate(Channel channel, String command, Document query) {
        String collectionName = query.get(command).toString();
        boolean isOrdered = Utils.isTrue(query.get("ordered"));
        log.trace("ordered: {}", isOrdered);

        @SuppressWarnings("unchecked")
        List<Document> updates = (List<Document>) query.get("updates");
        int nMatched = 0;
        int nModified = 0;
        Collection<Document> upserts = new ArrayList<>();
        for (Document updateObj : updates) {
            Document selector = (Document) updateObj.get("q");
            Document update = (Document) updateObj.get("u");
            boolean multi = Utils.isTrue(updateObj.get("multi"));
            boolean upsert = Utils.isTrue(updateObj.get("upsert"));
            final Document result = updateDocuments(channel, collectionName, selector, update, multi, upsert);
            if (result.containsKey("upserted")) {
                final Object id = result.get("upserted");
                final Document upserted = new Document("index", upserts.size());
                upserted.put(ID_FIELD, id);
                upserts.add(upserted);
            }
            nMatched += ((Integer) result.get("n")).intValue();
            nModified += ((Integer) result.get("nModified")).intValue();
        }

        Document response = new Document();
        response.put("n", nMatched);
        response.put("nModified", nModified);
        if (!upserts.isEmpty()) {
            response.put("upserted", upserts);
        }
        Utils.markOkay(response);
        putLastResult(channel, response);
        return response;
    }

    private Document commandDelete(Channel channel, String command, Document query) {
        String collectionName = query.get(command).toString();
        boolean isOrdered = Utils.isTrue(query.get("ordered"));
        log.trace("ordered: {}", isOrdered);

        @SuppressWarnings("unchecked")
        List<Document> deletes = (List<Document>) query.get("deletes");
        int n = 0;
        for (Document delete : deletes) {
            final Document selector = (Document) delete.get("q");
            final int limit = ((Number) delete.get("limit")).intValue();
            Document result = deleteDocuments(channel, collectionName, selector, limit);
            Integer resultNumber = (Integer) result.get("n");
            n += resultNumber.intValue();
        }

        Document response = new Document("n", Integer.valueOf(n));
        Utils.markOkay(response);
        return response;
    }

    private Document commandCreate(String command, Document query) {
        String collectionName = query.get(command).toString();
        boolean isCapped = Utils.isTrue(query.get("capped"));
        if (isCapped) {
            throw new MongoServerException("Creating capped collections is not yet implemented");
        }

        Object autoIndexId = query.get("autoIndexId");
        if (autoIndexId != null && !Utils.isTrue(autoIndexId)) {
            throw new MongoServerException("Disabling autoIndexId is not yet implemented");
        }

        MongoCollection<P> collection = resolveCollection(collectionName, false);
        if (collection != null) {
            throw new MongoServerError(48, "collection already exists");
        }

        createCollection(collectionName);

        Document response = new Document();
        Utils.markOkay(response);
        return response;
    }

    private Document commandCreateIndexes(Document query) {
        int indexesBefore = countIndexes();

        @SuppressWarnings("unchecked")
        final Collection<Document> indexDescriptions = (Collection<Document>) query.get("indexes");
        for (Document indexDescription : indexDescriptions) {
            addIndex(indexDescription);
        }

        int indexesAfter = countIndexes();

        Document response = new Document();
        response.put("numIndexesBefore", Integer.valueOf(indexesBefore));
        response.put("numIndexesAfter", Integer.valueOf(indexesAfter));
        Utils.markOkay(response);
        return response;
    }

    private int countIndexes() {
        final MongoCollection<P> indexesCollection;
        synchronized (indexes) {
            indexesCollection = indexes.get();
        }
        if (indexesCollection == null) {
            return 0;
        } else {
            return indexesCollection.count();
        }
    }

    private Document commandDatabaseStats() {
        Document response = new Document("db", getDatabaseName());
        response.put("collections", Integer.valueOf(namespaces.count()));

        long storageSize = getStorageSize();
        long fileSize = getFileSize();
        long indexSize = 0;
        long objects = 0;
        long dataSize = 0;
        double averageObjectSize = 0;

        for (MongoCollection<P> collection : collections.values()) {
            Document stats = collection.getStats();
            objects += ((Number) stats.get("count")).longValue();
            dataSize += ((Number) stats.get("size")).longValue();

            Document indexSizes = (Document) stats.get("indexSize");
            for (String indexName : indexSizes.keySet()) {
                indexSize += ((Number) indexSizes.get(indexName)).longValue();
            }

        }
        if (objects > 0) {
            averageObjectSize = dataSize / ((double) objects);
        }
        response.put("objects", Long.valueOf(objects));
        response.put("avgObjSize", Double.valueOf(averageObjectSize));
        response.put("dataSize", Long.valueOf(dataSize));
        response.put("storageSize", Long.valueOf(storageSize));
        response.put("numExtents", Integer.valueOf(0));
        response.put("indexes", Integer.valueOf(countIndexes()));
        response.put("indexSize", Long.valueOf(indexSize));
        response.put("fileSize", Long.valueOf(fileSize));
        response.put("nsSizeMB", Integer.valueOf(0));
        Utils.markOkay(response);
        return response;
    }

    protected abstract long getFileSize();

    protected abstract long getStorageSize();

    private Document commandDrop(Document query) {
        String collectionName = query.get("drop").toString();
        MongoCollection<P> collection = collections.remove(collectionName);

        if (collection == null) {
            throw new MongoSilentServerException("ns not found");
        }
        Document response = new Document();
        namespaces.removeDocument(new Document("name", collection.getFullName()));
        response.put("nIndexesWas", Integer.valueOf(collection.getNumIndexes()));
        response.put("ns", collection.getFullName());
        Utils.markOkay(response);
        return response;

    }

    private Document commandGetLastError(Channel channel, String command, Document query) {
        Iterator<String> it = query.keySet().iterator();
        String cmd = it.next();
        if (!cmd.equals(command)) {
            throw new IllegalStateException();
        }
        if (it.hasNext()) {
            String subCommand = it.next();
            switch (subCommand) {
                case "w":
                    // ignore
                    break;
                case "fsync":
                    // ignore
                    break;
                default:
                    throw new MongoServerException("unknown subcommand: " + subCommand);
            }
        }

        List<Document> results = lastResults.get(channel);

        Document result;
        if (results != null && !results.isEmpty()) {
            result = results.get(results.size() - 1);
            if (result == null) {
                result = new Document();
            }
        } else {
            result = new Document();
            result.put("err", null);
        }
        Utils.markOkay(result);
        return result;
    }

    private Document commandGetPrevError(Channel channel) {
        List<Document> results = lastResults.get(channel);

        if (results != null) {
            for (int i = 1; i < results.size(); i++) {
                Document result = results.get(results.size() - i);
                if (result == null) {
                    continue;
                }

                boolean isRelevant = false;
                if (result.get("err") != null) {
                    isRelevant = true;
                } else if (((Number) result.get("n")).intValue() > 0) {
                    isRelevant = true;
                }

                if (isRelevant) {
                    result.put("nPrev", Integer.valueOf(i));
                    Utils.markOkay(result);
                    return result;
                }
            }
        }

        // found no prev error
        Document result = new Document();
        result.put("nPrev", Integer.valueOf(-1));
        Utils.markOkay(result);
        return result;
    }

    private Document commandResetError(Channel channel) {
        List<Document> results = lastResults.get(channel);
        if (results != null) {
            results.clear();
        }
        Document result = new Document();
        Utils.markOkay(result);
        return result;
    }

    private Document commandCount(String command, Document query) {
        MongoCollection<P> collection = resolveCollection(command, query, false);
        Document response = new Document();
        if (collection == null) {
            response.put("n", Integer.valueOf(0));
        } else {
            Document queryObject = (Document) query.get("query");
            int limit = getOptionalNumber(query, "limit", -1);
            int skip = getOptionalNumber(query, "skip", 0);
            response.put("n", Integer.valueOf(collection.count(queryObject, skip, limit)));
        }
        Utils.markOkay(response);
        return response;
    }

    private Document commandAggregate(String command, Document query) {
        String collectionName = query.get(command).toString();
        Document cursor = (Document) query.get("cursor");
        if (cursor == null) {
            throw new MongoServerError(9, "The 'cursor' option is required, except for aggregate with the explain argument");
        }
        if (!cursor.isEmpty()) {
            throw new MongoServerException("Non-empty cursor is not yet implemented");
        }

        MongoCollection<P> collection = resolveCollection(collectionName, false);

        Aggregation aggregation = new Aggregation(collection);

        @SuppressWarnings("unchecked")
        List<Document> pipeline = (List<Document>) query.get("pipeline");
        for (Document stage : pipeline) {
            if (stage.size() != 1) {
                throw new MongoServerError(40323, "A pipeline stage specification object must contain exactly one field.");
            }
            String stageOperation = stage.keySet().iterator().next();
            switch (stageOperation) {
                case "$match":
                    Document matchQuery = (Document) stage.get(stageOperation);
                    aggregation.addStage(new MatchStage(matchQuery));
                    break;
                case "$skip":
                    Number numSkip = (Number) stage.get(stageOperation);
                    aggregation.addStage(new SkipStage(numSkip.longValue()));
                    break;
                case "$limit":
                    Number numLimit = (Number) stage.get(stageOperation);
                    aggregation.addStage(new LimitStage(numLimit.longValue()));
                    break;
                case "$sort":
                    Document orderBy = (Document) stage.get(stageOperation);
                    aggregation.addStage(new OrderByStage(orderBy));
                    break;
                case "$project":
                    aggregation.addStage(new ProjectStage((Document) stage.get(stageOperation)));
                    break;
                case "$count":
                    String count = (String) stage.get(stageOperation);
                    aggregation.addStage(new GroupStage(new Document(ID_FIELD, null).append(count, new Document("$sum", 1))));
                    aggregation.addStage(new ProjectStage(new Document(ID_FIELD, 0)));
                    break;
                case "$group":
                    Document groupDetails = (Document) stage.get(stageOperation);
                    aggregation.addStage(new GroupStage(groupDetails));
                    break;
                case "$addFields":
                    Document addFieldsDetails = (Document) stage.get(stageOperation);
                    aggregation.addStage(new AddFieldsStage(addFieldsDetails));
                    break;
                case "$unwind":
                    String unwindField = (String) stage.get(stageOperation);
                    aggregation.addStage(new UnwindStage(unwindField));
                    break;
                default:
                    throw new MongoServerError(40324, "Unrecognized pipeline stage name: '" + stageOperation + "'");
            }
        }

        return Utils.cursorResponse(getDatabaseName() + "." + collectionName, aggregation.getResult());
    }

    private int getOptionalNumber(Document query, String fieldName, int defaultValue) {
        Number limitNumber = (Number) query.get(fieldName);
        return limitNumber != null ? limitNumber.intValue() : defaultValue;
    }

    @Override
    public Iterable<Document> handleQuery(MongoQuery query) {
        clearLastStatus(query.getChannel());
        String collectionName = query.getCollectionName();
        MongoCollection<P> collection = resolveCollection(collectionName, false);
        if (collection == null) {
            return Collections.emptyList();
        }
        int numSkip = query.getNumberToSkip();
        int numReturn = query.getNumberToReturn();
        Document fieldSelector = query.getReturnFieldSelector();
        return collection.handleQuery(query.getQuery(), numSkip, numReturn, fieldSelector);
    }

    @Override
    public void handleClose(Channel channel) {
        lastResults.remove(channel);
    }

    private synchronized void clearLastStatus(Channel channel) {
        List<Document> results = lastResults.computeIfAbsent(channel, k -> new LimitedList<>(10));
        results.add(null);
    }

    @Override
    public void handleInsert(MongoInsert insert) {
        Channel channel = insert.getChannel();
        String collectionName = insert.getCollectionName();
        List<Document> documents = insert.getDocuments();

        if (collectionName.equals(INDEXES_COLLECTION_NAME)) {
            for (Document indexDescription : documents) {
                addIndex(indexDescription);
            }
        } else {
            try {
                insertDocuments(channel, collectionName, documents);
            } catch (MongoServerException e) {
                log.error("failed to insert {}", insert, e);
            }
        }
    }

    private MongoCollection<P> resolveCollection(String command, Document query, boolean throwIfNotFound) {
        String collectionName = query.get(command).toString();
        return resolveCollection(collectionName, throwIfNotFound);
    }

    @Override
    public synchronized MongoCollection<P> resolveCollection(String collectionName, boolean throwIfNotFound) {
        checkCollectionName(collectionName);
        MongoCollection<P> collection = collections.get(collectionName);
        if (collection == null && throwIfNotFound) {
            throw new NoSuchCollectionException(collectionName);
        }
        return collection;
    }

    private void checkCollectionName(String collectionName) {

        if (collectionName.length() > Constants.MAX_NS_LENGTH) {
            throw new MongoServerError(10080, "ns name too long, max size is " + Constants.MAX_NS_LENGTH);
        }

        if (collectionName.isEmpty()) {
            throw new MongoServerError(16256, "Invalid ns [" + collectionName + "]");
        }
    }

    @Override
    public boolean isEmpty() {
        return collections.isEmpty();
    }

    private void addNamespace(MongoCollection<P> collection) {
        collections.put(collection.getCollectionName(), collection);
        namespaces.addDocument(new Document("name", collection.getFullName()));
    }

    @Override
    public void handleDelete(MongoDelete delete) {
        Channel channel = delete.getChannel();
        String collectionName = delete.getCollectionName();
        Document selector = delete.getSelector();
        int limit = delete.isSingleRemove() ? 1 : Integer.MAX_VALUE;

        try {
            deleteDocuments(channel, collectionName, selector, limit);
        } catch (MongoServerException e) {
            log.error("failed to delete {}", delete, e);
        }
    }

    @Override
    public void handleUpdate(MongoUpdate updateCommand) {
        Channel channel = updateCommand.getChannel();
        String collectionName = updateCommand.getCollectionName();
        Document selector = updateCommand.getSelector();
        Document update = updateCommand.getUpdate();
        boolean multi = updateCommand.isMulti();
        boolean upsert = updateCommand.isUpsert();

        try {
            Document result = updateDocuments(channel, collectionName, selector, update, multi, upsert);
            putLastResult(channel, result);
        } catch (MongoServerException e) {
            log.error("failed to update {}", updateCommand, e);
        }
    }

    private void addIndex(Document indexDescription) {
        openOrCreateIndex(indexDescription);
        getOrCreateIndexesCollection().addDocument(indexDescription);
    }

    private MongoCollection<P> getOrCreateIndexesCollection() {
        synchronized (indexes) {
            if (indexes.get() == null) {
                MongoCollection<P> indexCollection = openOrCreateCollection(INDEXES_COLLECTION_NAME, null);
                addNamespace(indexCollection);
                indexes.set(indexCollection);
            }
            return indexes.get();
        }
    }

    private String extractCollectionNameFromNamespace(String namespace) {
        if (!namespace.startsWith(databaseName)) {
            throw new IllegalArgumentException();
        }
        return namespace.substring(databaseName.length() + 1);
    }

    private void openOrCreateIndex(Document indexDescription) {
        String ns = indexDescription.get("ns").toString();
        String collectionName = extractCollectionNameFromNamespace(ns);

        MongoCollection<P> collection = resolveOrCreateCollection(collectionName);

        Document key = (Document) indexDescription.get("key");
        if (key.keySet().equals(Collections.singleton(ID_FIELD))) {
            boolean ascending = isAscending(key.get(ID_FIELD));
            collection.addIndex(openOrCreateIdIndex(collectionName, ascending));
            log.info("adding unique _id index for collection {}", collectionName);
        } else if (Utils.isTrue(indexDescription.get("unique"))) {
            List<IndexKey> keys = new ArrayList<>();
            for (Entry<String, Object> entry : key.entrySet()) {
                String field = entry.getKey();
                boolean ascending = isAscending(entry.getValue());
                keys.add(new IndexKey(field, ascending));
            }

            log.info("adding unique index {} for collection {}", keys, collectionName);

            collection.addIndex(openOrCreateUniqueIndex(collectionName, keys));
        } else {
            // TODO: non-unique non-id indexes not yet implemented
            log.warn("adding non-unique non-id index with key {} is not yet implemented", key);
        }
    }

    private static boolean isAscending(Object keyValue) {
        return Objects.equals(Utils.normalizeValue(keyValue), Double.valueOf(1.0));
    }

    private Index<P> openOrCreateIdIndex(String collectionName, boolean ascending) {
        return openOrCreateUniqueIndex(collectionName, Collections.singletonList(new IndexKey(ID_FIELD, ascending)));
    }

    protected abstract Index<P> openOrCreateUniqueIndex(String collectionName, List<IndexKey> keys);

    private void insertDocuments(Channel channel, String collectionName, List<Document> documents) {
        clearLastStatus(channel);
        try {
            if (collectionName.startsWith("system.")) {
                throw new MongoServerError(16459, "attempt to insert in system namespace");
            }
            MongoCollection<P> collection = resolveOrCreateCollection(collectionName);
            int n = collection.insertDocuments(documents);
            assert n == documents.size();
            Document result = new Document("n", Integer.valueOf(n));
            putLastResult(channel, result);
        } catch (MongoServerError e) {
            putLastError(channel, e);
            throw e;
        }
    }

    private Document deleteDocuments(Channel channel, String collectionName, Document selector, int limit) {
        clearLastStatus(channel);
        try {
            if (collectionName.startsWith("system.")) {
                throw new MongoServerError(12050, "cannot delete from system namespace");
            }
            MongoCollection<P> collection = resolveCollection(collectionName, false);
            final int n;
            if (collection == null) {
                n = 0;
            } else {
                n = collection.deleteDocuments(selector, limit);
            }
            Document result = new Document("n", Integer.valueOf(n));
            putLastResult(channel, result);
            return result;
        } catch (MongoServerError e) {
            putLastError(channel, e);
            throw e;
        }
    }

    private Document updateDocuments(Channel channel, String collectionName, Document selector,
                                     Document update, boolean multi, boolean upsert) {
        clearLastStatus(channel);
        try {
            if (collectionName.startsWith("system.")) {
                throw new MongoServerError(10156, "cannot update system collection");
            }

            MongoCollection<P> collection = resolveOrCreateCollection(collectionName);
            return collection.updateDocuments(selector, update, multi, upsert);
        } catch (MongoServerException e) {
            putLastError(channel, e);
            throw e;
        }
    }

    private void putLastError(Channel channel, MongoServerException ex) {
        Document error = new Document();
        if (ex instanceof MongoServerError) {
            MongoServerError err = (MongoServerError) ex;
            error.put("err", err.getMessageWithoutErrorCode());
            error.put("code", Integer.valueOf(err.getCode()));
            error.putIfNotNull("codeName", err.getCodeName());
        } else {
            error.put("err", ex.getMessageWithoutErrorCode());
        }
        error.put("connectionId", channel.id().asShortText());
        putLastResult(channel, error);
    }

    private synchronized void putLastResult(Channel channel, Document result) {
        List<Document> results = lastResults.get(channel);
        // list must not be empty
        Document last = results.get(results.size() - 1);
        if (last != null) {
            throw new IllegalStateException("last result already set: " + last);
        }
        results.set(results.size() - 1, result);
    }

    private MongoCollection<P> createCollection(String collectionName) {
        checkCollectionName(collectionName);
        if (collectionName.contains("$")) {
            throw new MongoServerError(10093, "cannot insert into reserved $ collection");
        }

        MongoCollection<P> collection = openOrCreateCollection(collectionName, ID_FIELD);
        addNamespace(collection);

        Document indexDescription = new Document();
        indexDescription.put("name", "_id_");
        indexDescription.put("ns", collection.getFullName());
        indexDescription.put("key", new Document(ID_FIELD, Integer.valueOf(1)));
        addIndex(indexDescription);

        log.info("created collection {}", collection.getFullName());

        return collection;
    }

    protected abstract MongoCollection<P> openOrCreateCollection(String collectionName, String idField);

    @Override
    public void drop() {
        log.debug("dropping {}", this);
        for (String collectionName : collections.keySet()) {
            dropCollection(collectionName);
        }
    }

    @Override
    public void dropCollection(String collectionName) {
        unregisterCollection(collectionName);
    }

    @Override
    public MongoCollection<P> unregisterCollection(String collectionName) {
        MongoCollection<P> removedCollection = collections.remove(collectionName);
        namespaces.deleteDocuments(new Document("name", removedCollection.getFullName()), 1);
        return removedCollection;
    }

    @Override
    public void moveCollection(MongoDatabase oldDatabase, MongoCollection<?> collection, String newCollectionName) {
        oldDatabase.unregisterCollection(collection.getCollectionName());
        collection.renameTo(getDatabaseName(), newCollectionName);
        // TODO resolve cast
        @SuppressWarnings("unchecked")
        MongoCollection<P> newCollection = (MongoCollection<P>) collection;
        collections.put(newCollectionName, newCollection);
        List<Document> newDocuments = new ArrayList<>();
        newDocuments.add(new Document("name", collection.getFullName()));
        namespaces.insertDocuments(newDocuments);
    }

}
