/**
 * Copyright Pravega Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.pravega.controller.server.eventProcessor.requesthandlers;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import io.pravega.client.stream.ScalingPolicy;
import io.pravega.common.Exceptions;
import io.pravega.common.Timer;
import io.pravega.common.concurrent.Futures;
import io.pravega.common.tracing.TagLogger;
import io.pravega.controller.metrics.TransactionMetrics;
import io.pravega.controller.store.stream.BucketStore;
import io.pravega.controller.store.stream.OperationContext;
import io.pravega.controller.store.stream.StoreException;
import io.pravega.controller.store.stream.StreamMetadataStore;
import io.pravega.controller.store.VersionedMetadata;
import io.pravega.controller.store.stream.State;
import io.pravega.controller.store.stream.TxnWriterMark;
import io.pravega.controller.store.stream.records.CommittingTransactionsRecord;
import io.pravega.controller.store.stream.records.EpochRecord;
import io.pravega.controller.task.Stream.StreamMetadataTasks;
import io.pravega.controller.task.Stream.StreamTransactionMetadataTasks;
import io.pravega.shared.controller.event.CommitEvent;
import org.apache.curator.shaded.com.google.common.base.Strings;
import org.slf4j.LoggerFactory;

import static io.pravega.shared.NameUtils.computeSegmentId;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Request handler for processing commit events in commit-stream.
 */
public class CommitRequestHandler extends AbstractRequestProcessor<CommitEvent> implements StreamTask<CommitEvent> {
    private static final TagLogger log = new TagLogger(LoggerFactory.getLogger(CommitRequestHandler.class));

    private static final int MAX_TRANSACTION_COMMIT_BATCH_SIZE = 100;

    private final StreamMetadataTasks streamMetadataTasks;
    private final StreamTransactionMetadataTasks streamTransactionMetadataTasks;
    private final BucketStore bucketStore;
    private final ScheduledExecutorService executor;
    private final BlockingQueue<CommitEvent> processedEvents;

    public CommitRequestHandler(final StreamMetadataStore streamMetadataStore,
                                final StreamMetadataTasks streamMetadataTasks,
                                final StreamTransactionMetadataTasks streamTransactionMetadataTasks,
                                BucketStore bucketStore, final ScheduledExecutorService executor) {
        this(streamMetadataStore, streamMetadataTasks, streamTransactionMetadataTasks, bucketStore, executor, null);
    }

    @VisibleForTesting
    public CommitRequestHandler(final StreamMetadataStore streamMetadataStore,
                                final StreamMetadataTasks streamMetadataTasks,
                                final StreamTransactionMetadataTasks streamTransactionMetadataTasks,
                                BucketStore bucketStore, final ScheduledExecutorService executor,
                                final BlockingQueue<CommitEvent> queue) {
        super(streamMetadataStore, executor);
        this.bucketStore = bucketStore;
        Preconditions.checkNotNull(streamMetadataStore);
        Preconditions.checkNotNull(streamMetadataTasks);
        Preconditions.checkNotNull(executor);
        this.streamMetadataTasks = streamMetadataTasks;
        this.streamTransactionMetadataTasks = streamTransactionMetadataTasks;
        this.executor = executor;
        this.processedEvents = queue;
    }

    @Override
    public CompletableFuture<Void> processCommitTxnRequest(CommitEvent event) {
        return withCompletion(this, event, event.getScope(), event.getStream(), OPERATION_NOT_ALLOWED_PREDICATE);
    }

    /**
     * This method attempts to collect all transactions in the epoch that are marked for commit and decides if they can be
     * committed in active epoch or if it needs to roll the transactions.
     *
     * @param event event to process
     * @return Completable future which indicates completion of processing of commit event.
     */
    @Override
    public CompletableFuture<Void> execute(CommitEvent event) {
        String scope = event.getScope();
        String stream = event.getStream();
        long requestId = streamMetadataTasks.getRequestId(null);

        OperationContext context = streamMetadataStore.createStreamContext(scope, stream, requestId);
        log.debug(requestId, "Attempting to commit available transactions on stream {}/{}", event.getScope(), 
                event.getStream());

        CompletableFuture<Void> future = new CompletableFuture<>();

        // Note: we will ignore the epoch in the event. It has been deprecated. 
        // The logic now finds the smallest epoch with transactions and commits them.
        tryCommitTransactions(scope, stream, context)
                .whenComplete((r, e) -> {
                    if (e != null) {
                        Throwable cause = Exceptions.unwrap(e);
                        // for operation not allowed, we will report the event
                        if (cause instanceof StoreException.OperationNotAllowedException) {
                            log.debug(requestId, "Cannot commit transaction on stream {}/{}. Postponing", 
                                    scope, stream);
                        } else {
                            log.warn(requestId, "Exception while attempting to commit transaction on stream {}/{}", 
                                    scope, stream, e);
                            TransactionMetrics.getInstance().commitTransactionFailed(scope, stream);
                        }
                        future.completeExceptionally(cause);
                    } else {
                        if (r >= 0) {
                            log.info(requestId, "Successfully committed transactions on epoch {} on stream {}/{}", r, 
                                    scope, stream);
                        } else {
                            log.info(requestId, "No transactions found in committing state on stream {}/{}",
                                    scope, stream);
                        }
                        if (processedEvents != null) {
                            try {
                                processedEvents.offer(event);
                            } catch (Exception ex) {
                                // ignore, this processed events is only added for enabling unit testing this class
                            }
                        }
                        future.complete(null);
                    }
                });

        return future;
    }

    /**
     * Try creating txn commit list first. if node already exists and doesn't match the processing in the event, throw
     * operation not allowed.
     * This will result in event being posted back in the stream and retried later. Generally if a transaction commit starts,
     * it will come to an end. However, during failover, once we have created the node, we are guaranteed that it will 
     * be only that transaction that will be getting committed at that time.
     * @return CompletableFuture which when completed will contain the epoch on which transactions were committed.  
     */
    private CompletableFuture<Integer> tryCommitTransactions(final String scope,
                                                          final String stream,
                                                          final OperationContext context) {
        Timer timer = new Timer();
        Map<String, TxnWriterMark> writerMarks = new HashMap<>();
        Map<UUID, String> txnIdToWriterId = new HashMap<>();

        return streamMetadataStore.getVersionedState(scope, stream, context, executor)
                .thenComposeAsync(state -> {
                    final AtomicReference<VersionedMetadata<State>> stateRecord = new AtomicReference<>(state);
                    CompletableFuture<VersionedMetadata<CommittingTransactionsRecord>> commitFuture =
                            streamMetadataStore.startCommitTransactions(scope, stream, MAX_TRANSACTION_COMMIT_BATCH_SIZE, 
                                    context, executor)
                            .thenComposeAsync(txnsTuple -> {
                                VersionedMetadata<CommittingTransactionsRecord> committingTxnsRecord = txnsTuple.getKey();
                                if (committingTxnsRecord.getObject().equals(CommittingTransactionsRecord.EMPTY)) {
                                    // there are no transactions found to commit.
                                    // reset state conditionally in case we were left with stale committing state from
                                    // a previous execution
                                    // that died just before updating the state back to ACTIVE but after having 
                                    // completed all the work.
                                    return CompletableFuture.completedFuture(committingTxnsRecord);
                                } else {
                                    int txnEpoch = committingTxnsRecord.getObject().getEpoch();
                                    List<UUID> txnList = committingTxnsRecord.getObject().getTransactionsToCommit();

                                    log.info(context.getRequestId(), 
                                            "Committing {} transactions on epoch {} on stream {}/{}", 
                                            txnList, txnEpoch, scope, stream);
                                    // Once state is set to committing, we are guaranteed that this will be the only 
                                    // processing that can happen on the stream and we can proceed with committing 
                                    // outstanding transactions collected in the txnList step.
                                    CompletableFuture<Void> future;
                                    // if state is sealing, we should continue with commit so that we allow for 
                                    // completion of transactions in commit state.
                                    if (state.getObject().equals(State.SEALING)) {
                                        future = CompletableFuture.completedFuture(null);
                                    } else {
                                        // If state is not SEALING, try to set the state to COMMITTING_TXN before proceeding.
                                        // If we are unable to set the state to COMMITTING_TXN, it will get OPERATION_NOT_ALLOWED
                                        // and the processing will be retried later.
                                        future = streamMetadataStore.updateVersionedState(scope, stream, State.COMMITTING_TXN,
                                                state, context, executor)
                                            .thenAccept(stateRecord::set);
                                    }

                                    txnsTuple.getValue().forEach(txn -> {
                                        if (!Strings.isNullOrEmpty(txn.getWriterId())) {
                                            txnIdToWriterId.put(txn.getId(), txn.getWriterId());
                                            if (!writerMarks.containsKey(txn.getWriterId())
                                                    || writerMarks.get(txn.getWriterId()).getTimestamp() < txn.getCommitTime()) {
                                            writerMarks.put(txn.getWriterId(),
                                                    new TxnWriterMark(txn.getCommitTime(), ImmutableMap.of(), txn.getId()));
                                            }
                                        }
                                    });
                                    // Note: since we have set the state to COMMITTING_TXN (or it was already sealing),
                                    // the active epoch that we fetch now cannot change until we perform rolling txn. 
                                    // TxnCommittingRecord ensures no other rollingTxn can run concurrently
                                    return future.thenCompose(v -> getEpochRecords(scope, stream, txnEpoch, context)
                                            .thenCompose(records -> {
                                                EpochRecord txnEpochRecord = records.get(0);
                                                EpochRecord activeEpochRecord = records.get(1);
                                                if (activeEpochRecord.getEpoch() == txnEpoch ||
                                                        activeEpochRecord.getReferenceEpoch() == txnEpochRecord.getReferenceEpoch()) {
                                                    // If active epoch's reference is same as transaction epoch,
                                                    // we can commit transactions immediately
                                                    return commitTransactions(scope, stream, 
                                                            new ArrayList<>(activeEpochRecord.getSegmentIds()), txnList, 
                                                            context, txnIdToWriterId, writerMarks)
                                                            .thenApply(txnOffsets -> committingTxnsRecord);
                                                } else {
                                                    return rollTransactions(scope, stream, txnEpochRecord, activeEpochRecord,
                                                            committingTxnsRecord, context, txnIdToWriterId, writerMarks);
                                                }
                                            }));
                                }
                            }, executor);

                    // once all commits are done, reset the committing txn record.
                    // reset state to ACTIVE if it was COMMITTING_TXN
                    return commitFuture
                            .thenCompose(committingTxnsRecord -> streamMetadataStore.completeCommitTransactions(
                                    scope, stream, committingTxnsRecord, context, executor, writerMarks)
                            .thenCompose(v -> resetStateConditionally(scope, stream, stateRecord.get(), context))
                            .thenRun(() -> TransactionMetrics.getInstance().commitTransaction(scope, stream, timer.getElapsed()))
                            .thenApply(v -> committingTxnsRecord.getObject().getEpoch()));
                }, executor);
    }

    private CompletableFuture<VersionedMetadata<CommittingTransactionsRecord>> rollTransactions(
            String scope, String stream, EpochRecord txnEpoch, EpochRecord activeEpoch,
            VersionedMetadata<CommittingTransactionsRecord> existing, OperationContext context,
            Map<UUID, String> txnIdToWriterId,
            Map<String, TxnWriterMark> writerMarks) {
        CompletableFuture<VersionedMetadata<CommittingTransactionsRecord>> future = CompletableFuture.completedFuture(existing);
        if (!existing.getObject().isRollingTxnRecord()) {
            future = future.thenCompose(
                    x -> streamMetadataStore.startRollingTxn(scope, stream, activeEpoch.getEpoch(),
                            existing, context, executor));
        }

        return future.thenCompose(record -> {
            if (activeEpoch.getEpoch() > record.getObject().getCurrentEpoch()) {
                return CompletableFuture.completedFuture(record);
            } else {
                return runRollingTxn(scope, stream, txnEpoch, activeEpoch, record, context, txnIdToWriterId, writerMarks)
                        .thenApply(v -> record);
            }
        });
    }

    private CompletableFuture<Void> runRollingTxn(String scope, String stream, EpochRecord txnEpoch,
                                                  EpochRecord activeEpoch, 
                                                  VersionedMetadata<CommittingTransactionsRecord> existing,
                                                  OperationContext context, Map<UUID, String> txnIdToWriterId,
                                                  Map<String, TxnWriterMark> writerMarks) {
        String delegationToken = streamMetadataTasks.retrieveDelegationToken();
        long timestamp = System.currentTimeMillis();

        int newTxnEpoch = existing.getObject().getNewTxnEpoch();
        int newActiveEpoch = existing.getObject().getNewActiveEpoch();

        List<Long> txnEpochDuplicate = txnEpoch.getSegments().stream().map(segment ->
                computeSegmentId(segment.getSegmentNumber(), newTxnEpoch)).collect(Collectors.toList());
        List<Long> activeEpochSegmentIds = new ArrayList<>(activeEpoch.getSegmentIds());
        List<Long> activeEpochDuplicate = activeEpoch.getSegments().stream()
                                                    .map(segment -> computeSegmentId(segment.getSegmentNumber(), 
                                                            newActiveEpoch))
                                                     .collect(Collectors.toList());
        List<UUID> transactionsToCommit = existing.getObject().getTransactionsToCommit();
        return copyTxnEpochSegmentsAndCommitTxns(scope, stream, transactionsToCommit, txnEpochDuplicate, context, txnIdToWriterId, writerMarks)
                .thenCompose(v -> streamMetadataTasks.notifyNewSegments(scope, stream, activeEpochDuplicate, context,
                        delegationToken, context.getRequestId()))
                .thenCompose(v -> streamMetadataTasks.getSealedSegmentsSize(scope, stream, txnEpochDuplicate,
                        delegationToken, context.getRequestId()))
                .thenCompose(sealedSegmentsMap -> {
                    log.info(context.getRequestId(), 
                            "Rolling transaction, created duplicate of active epoch {} for stream {}/{}", 
                            activeEpoch, scope, stream);
                    return streamMetadataStore.rollingTxnCreateDuplicateEpochs(scope, stream, sealedSegmentsMap,
                            timestamp, existing, context, executor);
                })
                .thenCompose(v -> streamMetadataTasks.notifySealedSegments(scope, stream, activeEpochSegmentIds,
                        delegationToken, context.getRequestId())
                        .thenCompose(x -> streamMetadataTasks.getSealedSegmentsSize(scope, stream, activeEpochSegmentIds,
                                delegationToken, context.getRequestId()))
                        .thenCompose(sealedSegmentsMap -> {
                            log.info(context.getRequestId(), 
                                    "Rolling transaction, sealed active epoch {} for stream {}/{}",
                                    activeEpoch, scope, stream);
                            return streamMetadataStore.completeRollingTxn(scope, stream, sealedSegmentsMap, existing,
                                    context, executor);
                        }));
    }

    /**
     * This method is called in the rolling transaction flow.
     * This method creates duplicate segments for transaction epoch. It then merges all transactions from the list into
     * those duplicate segments.
     */
    private CompletableFuture<Void> copyTxnEpochSegmentsAndCommitTxns(String scope, String stream, List<UUID> transactionsToCommit,
                                                                      List<Long> segmentIds, OperationContext context, Map<UUID, String> txnIdToWriterId,
                                                                                            Map<String, TxnWriterMark> writerMarks) {
        // 1. create duplicate segments
        // 2. merge transactions in those segments
        // 3. seal txn epoch segments
        String delegationToken = streamMetadataTasks.retrieveDelegationToken();
        CompletableFuture<Void> createSegmentsFuture = Futures.allOf(segmentIds.stream().map(segment -> {
            // Use fixed scaling policy for these segments as they are created, merged into and sealed and are not
            // supposed to auto scale.
            return streamMetadataStore.getConfiguration(scope, stream, context, executor).thenCompose(config ->
                    streamMetadataTasks.notifyNewSegment(scope, stream, segment, ScalingPolicy.fixed(1), delegationToken,
                    context.getRequestId(), config.getRolloverSizeBytes()));
        }).collect(Collectors.toList()));

        return createSegmentsFuture
                .thenCompose(v -> {
                    log.info(context.getRequestId(), 
                            "Rolling transaction, successfully created duplicate txn epoch {} for stream {}/{}", 
                            segmentIds, scope, stream);
                    // now commit transactions into these newly created segments
                    return commitTransactions(scope, stream, segmentIds, transactionsToCommit, context, txnIdToWriterId, writerMarks);
                })
                .thenAccept(v -> streamMetadataTasks.notifySealedSegments(scope, stream, segmentIds, delegationToken,
                        context.getRequestId()));
    }

    /**
     * This method loops over each transaction in the list, and commits them in order
     * At the end of this method's execution, all transactions in the list would have committed into given list of segments.
     */
    private CompletableFuture<Void> commitTransactions(String scope, String stream, List<Long> segments,
                                                                             List<UUID> transactionsToCommit, OperationContext context,
                                                                             Map<UUID, String> txnIdToWriterId,
                                                                             Map<String, TxnWriterMark> writerMarks) {
        // Chain all transaction commit futures one after the other. This will ensure that order of commit
        // if honoured and is based on the order in the list.
        boolean noteTime = writerMarks.size() > 0;
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (UUID txnId : transactionsToCommit) {
            log.info(context.getRequestId(), "Committing transaction {} on stream {}/{}", txnId, scope, stream);
            // commit transaction in segment store
            future = future
                    .thenCompose(v -> streamMetadataTasks.notifyTxnCommit(scope, stream, segments, txnId, context.getRequestId()))
                    .thenAccept(txnOffsets -> {
                        String writerId = txnIdToWriterId.get(txnId);
                        if (!Strings.isNullOrEmpty(writerId) && writerMarks.get(writerId).getTransactionId().equals(txnId)) {
                            TxnWriterMark mark = writerMarks.get(writerId);
                            writerMarks.put(writerId, new TxnWriterMark(mark.getTimestamp(), txnOffsets, mark.getTransactionId()));
                            }
                        }
            );
        }
        return future.thenAcceptAsync(x -> {
            if (noteTime) {
                bucketStore.addStreamToBucketStore(BucketStore.ServiceType.WatermarkingService, scope, stream, executor);
            }
        });
    }

    /**
     * Fetches epoch history records for active epoch and the supplied `epoch` from the store.
     */
    private CompletableFuture<List<EpochRecord>> getEpochRecords(String scope, String stream, int epoch, 
                                                                 OperationContext context) {
        List<CompletableFuture<EpochRecord>> list = new ArrayList<>();
        list.add(streamMetadataStore.getEpoch(scope, stream, epoch, context, executor));
        list.add(streamMetadataStore.getActiveEpoch(scope, stream, context, true, executor));
        return Futures.allOfWithResults(list);
    }

    @Override
    public CompletableFuture<Void> writeBack(CommitEvent event) {
        return streamTransactionMetadataTasks.writeCommitEvent(event);
    }
    
    private CompletableFuture<Void> resetStateConditionally(String scope, String stream, VersionedMetadata<State> state,
                                                            OperationContext context) {
        if (state.getObject().equals(State.COMMITTING_TXN)) {
            return Futures.toVoid(streamMetadataStore.updateVersionedState(scope, stream, State.ACTIVE, state, context, 
                    executor));
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public CompletableFuture<Boolean> hasTaskStarted(CommitEvent event) {
        return streamMetadataStore.getState(event.getScope(), event.getStream(), true, null, executor)
                                  .thenApply(state -> state.equals(State.COMMITTING_TXN) || state.equals(State.SEALING));
    }

}
