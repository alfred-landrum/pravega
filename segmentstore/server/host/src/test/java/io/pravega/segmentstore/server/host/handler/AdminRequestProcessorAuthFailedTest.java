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
package io.pravega.segmentstore.server.host.handler;

import io.pravega.auth.InvalidTokenException;
import io.pravega.segmentstore.contracts.StreamSegmentStore;
import io.pravega.segmentstore.contracts.tables.TableStore;
import io.pravega.segmentstore.server.host.stat.SegmentStatsRecorder;
import io.pravega.segmentstore.server.host.stat.TableSegmentStatsRecorder;
import io.pravega.shared.protocol.netty.AdminRequestProcessor;
import io.pravega.shared.protocol.netty.WireCommands;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static io.pravega.shared.protocol.netty.WireCommands.AuthTokenCheckFailed.ErrorCode.TOKEN_CHECK_FAILED;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class AdminRequestProcessorAuthFailedTest {

    private AdminRequestProcessor processor;
    private ServerConnection connection;

    @Before
    public void setUp() throws Exception {
        StreamSegmentStore store = mock(StreamSegmentStore.class);
        connection = mock(ServerConnection.class);
        processor = new AdminRequestProcessorImpl(store, mock(TableStore.class), new TrackedConnection(connection),
                SegmentStatsRecorder.noOp(), TableSegmentStatsRecorder.noOp(),
                (resource, token, expectedLevel) -> {
                    throw new InvalidTokenException("Token verification failed.");
                }, false);
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void flushToStorage() {
        processor.flushToStorage(new WireCommands.FlushToStorage(0, "", 1));
        verify(connection).send(new WireCommands.AuthTokenCheckFailed(1, "", TOKEN_CHECK_FAILED));
    }
}
