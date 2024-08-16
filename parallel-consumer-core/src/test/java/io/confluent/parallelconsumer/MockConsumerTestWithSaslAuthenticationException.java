package io.confluent.parallelconsumer;

/*-
 * Copyright (C) 2020-2024 Confluent, Inc.
 */

import io.confluent.csid.utils.LongPollingMockConsumer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.truth.Truth.assertThat;
import static pl.tlinkowski.unij.api.UniLists.of;

/**
 * Tests that PC works fine with the plain vanilla {@link MockConsumer}, as opposed to the
 * {@link LongPollingMockConsumer}.
 * <p>
 * These tests demonstrate why using {@link MockConsumer} is difficult, and why {@link LongPollingMockConsumer} should
 * be used instead.
 *
 * @author Antony Stubbs
 * @see LongPollingMockConsumer#revokeAssignment
 */
@Slf4j
@Timeout(60000L)
class MockConsumerTestWithSaslAuthenticationException {

    private final String topic = MockConsumerTestWithSaslAuthenticationException.class.getSimpleName();

    /**
     * Test that the mock consumer works as expected
     */
    @Test
    void mockConsumer() {
        final AtomicLong failUntil = new AtomicLong(System.currentTimeMillis() + 20000L);
        var mockConsumer = new MockConsumer<String, String>(OffsetResetStrategy.EARLIEST) {
            @Override
            public synchronized ConsumerRecords<String, String> poll(Duration timeout) {
                if(System.currentTimeMillis() < failUntil.get()) {
                    log.info("Mocking failure before 20 seconds");
                    throw new SaslAuthenticationException("Invalid username or password");
                }
                return super.poll(timeout);
            }

            @Override
            public synchronized void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
                if(System.currentTimeMillis() < failUntil.get()) {
                    throw new SaslAuthenticationException("Invalid username or password");
                }
                super.commitSync(offsets);
            }
        };
        HashMap<TopicPartition, Long> startOffsets = new HashMap<>();
        TopicPartition tp = new TopicPartition(topic, 0);
        startOffsets.put(tp, 0L);

        //
        var options = ParallelConsumerOptions.<String, String>builder()
                .consumer(mockConsumer)
                .saslAuthenticationRetryTimeout(Duration.ofSeconds(25L))
                .build();
        var parallelConsumer = new ParallelEoSStreamProcessor<String, String>(options);
        parallelConsumer.subscribe(of(topic));

        // MockConsumer is not a correct implementation of the Consumer contract - must manually rebalance++ - or use LongPollingMockConsumer
        mockConsumer.rebalance(Collections.singletonList(tp));
        parallelConsumer.onPartitionsAssigned(of(tp));
        mockConsumer.updateBeginningOffsets(startOffsets);

        //
        addRecords(mockConsumer);

        //
        ConcurrentLinkedQueue<RecordContext<String, String>> records = new ConcurrentLinkedQueue<>();
        parallelConsumer.poll(recordContexts -> {
            recordContexts.forEach(recordContext -> {
                log.warn("Processing: {}", recordContext);
                records.add(recordContext);
            });
        });

        // temporarily set the wait timeout
        Awaitility.setDefaultTimeout(Duration.ofSeconds(50));
        //
        Awaitility.await().untilAsserted(() -> {
            assertThat(records).hasSize(3);
        });

        Awaitility.reset();
    }

    private void addRecords(MockConsumer<String, String> mockConsumer) {
        mockConsumer.addRecord(new org.apache.kafka.clients.consumer.ConsumerRecord<>(topic, 0, 0, "key", "value"));
        mockConsumer.addRecord(new org.apache.kafka.clients.consumer.ConsumerRecord<>(topic, 0, 1, "key", "value"));
        mockConsumer.addRecord(new org.apache.kafka.clients.consumer.ConsumerRecord<>(topic, 0, 2, "key", "value"));
    }

}
