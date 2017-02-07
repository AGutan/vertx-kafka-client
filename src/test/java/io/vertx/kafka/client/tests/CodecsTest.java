/*
 * Copyright 2016 Red Hat Inc.
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

package io.vertx.kafka.client.tests;

import io.debezium.kafka.KafkaCluster;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.kafka.client.KafkaCodecs;
import io.vertx.kafka.client.consumer.KafkaReadStream;
import io.vertx.kafka.client.producer.KafkaWriteStream;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;

/**
 * Codec tests
 */
public class CodecsTest extends KafkaClusterTestBase {

  final private String topic = "the_topic";
  private Vertx vertx;
  private KafkaWriteStream<?, ?> producer;
  private KafkaReadStream<?, ?> consumer;

  @Before
  public void beforeTest() {
    vertx = Vertx.vertx();
  }

  @After
  public void afterTest(TestContext ctx) {
    close(ctx, producer);
    close(ctx, consumer);
    vertx.close(ctx.asyncAssertSuccess());
    super.afterTest(ctx);
  }


  @Test
  public void testBufferSerializer() {
    final Deserializer<Buffer> deserializer = KafkaCodecs.deserializer(Buffer.class);
    final Serializer<Buffer> serializer = KafkaCodecs.serializer(Buffer.class);

    final Buffer stringBuffer = Buffer.buffer("Hello");

    assertEquals("Should get the original Buffer after serialization and deserialization",
      stringBuffer, deserializer.deserialize(topic, serializer.serialize(topic, stringBuffer)));

    assertEquals("Should support null in serialization and deserialization",
      null, deserializer.deserialize(topic, serializer.serialize(topic, null)));
  }


  @Test
  public void testStringCodec(TestContext ctx) throws Exception {
    testCodec(ctx, String.class, String.class, i -> "key-" + i, i -> "value-" + i);
  }

  @Test
  public void testBufferCodec(TestContext ctx) throws Exception {
    testCodec(ctx, Buffer.class, Buffer.class, i -> Buffer.buffer("key-" + i), i -> Buffer.buffer("value-" + i));
  }

  private <K, V> void testCodec(TestContext ctx, Class<K> keyType, Class<V> valueType, Function<Integer, K> keyConv, Function<Integer, V> valueConv) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    Properties producerConfig = kafkaCluster.useTo().getProducerProperties("the_producer");
    KafkaWriteStream<K, V> writeStream = producer(fut -> KafkaWriteStream.create(Vertx.vertx(), producerConfig, keyType, valueType, fut.completer()));
    producer = writeStream;
    writeStream.exceptionHandler(ctx::fail);
    int numMessages = 100000;
    ConcurrentLinkedDeque<K> keys = new ConcurrentLinkedDeque<K>();
    ConcurrentLinkedDeque<V> values = new ConcurrentLinkedDeque<V>();
    for (int i = 0;i < numMessages;i++) {
      K key = keyConv.apply(i);
      V value = valueConv.apply(i);
      keys.add(key);
      values.add(value);
      writeStream.write(new ProducerRecord<>(topic, 0, key, value));
    }
    Async done = ctx.async();
    Properties consumerConfig = kafkaCluster.useTo().getConsumerProperties("the_consumer", "the_consumer", OffsetResetStrategy.EARLIEST);;
    KafkaReadStream<K, V> readStream = KafkaReadStream.create(vertx, consumerConfig, keyType, valueType);
    consumer = readStream;
    AtomicInteger count = new AtomicInteger(numMessages);
    readStream.exceptionHandler(ctx::fail);
    readStream.handler(rec -> {
      ctx.assertEquals(keys.pop(), rec.key());
      ctx.assertEquals(values.pop(), rec.value());
      if (count.decrementAndGet() == 0) {
        done.complete();
      }
    });
    readStream.subscribe(Collections.singleton(topic));
  }
}
