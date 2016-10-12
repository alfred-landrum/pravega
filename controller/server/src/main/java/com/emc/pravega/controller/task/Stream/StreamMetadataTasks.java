/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.emc.pravega.controller.task.Stream;

import com.emc.pravega.controller.server.rpc.v1.SegmentHelper;
import com.emc.pravega.controller.store.host.HostControllerStore;
import com.emc.pravega.controller.store.stream.ScalingConflictException;
import com.emc.pravega.controller.store.stream.Segment;
import com.emc.pravega.controller.store.stream.StreamAlreadyExistsException;
import com.emc.pravega.controller.store.stream.StreamMetadataStore;
import com.emc.pravega.controller.store.stream.StreamNotFoundException;
import com.emc.pravega.controller.stream.api.v1.NodeUri;
import com.emc.pravega.controller.stream.api.v1.Status;
import com.emc.pravega.controller.task.Task;
import com.emc.pravega.controller.task.TaskBase;
import com.emc.pravega.stream.StreamConfiguration;
import com.emc.pravega.stream.impl.model.ModelHelper;
import com.emc.pravega.stream.impl.netty.ConnectionFactoryImpl;
import org.apache.commons.lang.NotImplementedException;
import org.apache.curator.framework.CuratorFramework;

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Collection of metadata update batch operations on stream.
 */
public class StreamMetadataTasks extends TaskBase {

    public StreamMetadataTasks(StreamMetadataStore streamMetadataStore, HostControllerStore hostControllerStore, ConnectionFactoryImpl connectionFactory, CuratorFramework client) {
        super(streamMetadataStore, hostControllerStore, connectionFactory, client);
    }

    @Task(name = "createStream")
    public CompletableFuture<Status> createStream(String scope, String stream, StreamConfiguration config, long createTimestamp) {
        Serializable[] params = {scope, stream, config};
        return this.wrapper(scope, stream, Arrays.asList(params), () -> createStreamBody(scope, stream, config));
    }

    @Task(name = "updateConfig")
    public CompletableFuture<Status> alterStream(String scope, String stream, StreamConfiguration config) {
        Serializable[] params = {scope, stream, config};
        return this.wrapper(scope, stream, Arrays.asList(params), () -> updateStreamConfigBody(scope, stream, config));
    }

    /**
     * Scales stream segments.
     *
     * Annotation takes care of locking and unlocking the stream and persisting operation details, in case the operation
     * needs to be retried on node/process failure
     *
     * Effectively, annotation does the following, before executing the method body
     *
     * 1. Lock the path /stream/{streamName}
     * 2. Add the data for this operation at /stream/{streamName}/operation
     *
     * And, it does the following after executing the method body, in a finally block
     * 1. Delete the data for this operation at /stream/{streamName}/operation
     * 2. Unlock the path /stream/{streamName}
     *
     * @param scope scope
     * @param stream stream name
     * @param sealedSegments segments to be sealed
     * @param newRanges key ranges for new segments
     * @param scaleTimestamp scaling time stamp
     * @return returns the newly created segments
     */
    @Task(name = "scaleStream")
    public CompletableFuture<List<Segment>> scale(String scope, String stream, ArrayList<Integer> sealedSegments, ArrayList<AbstractMap.SimpleEntry<Double, Double>> newRanges, long scaleTimestamp) {
        Serializable[] params = {scope, stream, sealedSegments, newRanges, scaleTimestamp};
        return this.wrapper(scope, stream, Arrays.asList(params), () -> scaleBody(scope, stream, sealedSegments, newRanges, scaleTimestamp));
    }

    @Task(name = "createTransaction")
    public CompletableFuture<String> createTx(String scope, String stream) {
        throw new NotImplementedException();
    }

    @Task(name = "dropTransaction")
    public CompletableFuture<Boolean> dropTx(String scope, String stream, String txId) {
        throw new NotImplementedException();
    }

    @Task(name = "commitTransaction")
    public CompletableFuture<Boolean> commitTx(String scope, String stream, String txId) {
        throw new NotImplementedException();
    }

    private CompletableFuture<Status> createStreamBody(String scope, String stream, StreamConfiguration config) {
        return this.streamMetadataStore.createStream(stream, config)
                .handle((result, ex) -> {
                    if (ex != null) {
                        if (ex instanceof StreamAlreadyExistsException) {
                            return Status.DUPLICATE_STREAM_NAME;
                        } else {
                            throw new RuntimeException(ex);
                        }
                    } else {
                        // result is non-null
                        if (result) {
                            // successful stream creation implies the stream was completely created from scratch
                            // or its creation was completed from a previous incomplete state resulting from host failure
                            this.streamMetadataStore.getActiveSegments(stream)
                                    .thenApply(activeSegments ->
                                            notifyNewSegments(config.getScope(), stream, activeSegments));
                            return Status.SUCCESS;
                        } else {
                            // failure indicates that the stream creation failed due to some internal error, or
                            return Status.FAILURE;
                        }
                    }
                });
    }

    public CompletableFuture<Status> updateStreamConfigBody(String scope, String stream, StreamConfiguration config) {
        return streamMetadataStore.updateConfiguration(stream, config)
                .handle((result, ex) -> {
                    if (ex != null) {
                        if (ex instanceof StreamNotFoundException) {
                            return Status.STREAM_NOT_FOUND;
                        } else {
                            throw new RuntimeException(ex);
                        }
                    } else {
                        return result ? Status.SUCCESS : Status.FAILURE;
                    }
                });
    }

    private CompletableFuture<List<Segment>> scaleBody(String scope, String stream, List<Integer> sealedSegments, List<AbstractMap.SimpleEntry<Double, Double>> newRanges, long scaleTimestamp) {
        // Abort scaling operation in the following error scenarios
        // 1. if the active segments in the stream have ts greater than scaleTimestamp, or
        // 2. if active segments having creation timestamp as scaleTimestamp have different key ranges than the ones specified in newRanges

        CompletableFuture<Boolean> checkValidity =
                streamMetadataStore.getActiveSegments(stream)
                        .thenApply(activeSegments ->
                                activeSegments
                                        .stream()
                                        .anyMatch(segment -> segment.getStart() > scaleTimestamp));

        return checkValidity.thenCompose(result -> {

                    if (true) {
                        return notifySealedSegments(scope, stream, sealedSegments)
                                .thenCompose(results ->
                                        streamMetadataStore.scale(stream, sealedSegments, newRanges, scaleTimestamp))
                                .thenApply(newSegments -> {
                                    notifyNewSegments(scope, stream, newSegments);
                                    return newSegments;
                                });
                    } else {
                        throw new ScalingConflictException(stream, scaleTimestamp);
                    }
                }
        );
    }

    private Void notifyNewSegments(String scope, String stream, List<Segment> segmentNumbers) {
        segmentNumbers
                .stream()
                .parallel()
                .forEach(segment -> asyncNotifyNewSegment(scope, stream, segment.getNumber()));
        return null;
    }

    private Void asyncNotifyNewSegment(String scope, String stream, int segmentNumber) {
        NodeUri uri = SegmentHelper.getSegmentUri(scope, stream, segmentNumber, this.hostControllerStore);

        // async call, dont wait for its completion or success. Host will contact controller if it does not know
        // about some segment even if this call fails
        CompletableFuture.runAsync(() -> SegmentHelper.createSegment(scope, stream, segmentNumber, ModelHelper.encode(uri), this.connectionFactory));
        return null;
    }

    private CompletableFuture<Void> notifySealedSegments(String scope, String stream, List<Integer> sealedSegments) {
        sealedSegments
                .stream()
                .parallel()
                .forEach(number -> sealSegment(scope, stream, number));
        return CompletableFuture.completedFuture(null);
    }

    /**
     * This method sends segment sealed message for the specified segment.
     * It owns up the responsibility of retrying the operation on failures until success.
     * @param scope stream scope
     * @param stream stream name
     * @param segmentNumber number of segment to be sealed
     * @return void
     */
    public Void sealSegment(String scope, String stream, int segmentNumber) {
        boolean result = false;
        while (!result) {
            try {
                NodeUri uri = SegmentHelper.getSegmentUri(scope, stream, segmentNumber, this.hostControllerStore);
                result = SegmentHelper.sealSegment(scope, stream, segmentNumber, ModelHelper.encode(uri), this.connectionFactory);
            } catch (RuntimeException ex) {
                //log exception and continue retrying
            }
        }
        return null;
    }
}
