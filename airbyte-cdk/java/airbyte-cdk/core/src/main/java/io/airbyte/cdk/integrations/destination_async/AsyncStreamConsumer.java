/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.cdk.integrations.destination_async;

import static io.airbyte.cdk.integrations.destination_async.deser.DeserializationUtil.*;
import static java.util.stream.Collectors.toMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import io.airbyte.cdk.integrations.base.SerializedAirbyteMessageConsumer;
import io.airbyte.cdk.integrations.destination.StreamSyncSummary;
import io.airbyte.cdk.integrations.destination.buffered_stream_consumer.OnStartFunction;
import io.airbyte.cdk.integrations.destination_async.buffers.BufferEnqueue;
import io.airbyte.cdk.integrations.destination_async.buffers.BufferManager;
import io.airbyte.cdk.integrations.destination_async.deser.IdentityDataTransformer;
import io.airbyte.cdk.integrations.destination_async.deser.StreamAwareDataTransformer;
import io.airbyte.cdk.integrations.destination_async.partial_messages.PartialAirbyteMessage;
import io.airbyte.cdk.integrations.destination_async.state.FlushFailure;
import io.airbyte.commons.json.Jsons;
import io.airbyte.protocol.models.v0.AirbyteMessage;
import io.airbyte.protocol.models.v0.AirbyteMessage.Type;
import io.airbyte.protocol.models.v0.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.v0.StreamDescriptor;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Async version of the
 * {@link io.airbyte.cdk.integrations.destination.buffered_stream_consumer.BufferedStreamConsumer}.
 * <p>
 * With this consumer, a destination is able to continue reading records until hitting the maximum
 * memory limit governed by {@link GlobalMemoryManager}. Record writing is decoupled via
 * {@link FlushWorkers}. See the other linked class for more detail.
 */
@Slf4j
public class AsyncStreamConsumer implements SerializedAirbyteMessageConsumer {

  private static final Logger LOGGER = LoggerFactory.getLogger(AsyncStreamConsumer.class);

  private final OnStartFunction onStart;
  private final OnCloseFunction onClose;
  private final ConfiguredAirbyteCatalog catalog;
  private final BufferManager bufferManager;
  private final BufferEnqueue bufferEnqueue;
  private final FlushWorkers flushWorkers;
  private final Set<StreamDescriptor> streamNames;
  private final FlushFailure flushFailure;
  private final String defaultNamespace;

  private final StreamAwareDataTransformer dataTransformer;
  // Note that this map will only be populated for streams with nonzero records.
  private final ConcurrentMap<StreamDescriptor, AtomicLong> recordCounts;

  private boolean hasStarted;
  private boolean hasClosed;
  private boolean hasFailed = false;
  // This is to account for the references when deserialization to a PartialAirbyteMessage. The
  // calculation is as follows:
  // PartialAirbyteMessage (4) + Max( PartialRecordMessage(4), PartialStateMessage(6)) with
  // PartialStateMessage being larger with more nested objects within it. Using 8 bytes as we assumed
  // a 64 bit JVM.
  final int PARTIAL_DESERIALIZE_REF_BYTES = 10 * 8;

  // TODO: What the.. combinatorics of the constructors are getting out of hand. We should consider
  // refactoring this to use a builder pattern with enforced defaults.

  public AsyncStreamConsumer(final Consumer<AirbyteMessage> outputRecordCollector,
                             final OnStartFunction onStart,
                             final OnCloseFunction onClose,
                             final DestinationFlushFunction flusher,
                             final ConfiguredAirbyteCatalog catalog,
                             final BufferManager bufferManager,
                             final String defaultNamespace) {
    this(outputRecordCollector, onStart, onClose, flusher, catalog, bufferManager, new FlushFailure(), defaultNamespace);
  }

  public AsyncStreamConsumer(final Consumer<AirbyteMessage> outputRecordCollector,
                             final OnStartFunction onStart,
                             final OnCloseFunction onClose,
                             final DestinationFlushFunction flusher,
                             final ConfiguredAirbyteCatalog catalog,
                             final BufferManager bufferManager,
                             final String defaultNamespace,
                             final StreamAwareDataTransformer dataTransformer) {
    this(outputRecordCollector, onStart, onClose, flusher, catalog, bufferManager, new FlushFailure(), defaultNamespace,
        Executors.newFixedThreadPool(5), dataTransformer);
  }

  public AsyncStreamConsumer(final Consumer<AirbyteMessage> outputRecordCollector,
                             final OnStartFunction onStart,
                             final OnCloseFunction onClose,
                             final DestinationFlushFunction flusher,
                             final ConfiguredAirbyteCatalog catalog,
                             final BufferManager bufferManager,
                             final String defaultNamespace,
                             final ExecutorService workerPool) {
    this(outputRecordCollector, onStart, onClose, flusher, catalog, bufferManager, new FlushFailure(), defaultNamespace, workerPool,
        new IdentityDataTransformer());
  }

  @VisibleForTesting
  public AsyncStreamConsumer(final Consumer<AirbyteMessage> outputRecordCollector,
                             final OnStartFunction onStart,
                             final OnCloseFunction onClose,
                             final DestinationFlushFunction flusher,
                             final ConfiguredAirbyteCatalog catalog,
                             final BufferManager bufferManager,
                             final FlushFailure flushFailure,
                             final String defaultNamespace) {
    this(outputRecordCollector, onStart, onClose, flusher, catalog, bufferManager, flushFailure, defaultNamespace, Executors.newFixedThreadPool(5),
        new IdentityDataTransformer());
  }

  @VisibleForTesting
  public AsyncStreamConsumer(final Consumer<AirbyteMessage> outputRecordCollector,
                             final OnStartFunction onStart,
                             final OnCloseFunction onClose,
                             final DestinationFlushFunction flusher,
                             final ConfiguredAirbyteCatalog catalog,
                             final BufferManager bufferManager,
                             final FlushFailure flushFailure,
                             final String defaultNamespace,
                             final ExecutorService workerPool,
                             final StreamAwareDataTransformer dataTransformer) {
    this.defaultNamespace = defaultNamespace;
    hasStarted = false;
    hasClosed = false;

    this.onStart = onStart;
    this.onClose = onClose;
    this.catalog = catalog;
    this.bufferManager = bufferManager;
    bufferEnqueue = bufferManager.getBufferEnqueue();
    this.flushFailure = flushFailure;
    flushWorkers =
        new FlushWorkers(bufferManager.getBufferDequeue(), flusher, outputRecordCollector, flushFailure, bufferManager.getStateManager(), workerPool);
    streamNames = StreamDescriptorUtils.fromConfiguredCatalog(catalog);
    this.recordCounts = new ConcurrentHashMap<>();
    this.dataTransformer = dataTransformer;
  }

  @Override
  public void start() throws Exception {
    Preconditions.checkState(!hasStarted, "Consumer has already been started.");
    hasStarted = true;

    flushWorkers.start();

    LOGGER.info("{} started.", AsyncStreamConsumer.class);
    onStart.call();
  }

  @Override
  public void accept(final String messageString, final Integer sizeInBytes) throws Exception {
    Preconditions.checkState(hasStarted, "Cannot accept records until consumer has started");
    propagateFlushWorkerExceptionIfPresent();
    /*
     * intentionally putting extractStream outside the buffer manager so that if in the future we want
     * to try to use a thread pool to partially deserialize to get record type and stream name, we can
     * do it without touching buffer manager.
     */
    final var message = deserializeAirbyteMessage(messageString, this.dataTransformer);
    if (Type.RECORD.equals(message.getType())) {
      if (Strings.isNullOrEmpty(message.getRecord().getNamespace())) {
        message.getRecord().setNamespace(defaultNamespace);
      }
      validateRecord(message);

      getRecordCounter(message.getRecord().getStreamDescriptor()).incrementAndGet();
    }
    bufferEnqueue.addRecord(message, sizeInBytes + PARTIAL_DESERIALIZE_REF_BYTES, defaultNamespace);
  }

  @Override
  public void close() throws Exception {
    Preconditions.checkState(hasStarted, "Cannot close; has not started.");
    Preconditions.checkState(!hasClosed, "Has already closed.");
    hasClosed = true;

    // assume closing upload workers will flush all accepted records.
    // we need to close the workers before closing the bufferManagers (and underlying buffers)
    // or we risk in-memory data.
    flushWorkers.close();

    bufferManager.close();

    final Map<StreamDescriptor, StreamSyncSummary> streamSyncSummaries = streamNames.stream().collect(toMap(
        streamDescriptor -> streamDescriptor,
        streamDescriptor -> new StreamSyncSummary(
            Optional.of(getRecordCounter(streamDescriptor).get()))));
    onClose.accept(hasFailed, streamSyncSummaries);

    // as this throws an exception, we need to be after all other close functions.
    propagateFlushWorkerExceptionIfPresent();
    LOGGER.info("{} closed", AsyncStreamConsumer.class);
  }

  private AtomicLong getRecordCounter(final StreamDescriptor streamDescriptor) {
    return recordCounts.computeIfAbsent(streamDescriptor, sd -> new AtomicLong());
  }

  private void propagateFlushWorkerExceptionIfPresent() throws Exception {
    if (flushFailure.isFailed()) {
      hasFailed = true;
      if (flushFailure.getException() == null) {
        throw new RuntimeException("The Destination failed with a missing exception. This should not happen. Please check logs.");
      }
      throw flushFailure.getException();
    }
  }

  private void validateRecord(final PartialAirbyteMessage message) {
    final StreamDescriptor streamDescriptor = new StreamDescriptor()
        .withNamespace(message.getRecord().getNamespace())
        .withName(message.getRecord().getStream());
    // if stream is not part of list of streams to sync to then throw invalid stream exception
    if (!streamNames.contains(streamDescriptor)) {
      throwUnrecognizedStream(catalog, message);
    }
  }

  private static void throwUnrecognizedStream(final ConfiguredAirbyteCatalog catalog, final PartialAirbyteMessage message) {
    throw new IllegalArgumentException(
        String.format("Message contained record from a stream that was not in the catalog. \ncatalog: %s , \nmessage: %s",
            Jsons.serialize(catalog), Jsons.serialize(message)));
  }

}
