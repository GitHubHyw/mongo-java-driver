/*
 * Copyright (c) 2008-2014 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.async.client;

import com.mongodb.MongoException;
import com.mongodb.MongoNamespace;
import com.mongodb.WriteConcernResult;
import com.mongodb.async.MongoFuture;
import com.mongodb.async.SingleResultCallback;
import com.mongodb.async.SingleResultFuture;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.bulk.DeleteRequest;
import com.mongodb.bulk.InsertRequest;
import com.mongodb.bulk.UpdateRequest;
import com.mongodb.bulk.WriteRequest;
import com.mongodb.client.model.AggregateOptions;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.CreateIndexOptions;
import com.mongodb.client.model.DeleteManyModel;
import com.mongodb.client.model.DeleteOneModel;
import com.mongodb.client.model.DistinctOptions;
import com.mongodb.client.model.FindOneAndDeleteOptions;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.FindOptions;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.MapReduceOptions;
import com.mongodb.client.model.RenameCollectionOptions;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.UpdateManyModel;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.options.OperationOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.operation.AggregateOperation;
import com.mongodb.operation.AggregateToCollectionOperation;
import com.mongodb.operation.AsyncOperationExecutor;
import com.mongodb.operation.CountOperation;
import com.mongodb.operation.CreateIndexOperation;
import com.mongodb.operation.DeleteOperation;
import com.mongodb.operation.DistinctOperation;
import com.mongodb.operation.DropCollectionOperation;
import com.mongodb.operation.DropIndexOperation;
import com.mongodb.operation.FindAndDeleteOperation;
import com.mongodb.operation.FindAndReplaceOperation;
import com.mongodb.operation.FindAndUpdateOperation;
import com.mongodb.operation.InsertOperation;
import com.mongodb.operation.ListIndexesOperation;
import com.mongodb.operation.MapReduceToCollectionOperation;
import com.mongodb.operation.MapReduceWithInlineResultsOperation;
import com.mongodb.operation.MixedBulkWriteOperation;
import com.mongodb.operation.RenameCollectionOperation;
import com.mongodb.operation.UpdateOperation;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWrapper;
import org.bson.BsonJavaScript;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.CollectibleCodec;
import org.bson.codecs.DecoderContext;

import java.util.ArrayList;
import java.util.List;

import static com.mongodb.ReadPreference.primary;
import static com.mongodb.assertions.Assertions.notNull;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

class MongoCollectionImpl<T> implements MongoCollection<T> {
    private final MongoNamespace namespace;
    private final OperationOptions options;
    private final Class<T> clazz;
    private final AsyncOperationExecutor executor;

    MongoCollectionImpl(final MongoNamespace namespace, final Class<T> clazz,
                        final OperationOptions options, final AsyncOperationExecutor executor) {
        this.namespace = notNull("namespace", namespace);
        this.clazz = notNull("clazz", clazz);
        this.options = notNull("options", options);
        this.executor = notNull("executor", executor);
    }

    @Override
    public MongoNamespace getNamespace() {
        return namespace;
    }

    @Override
    public OperationOptions getOptions() {
        return options;
    }

    @Override
    public MongoFuture<Long> count() {
        return count(new BsonDocument(), new CountOptions());
    }

    @Override
    public MongoFuture<Long> count(final Object filter) {
        return count(filter, new CountOptions());
    }

    @Override
    public MongoFuture<Long> count(final Object filter, final CountOptions options) {
        CountOperation operation = new CountOperation(namespace)
                                          .filter(asBson(filter))
                                          .skip(options.getSkip())
                                          .limit(options.getLimit())
                                          .maxTime(options.getMaxTime(MILLISECONDS), MILLISECONDS);
        if (options.getHint() != null) {
            operation.hint(asBson(options.getHint()));
        } else if (options.getHintString() != null) {
            operation.hint(new BsonString(options.getHintString()));
        }
        return executor.execute(operation, this.options.getReadPreference());
    }

    @Override
    public MongoFuture<List<Object>> distinct(final String fieldName, final Object filter) {
        return distinct(fieldName, filter, new DistinctOptions());
    }

    @Override
    public MongoFuture<List<Object>> distinct(final String fieldName, final Object filter, final DistinctOptions options) {

        DistinctOperation operation = new DistinctOperation(namespace, fieldName)
                                      .filter(asBson(filter))
                                      .maxTime(options.getMaxTime(MILLISECONDS), MILLISECONDS);

        final SingleResultFuture<List<Object>> future = new SingleResultFuture<List<Object>>();
        executor.execute(operation, this.options.getReadPreference()).register(new SingleResultCallback<BsonArray>() {
            @Override
            public void onResult(final BsonArray result, final MongoException e) {
                if (e != null) {
                    future.init(null, e);
                } else {
                    try {
                        List<Object> distinctList = new ArrayList<Object>();
                        for (BsonValue value : result) {
                            BsonDocument bsonDocument = new BsonDocument("value", value);
                            Document document = getOptions().getCodecRegistry().get(Document.class)
                                                            .decode(new BsonDocumentReader(bsonDocument),
                                                                    DecoderContext.builder().build());
                            distinctList.add(document.get("value"));
                        }
                        future.init(distinctList, null);
                    } catch (MongoException err) {
                        future.init(null, err);
                    } catch (Throwable t) {
                        future.init(null, new MongoException("Error when decoding distinct results", t));
                    }
                }
            }
        });

        return future;
    }

    @Override
    public FindFluent<T> find() {
        return find(new BsonDocument(), clazz);
    }

    @Override
    public <C> FindFluent<C> find(final Class<C> clazz) {
        return find(new BsonDocument(), clazz);
    }

    @Override
    public FindFluent<T> find(final Object filter) {
        return find(filter, clazz);
    }

    @Override
    public <C> FindFluent<C> find(final Object filter, final Class<C> clazz) {
        return new FindFluentImpl<C>(namespace, options, executor, filter, new FindOptions(), clazz);
    }

    public MongoIterable<Document> aggregate(final List<?> pipeline) {
        return aggregate(pipeline, new AggregateOptions(), Document.class);
    }

    public <C> MongoIterable<C> aggregate(final List<?> pipeline, final Class<C> clazz) {
        return aggregate(pipeline, new AggregateOptions(), clazz);
    }

    public MongoIterable<Document> aggregate(final List<?> pipeline, final AggregateOptions options) {
        return aggregate(pipeline, options, Document.class);
    }

    public <C> MongoIterable<C> aggregate(final List<?> pipeline, final AggregateOptions options, final Class<C> clazz) {
        List<BsonDocument> aggregateList = createBsonDocumentList(pipeline);
        BsonValue outCollection = aggregateList.size() == 0 ? null : aggregateList.get(aggregateList.size() - 1).get("$out");

        if (outCollection != null) {
            AggregateToCollectionOperation operation = new AggregateToCollectionOperation(namespace, aggregateList)
                                                       .maxTime(options.getMaxTime(MILLISECONDS), MILLISECONDS)
                                                       .allowDiskUse(options.getAllowDiskUse());
            executor.execute(operation);
            return new FindFluentImpl<C>(new MongoNamespace(namespace.getDatabaseName(), outCollection.asString().getValue()),
                                         this.options, executor, new BsonDocument(), new FindOptions(), clazz);
        } else {
            return new OperationIterable<C>(new AggregateOperation<C>(namespace, aggregateList, getCodec(clazz))
                                            .maxTime(options.getMaxTime(MILLISECONDS), MILLISECONDS)
                                            .allowDiskUse(options.getAllowDiskUse())
                                            .batchSize(options.getBatchSize())
                                            .useCursor(options.getUseCursor()),
                                            this.options.getReadPreference(),
                                            executor);
        }
    }

    @Override
    public MongoIterable<Document> mapReduce(final String mapFunction, final String reduceFunction) {
        return mapReduce(mapFunction, reduceFunction, new MapReduceOptions());
    }

    @Override
    public MongoIterable<Document> mapReduce(final String mapFunction, final String reduceFunction, final MapReduceOptions options) {
        return mapReduce(mapFunction, reduceFunction, options, Document.class);
    }

    @Override
    public <C> MongoIterable<C> mapReduce(final String mapFunction, final String reduceFunction, final Class<C> clazz) {
        return mapReduce(mapFunction, reduceFunction, new MapReduceOptions(), clazz);
    }

    @Override
    public <C> MongoIterable<C> mapReduce(final String mapFunction, final String reduceFunction, final MapReduceOptions options,
                                          final Class<C> clazz) {
        if (options.isInline()) {
            MapReduceWithInlineResultsOperation<C> operation =
            new MapReduceWithInlineResultsOperation<C>(getNamespace(),
                                                       new BsonJavaScript(mapFunction),
                                                       new BsonJavaScript(reduceFunction),
                                                       getCodec(clazz))
            .filter(asBson(options.getFilter()))
            .limit(options.getLimit())
            .maxTime(options.getMaxTime(MILLISECONDS), MILLISECONDS)
            .jsMode(options.isJsMode())
            .scope(asBson(options.getScope()))
            .sort(asBson(options.getSort()))
            .verbose(options.isVerbose());
            if (options.getFinalizeFunction() != null) {
                operation.finalizeFunction(new BsonJavaScript(options.getFinalizeFunction()));
            }
            return new OperationIterable<C>(operation, this.options.getReadPreference(), executor);
        } else {
            MapReduceToCollectionOperation operation =
            new MapReduceToCollectionOperation(getNamespace(),
                                               new BsonJavaScript(mapFunction),
                                               new BsonJavaScript(reduceFunction),
                                               options.getCollectionName())
            .filter(asBson(options.getFilter()))
            .limit(options.getLimit())
            .maxTime(options.getMaxTime(MILLISECONDS), MILLISECONDS)
            .jsMode(options.isJsMode())
            .scope(asBson(options.getScope()))
            .sort(asBson(options.getSort()))
            .verbose(options.isVerbose())
            .action(options.getAction().getValue())
            .nonAtomic(options.isNonAtomic())
            .sharded(options.isSharded())
            .databaseName(options.getDatabaseName());

            if (options.getFinalizeFunction() != null) {
                operation.finalizeFunction(new BsonJavaScript(options.getFinalizeFunction()));
            }
            executor.execute(operation);

            String databaseName = options.getDatabaseName() != null ? options.getDatabaseName() : namespace.getDatabaseName();
            OperationOptions readOptions = OperationOptions.builder().readPreference(primary()).build().withDefaults(this.options);
            return new FindFluentImpl<C>(new MongoNamespace(databaseName, options.getCollectionName()), readOptions, executor,
                                         new BsonDocument(), new FindOptions(), clazz);
        }
    }

    @Override
    public MongoFuture<BulkWriteResult> bulkWrite(final List<? extends WriteModel<? extends T>> requests) {
        return bulkWrite(requests, new BulkWriteOptions());
    }

    @SuppressWarnings("unchecked")
    @Override
    public MongoFuture<BulkWriteResult> bulkWrite(final List<? extends WriteModel<? extends T>> requests, final BulkWriteOptions options) {
        List<WriteRequest> writeRequests = new ArrayList<WriteRequest>(requests.size());
        for (WriteModel<? extends T> writeModel : requests) {
            WriteRequest writeRequest;
            if (writeModel instanceof InsertOneModel) {
                InsertOneModel<T> insertOneModel = (InsertOneModel<T>) writeModel;
                if (getCodec() instanceof CollectibleCodec) {
                    ((CollectibleCodec<T>) getCodec()).generateIdIfAbsentFromDocument(insertOneModel.getDocument());
                }
                writeRequest = new InsertRequest(asBson(insertOneModel.getDocument()));
            } else if (writeModel instanceof ReplaceOneModel) {
                ReplaceOneModel<T> replaceOneModel = (ReplaceOneModel<T>) writeModel;
                writeRequest = new UpdateRequest(asBson(replaceOneModel.getFilter()), asBson(replaceOneModel.getReplacement()),
                                                 WriteRequest.Type.REPLACE)
                               .upsert(replaceOneModel.getOptions().isUpsert());
            } else if (writeModel instanceof UpdateOneModel) {
                UpdateOneModel<T> updateOneModel = (UpdateOneModel<T>) writeModel;
                writeRequest = new UpdateRequest(asBson(updateOneModel.getFilter()), asBson(updateOneModel.getUpdate()),
                                                 WriteRequest.Type.UPDATE)
                               .multi(false)
                               .upsert(updateOneModel.getOptions().isUpsert());
            } else if (writeModel instanceof UpdateManyModel) {
                UpdateManyModel<T> updateManyModel = (UpdateManyModel<T>) writeModel;
                writeRequest = new UpdateRequest(asBson(updateManyModel.getFilter()), asBson(updateManyModel.getUpdate()),
                                                 WriteRequest.Type.UPDATE)
                               .multi(true)
                               .upsert(updateManyModel.getOptions().isUpsert());
            } else if (writeModel instanceof DeleteOneModel) {
                DeleteOneModel<T> deleteOneModel = (DeleteOneModel<T>) writeModel;
                writeRequest = new DeleteRequest(asBson(deleteOneModel.getFilter())).multi(false);
            } else if (writeModel instanceof DeleteManyModel) {
                DeleteManyModel<T> deleteManyModel = (DeleteManyModel<T>) writeModel;
                writeRequest = new DeleteRequest(asBson(deleteManyModel.getFilter())).multi(true);
            } else {
                throw new UnsupportedOperationException(format("WriteModel of type %s is not supported", writeModel.getClass()));
            }

            writeRequests.add(writeRequest);
        }

        return executor.execute(new MixedBulkWriteOperation(namespace, writeRequests, options.isOrdered(),
                                                            this.options.getWriteConcern()));
    }

    @Override
    public MongoFuture<WriteConcernResult> insertOne(final T document) {
        if (getCodec() instanceof CollectibleCodec) {
            ((CollectibleCodec<T>) getCodec()).generateIdIfAbsentFromDocument(document);
        }
        List<InsertRequest> requests = new ArrayList<InsertRequest>();
        requests.add(new InsertRequest(asBson(document)));
        return executor.execute(new InsertOperation(namespace, true, options.getWriteConcern(), requests));
    }

    @Override
    public MongoFuture<WriteConcernResult> insertMany(final List<? extends T> documents) {
        return insertMany(documents, new InsertManyOptions());
    }

    @Override
    public MongoFuture<WriteConcernResult> insertMany(final List<? extends T> documents, final InsertManyOptions options) {
        List<InsertRequest> requests = new ArrayList<InsertRequest>(documents.size());
        for (T document : documents) {
            if (getCodec() instanceof CollectibleCodec) {
                ((CollectibleCodec<T>) getCodec()).generateIdIfAbsentFromDocument(document);
            }
            requests.add(new InsertRequest(asBson(document)));
        }
        return executor.execute(new InsertOperation(namespace, options.isOrdered(), this.options.getWriteConcern(), requests));
    }

    @Override
    public MongoFuture<DeleteResult> deleteOne(final Object filter) {
        return delete(filter, false);
    }

    @Override
    public MongoFuture<DeleteResult> deleteMany(final Object filter) {
        return delete(filter, true);
    }

    @Override
    public MongoFuture<UpdateResult> replaceOne(final Object filter, final T replacement) {
        return replaceOne(filter, replacement, new UpdateOptions());
    }

    @Override
    public MongoFuture<UpdateResult> replaceOne(final Object filter, final T replacement, final UpdateOptions options) {
        List<UpdateRequest> requests = new ArrayList<UpdateRequest>(1);
        requests.add(new UpdateRequest(asBson(filter), asBson(replacement), WriteRequest.Type.REPLACE).upsert(options.isUpsert()));
        return createUpdateResult(executor.execute(new UpdateOperation(namespace, true, this.options.getWriteConcern(), requests)));
    }

    @Override
    public MongoFuture<UpdateResult> updateOne(final Object filter, final Object update) {
        return updateOne(filter, update, new UpdateOptions());
    }

    @Override
    public MongoFuture<UpdateResult> updateOne(final Object filter, final Object update, final UpdateOptions options) {
        return update(filter, update, options, false);
    }

    @Override
    public MongoFuture<UpdateResult> updateMany(final Object filter, final Object update) {
        return updateMany(filter, update, new UpdateOptions());
    }

    @Override
    public MongoFuture<UpdateResult> updateMany(final Object filter, final Object update, final UpdateOptions options) {
        return update(filter, update, options, true);
    }

    @Override
    public MongoFuture<T> findOneAndDelete(final Object filter) {
        return findOneAndDelete(filter, new FindOneAndDeleteOptions());
    }

    @Override
    public MongoFuture<T> findOneAndDelete(final Object filter, final FindOneAndDeleteOptions options) {
        return executor.execute(new FindAndDeleteOperation<T>(namespace, getCodec())
                                .filter(asBson(filter))
                                .projection(asBson(options.getProjection()))
                                .sort(asBson(options.getSort())));
    }

    @Override
    public MongoFuture<T> findOneAndReplace(final Object filter, final T replacement) {
        return findOneAndReplace(filter, replacement, new FindOneAndReplaceOptions());
    }

    @Override
    public MongoFuture<T> findOneAndReplace(final Object filter, final T replacement, final FindOneAndReplaceOptions options) {
        return executor.execute(new FindAndReplaceOperation<T>(namespace, getCodec(), asBson(replacement))
                                .filter(asBson(filter))
                                .projection(asBson(options.getProjection()))
                                .sort(asBson(options.getSort()))
                                .returnOriginal(options.getReturnOriginal())
                                .upsert(options.isUpsert()));
    }

    @Override
    public MongoFuture<T> findOneAndUpdate(final Object filter, final Object update) {
        return findOneAndUpdate(filter, update, new FindOneAndUpdateOptions());
    }

    @Override
    public MongoFuture<T> findOneAndUpdate(final Object filter, final Object update, final FindOneAndUpdateOptions options) {
        return executor.execute(new FindAndUpdateOperation<T>(namespace, getCodec(), asBson(update))
                                .filter(asBson(filter))
                                .projection(asBson(options.getProjection()))
                                .sort(asBson(options.getSort()))
                                .returnOriginal(options.getReturnOriginal())
                                .upsert(options.isUpsert()));
    }

    @Override
    public MongoFuture<Void> dropCollection() {
        return executor.execute(new DropCollectionOperation(namespace));
    }

    @Override
    public MongoFuture<Void> createIndex(final Object key) {
        return createIndex(key, new CreateIndexOptions());
    }

    @Override
    public MongoFuture<Void> createIndex(final Object key, final CreateIndexOptions options) {
        return executor.execute(new CreateIndexOperation(getNamespace(), asBson(key))
                                .name(options.getName())
                                .background(options.isBackground())
                                .unique(options.isUnique())
                                .sparse(options.isSparse())
                                .expireAfterSeconds(options.getExpireAfterSeconds())
                                .version(options.getVersion())
                                .weights(asBson(options.getWeights()))
                                .defaultLanguage(options.getDefaultLanguage())
                                .languageOverride(options.getLanguageOverride())
                                .textIndexVersion(options.getTextIndexVersion())
                                .twoDSphereIndexVersion(options.getTwoDSphereIndexVersion())
                                .bits(options.getBits())
                                .min(options.getMin())
                                .max(options.getMax())
                                .bucketSize(options.getBucketSize()));
    }

    @Override
    public MongoFuture<List<Document>> getIndexes() {
        return getIndexes(Document.class);
    }

    @Override
    public <C> MongoFuture<List<C>> getIndexes(final Class<C> clazz) {
        return executor.execute(new ListIndexesOperation<C>(namespace, getCodec(clazz)), options.getReadPreference());
    }

    @Override
    public MongoFuture<Void> dropIndex(final String indexName) {
        return executor.execute(new DropIndexOperation(namespace, indexName));
    }

    @Override
    public MongoFuture<Void> dropIndexes() {
        return dropIndex("*");
    }

    @Override
    public MongoFuture<Void> renameCollection(final MongoNamespace newCollectionNamespace) {
        return renameCollection(newCollectionNamespace, new RenameCollectionOptions());
    }

    @Override
    public MongoFuture<Void> renameCollection(final MongoNamespace newCollectionNamespace,
                                              final RenameCollectionOptions options) {
        return executor.execute(new RenameCollectionOperation(getNamespace(), newCollectionNamespace)
                                .dropTarget(options.isDropTarget()));
    }

    private MongoFuture<DeleteResult> delete(final Object filter, final boolean multi) {
        final SingleResultFuture<DeleteResult> future = new SingleResultFuture<DeleteResult>();
        executor.execute(new DeleteOperation(namespace, true, options.getWriteConcern(),
                                             asList(new DeleteRequest(asBson(filter)).multi(multi)))
                        ).register(new SingleResultCallback<WriteConcernResult>() {
            @Override
            public void onResult(final WriteConcernResult result, final MongoException e) {
                if (e != null) {
                    future.init(null, e);
                } else {
                    DeleteResult deleteResult = result.wasAcknowledged() ? DeleteResult.acknowledged(result.getCount())
                                                                         : DeleteResult.unacknowledged();
                    future.init(deleteResult, null);
                }
            }
        });
        return future;
    }

    private MongoFuture<UpdateResult> update(final Object filter, final Object update,
                                             final UpdateOptions updateOptions, final boolean multi) {
        List<UpdateRequest> requests = new ArrayList<UpdateRequest>(1);
        requests.add(new UpdateRequest(asBson(filter), asBson(update), WriteRequest.Type.UPDATE)
                     .upsert(updateOptions.isUpsert()).multi(multi));
        return createUpdateResult(executor.execute(new UpdateOperation(namespace, true, options.getWriteConcern(), requests)));
    }

    // TODO modifiedCount
    private MongoFuture<UpdateResult> createUpdateResult(final MongoFuture<WriteConcernResult> writeConcernResult) {
        final SingleResultFuture<UpdateResult> future = new SingleResultFuture<UpdateResult>();
        writeConcernResult.register(new SingleResultCallback<WriteConcernResult>() {
            @Override
            public void onResult(final WriteConcernResult result, final MongoException e) {
                if (e != null) {
                    future.init(null, e);
                } else {
                    UpdateResult updateResult = result.wasAcknowledged() ? UpdateResult.acknowledged(result.getCount(), 0,
                                                                                                     result.getUpsertedId())
                                                                         : UpdateResult.unacknowledged();
                    future.init(updateResult, null);
                }
            }
        });
        return future;
    }

    private Codec<T> getCodec() {
        return getCodec(clazz);
    }

    private <C> Codec<C> getCodec(final Class<C> clazz) {
        return options.getCodecRegistry().get(clazz);
    }

    private BsonDocument asBson(final Object document) {
        return BsonDocumentWrapper.asBsonDocument(document, options.getCodecRegistry());
    }

    private <D> List<BsonDocument> createBsonDocumentList(final List<D> pipeline) {
        List<BsonDocument> aggregateList = new ArrayList<BsonDocument>(pipeline.size());
        for (D obj : pipeline) {
            aggregateList.add(asBson(obj));
        }
        return aggregateList;
    }

}