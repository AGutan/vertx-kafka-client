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
import io.vertx.core.net.NetServer;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.kafka.client.producer.KafkaWriteStream;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Producer tests
 */
public class ProducerTest extends KafkaClusterTestBase {

  private Vertx vertx;
  private KafkaWriteStream<String, String> producer;

  @Before
  public void beforeTest() {
    vertx = Vertx.vertx();
  }

  @After
  public void afterTest(TestContext ctx) {
    close(ctx, producer);
    vertx.close(ctx.asyncAssertSuccess());
    super.afterTest(ctx);
  }

  @Test
  public void testProduce(TestContext ctx) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    Properties config = kafkaCluster.useTo().getProducerProperties("the_producer");
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producer = producer(Vertx.vertx(), config);
    producer.exceptionHandler(ctx::fail);
    int numMessages = 100000;
    for (int i = 0;i < numMessages;i++) {
      producer.write(new ProducerRecord<>("the_topic", 0, "key-" + i, "value-" + i));
    }
    Async done = ctx.async();
    AtomicInteger seq = new AtomicInteger();
    kafkaCluster.useTo().consumeStrings("the_topic", numMessages, 10, TimeUnit.SECONDS, done::complete, (key, value) -> {
      int count = seq.getAndIncrement();
      ctx.assertEquals("key-" + count, key);
      ctx.assertEquals("value-" + count, value);
      return true;
    });
  }

  @Test
  public void testBlockingBroker(TestContext ctx) throws Exception {
    Async serverAsync = ctx.async();
    NetServer server = vertx.createNetServer().connectHandler(so -> {
    }).listen(9092, ctx.asyncAssertSuccess(v -> serverAsync.complete()));
    serverAsync.awaitSuccess(10000);
    Properties props = new Properties();
    props.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    props.setProperty(ProducerConfig.ACKS_CONFIG, Integer.toString(1));
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);

    producer = producer(Vertx.vertx(), props);
    producer.write(new ProducerRecord<>("the_topic", 0, "key", "value"), ctx.asyncAssertFailure());
  }

  @Test
  public void testBrokerConnectionError(TestContext ctx) throws Exception {
    Properties props = new Properties();
    props.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    props.setProperty(ProducerConfig.ACKS_CONFIG, Integer.toString(1));
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);

    producer = producer(Vertx.vertx(), props);
    producer.write(new ProducerRecord<>("the_topic", 0, "key", "value"), ctx.asyncAssertFailure());
  }
}
