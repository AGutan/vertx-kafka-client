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
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.kafka.client.consumer.KafkaReadStream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for consumer tests
 */
public abstract class ConsumerTestBase extends KafkaClusterTestBase {

  private Vertx vertx;
  private KafkaReadStream<String, String> consumer;
  private KafkaReadStream<String, String> consumer2;

  @Before
  public void beforeTest() {
    vertx = Vertx.vertx();
  }

  @After
  public void afterTest(TestContext ctx) {
    close(ctx, consumer);
    close(ctx, consumer2);
    consumer = null;
    consumer2 = null;
    vertx.close(ctx.asyncAssertSuccess());
    super.afterTest(ctx);
  }

  @Test
  public void testConsume(TestContext ctx) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    Async batch = ctx.async();
    AtomicInteger index = new AtomicInteger();
    int numMessages = 1000;
    kafkaCluster.useTo().produceStrings(numMessages, batch::complete,  () ->
        new ProducerRecord<>("the_topic", 0, "key-" + index.get(), "value-" + index.getAndIncrement()));
    batch.awaitSuccess(20000);
    Properties config = kafkaCluster.useTo().getConsumerProperties("the_consumer", "the_consumer", OffsetResetStrategy.EARLIEST);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumer = createConsumer(vertx, config);
    Async done = ctx.async();
    AtomicInteger count = new AtomicInteger(numMessages);
    consumer.exceptionHandler(ctx::fail);
    consumer.handler(rec -> {
      if (count.decrementAndGet() == 0) {
        done.complete();
      }
    });
    consumer.subscribe(Collections.singleton("the_topic"));
  }

  @Test
  public void testPause(TestContext ctx) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    Async batch = ctx.async();
    AtomicInteger index = new AtomicInteger();
    int numMessages = 1000;
    kafkaCluster.useTo().produceStrings(numMessages, batch::complete,  () ->
        new ProducerRecord<>("the_topic", 0, "key-" + index.get(), "value-" + index.getAndIncrement()));
    batch.awaitSuccess(20000);
    Properties config = kafkaCluster.useTo().getConsumerProperties("the_consumer", "the_consumer", OffsetResetStrategy.EARLIEST);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumer = createConsumer(vertx, config);
    Async done = ctx.async();
    AtomicInteger count = new AtomicInteger(numMessages);
    consumer.exceptionHandler(ctx::fail);
    AtomicBoolean paused = new AtomicBoolean();
    consumer.handler(rec -> {
      ctx.assertFalse(paused.get());
      int val = count.decrementAndGet();
      if (val == numMessages / 2) {
        paused.set(true);
        consumer.pause();
        vertx.setTimer(500, id -> {
          paused.set(false);
          consumer.resume();
        });
      }
      if (val == 0) {
        done.complete();
      }
    });
    consumer.subscribe(Collections.singleton("the_topic"));
  }

  @Test
  public void testCommit(TestContext ctx) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    Async batch1 = ctx.async();
    AtomicInteger index = new AtomicInteger();
    int numMessages = 500;
    kafkaCluster.useTo().produceStrings(numMessages, batch1::complete,  () ->
        new ProducerRecord<>("the_topic", 0, "key-" + index.get(), "value-" + index.getAndIncrement()));
    batch1.awaitSuccess(10000);
    Properties config = kafkaCluster.useTo().getConsumerProperties("the_consumer", "the_consumer", OffsetResetStrategy.EARLIEST);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumer = createConsumer(vertx, config);
    consumer.exceptionHandler(ctx::fail);
    Async commited = ctx.async();
    AtomicInteger count = new AtomicInteger();
    consumer.handler(rec -> {
      int idx = count.getAndIncrement();
      ctx.assertEquals("key-" + idx, rec.key());
      ctx.assertEquals("value-" + idx, rec.value());
      if (idx == numMessages - 1) {
        consumer.commit(ctx.asyncAssertSuccess(v1 -> {
          consumer.close(v2 -> {
            commited.complete();
          });
        }));
      }
    });
    consumer.subscribe(Collections.singleton("the_topic"));
    commited.awaitSuccess(10000);
    Async batch2 = ctx.async();
    kafkaCluster.useTo().produceStrings(numMessages, batch2::complete,  () ->
        new ProducerRecord<>("the_topic", 0, "key-" + index.get(), "value-" + index.getAndIncrement()));
    batch2.awaitSuccess(10000);
    consumer = createConsumer(vertx, config);
    consumer.exceptionHandler(ctx::fail);
    Async done = ctx.async();
    consumer.handler(rec -> {
      int idx = count.getAndIncrement();
      ctx.assertEquals("key-" + idx, rec.key());
      ctx.assertEquals("value-" + idx, rec.value());
      if (idx == numMessages * 2 - 1) {
        consumer.commit(ctx.asyncAssertSuccess(v1 -> {
          done.complete();
        }));
      }
    });
    consumer.subscribe(Collections.singleton("the_topic"));
  }

  @Test
  public void testCommitWithOffets(TestContext ctx) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    Async batch1 = ctx.async();
    AtomicInteger index = new AtomicInteger();
    int numMessages = 500;
    kafkaCluster.useTo().produceStrings(numMessages, batch1::complete,  () ->
        new ProducerRecord<>("the_topic", 0, "key-" + index.get(), "value-" + index.getAndIncrement()));
    batch1.awaitSuccess(10000);
    Properties config = kafkaCluster.useTo().getConsumerProperties("the_consumer", "the_consumer", OffsetResetStrategy.EARLIEST);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumer = createConsumer(vertx, config);
    consumer.exceptionHandler(ctx::fail);
    Async commited = ctx.async(2);
    AtomicInteger count = new AtomicInteger();
    consumer.handler(rec -> {
      int val = count.incrementAndGet();
      switch (val) {
        case 101:
          TopicPartition the_topic = new TopicPartition("the_topic", 0);
          consumer.commit(Collections.singletonMap(the_topic, new OffsetAndMetadata(rec.offset())),
                  ctx.asyncAssertSuccess(v -> commited.countDown()));
          break;
        case 500:
          commited.countDown();
          break;
      }
    });
    consumer.subscribe(Collections.singleton("the_topic"));
    commited.awaitSuccess(10000);
    Async closed = ctx.async();
    consumer.close(v -> closed.complete());
    closed.awaitSuccess(10000);
    consumer = createConsumer(vertx, config);
    count.set(100);
    Async done = ctx.async();
    consumer.handler(rec -> {
      int idx = count.getAndIncrement();
      ctx.assertEquals("key-" + idx, rec.key());
      ctx.assertEquals("value-" + idx, rec.value());
      if (idx == numMessages - 1) {
        done.complete();
      }
    });
    consumer.subscribe(Collections.singleton("the_topic"));
  }

  @Test
  public void testRebalance(TestContext ctx) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    kafkaCluster.createTopic("the_topic", 2, 1);
    Properties config = kafkaCluster.useTo().getConsumerProperties("the_consumer", "the_consumer", OffsetResetStrategy.EARLIEST);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    Context context = vertx.getOrCreateContext();
    consumer = createConsumer(context, config);

    // We need to rename the client to avoid javax.management.InstanceAlreadyExistsException
    // see https://github.com/vert-x3/vertx-kafka-client/issues/5
    config.setProperty("client.id", "the_consumer2");
    consumer2 = createConsumer(vertx, config);
    consumer.handler(rec -> {});
    consumer2.handler(rec -> {});
    Async rebalanced = ctx.async(2);
    AtomicInteger status = new AtomicInteger();
    consumer.partitionsAssignedHandler(partitions -> {
      ctx.assertEquals(Vertx.currentContext(), context);
      switch (status.getAndIncrement()) {
        case 0:
          ctx.fail();
          break;
        case 1:
          consumer2.subscribe(Collections.singleton("the_topic"));
          ctx.assertEquals(2, partitions.size());
          ctx.assertTrue(partitions.contains(new TopicPartition("the_topic", 0)));
          ctx.assertTrue(partitions.contains(new TopicPartition("the_topic", 1)));
          break;
        case 2:
          ctx.fail();
          break;
        case 3:
          ctx.assertEquals(1, partitions.size());
          rebalanced.countDown();
          break;
      }
    });
    consumer.partitionsRevokedHandler(partitions -> {
      ctx.assertEquals(Vertx.currentContext(), context);
      switch (status.getAndIncrement()) {
        case 0:
          ctx.assertEquals(0, partitions.size());
          break;
        case 1:
          ctx.fail();
          break;
        case 2:
          ctx.assertEquals(2, partitions.size());
          ctx.assertTrue(partitions.contains(new TopicPartition("the_topic", 0)));
          ctx.assertTrue(partitions.contains(new TopicPartition("the_topic", 1)));
          break;
      }
    });
    AtomicInteger status2 = new AtomicInteger();
    consumer2.partitionsAssignedHandler(partitions -> {
      switch (status2.getAndIncrement()) {
        case 0:
          ctx.assertEquals(1, partitions.size());
          rebalanced.countDown();
          break;
      }
    });
    consumer.subscribe(Collections.singleton("the_topic"));
  }

  @Test
  public void testSeek(TestContext ctx) throws Exception {
    int numMessages = 500;
    testSeek("the_topic_0", numMessages, ctx, () -> {
      consumer.seek(new TopicPartition("the_topic_0", 0), 0);
    }, -numMessages);
  }

  @Test
  public void testSeekToBeginning(TestContext ctx) throws Exception {
    int numMessages = 500;
    testSeek("the_topic_1", numMessages, ctx, () -> {
      consumer.seekToBeginning(Collections.singleton(new TopicPartition("the_topic_1", 0)));
    }, -numMessages);
  }

  @Test
  public void testSeekToEnd(TestContext ctx) throws Exception {
    int numMessages = 500;
    testSeek("the_topic_2", numMessages, ctx, () -> {
      consumer.seekToEnd(Collections.singleton(new TopicPartition("the_topic_2", 0)));
    }, 0);
  }

  private void testSeek(String topic, int numMessages, TestContext ctx, Runnable seeker, int abc) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    kafkaCluster.createTopic(topic, 1, 1);
    Properties config = kafkaCluster.useTo().getConsumerProperties("the_consumer", "the_consumer", OffsetResetStrategy.EARLIEST);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    Context context = vertx.getOrCreateContext();
    consumer = createConsumer(context, config);
    Async batch1 = ctx.async();
    AtomicInteger index = new AtomicInteger();
    kafkaCluster.useTo().produceStrings(numMessages, batch1::complete,  () ->
        new ProducerRecord<>(topic, 0, "key-" + index.get(), "value-" + index.getAndIncrement()));
    batch1.awaitSuccess(10000);
    AtomicInteger count = new AtomicInteger(numMessages);
    Async done = ctx.async();
    consumer.handler(record -> {
      int dec = count.decrementAndGet();
      if (dec >= 0) {
        ctx.assertEquals("key-" + (numMessages - dec - 1), record.key());
      } else {
        ctx.assertEquals("key-" + (-1 - dec), record.key());
      }
      if (dec == 0) {
        seeker.run();
      }
      if (dec == abc) {
        done.complete();
      }
    });
    consumer.subscribe(Collections.singleton(topic));
  }

  @Test
  public void testSubscription(TestContext ctx) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    kafkaCluster.createTopic("the_topic", 1, 1);
    Properties config = kafkaCluster.useTo().getConsumerProperties("the_consumer", "the_consumer", OffsetResetStrategy.EARLIEST);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    Context context = vertx.getOrCreateContext();
    consumer = createConsumer(context, config);

    Async done = ctx.async();

    consumer.handler(record -> {
      // no need for handling incoming records in this test
    });

    consumer.subscribe(Collections.singleton("the_topic"), asyncResult -> {

      if (asyncResult.succeeded()) {

        consumer.subscription(asyncResult1 -> {

          if (asyncResult1.succeeded()) {

            ctx.assertTrue(asyncResult1.result().contains("the_topic"));
            done.complete();

          } else {
            ctx.fail();
          }
        });

      } else {
        ctx.fail();
      }

    });
  }

  @Test
  public void testAssign(TestContext ctx) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    kafkaCluster.createTopic("the_topic", 1, 1);
    Properties config = kafkaCluster.useTo().getConsumerProperties("the_consumer", "the_consumer", OffsetResetStrategy.EARLIEST);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    Context context = vertx.getOrCreateContext();
    consumer = createConsumer(context, config);

    Async done = ctx.async();

    consumer.handler(record -> {
      // no need for handling incoming records in this test
    });

    TopicPartition partition = new TopicPartition("the_topic", 0);

    consumer.assign(Collections.singleton(partition), asyncResult -> {

      if (asyncResult.succeeded()) {

        consumer.assignment(asyncResult1 -> {

          if (asyncResult1.succeeded()) {

            ctx.assertTrue(asyncResult1.result().contains(partition));
            done.complete();

          } else {
            ctx.fail();
          }
        });

      } else {
        ctx.fail();
      }

    });
  }

  @Test
  public void testListTopics(TestContext ctx) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    kafkaCluster.createTopic("the_topic", 1, 1);
    Properties config = kafkaCluster.useTo().getConsumerProperties("the_consumer", "the_consumer", OffsetResetStrategy.EARLIEST);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    Context context = vertx.getOrCreateContext();
    consumer = createConsumer(context, config);

    Async done = ctx.async();

    consumer.handler(record -> {
      // no need for handling incoming records in this test
    });

    consumer.subscribe(Collections.singleton("the_topic"), asyncResult -> {

      if (asyncResult.succeeded()) {

        consumer.listTopics(asyncResult1 -> {

          if (asyncResult1.succeeded()) {

            ctx.assertTrue(asyncResult1.result().containsKey("the_topic"));
            done.complete();

          } else {
            ctx.fail();
          }
        });

      } else {
        ctx.fail();
      }

    });
  }

  @Test
  public void testPositionEmptyTopic(TestContext ctx) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    kafkaCluster.createTopic("the_topic", 1, 1);
    Properties config = kafkaCluster.useTo().getConsumerProperties("the_consumer", "the_consumer", OffsetResetStrategy.EARLIEST);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    Context context = vertx.getOrCreateContext();
    consumer = createConsumer(context, config);

    Async done = ctx.async();

    consumer.handler(record -> {
      // no need for handling incoming records in this test
    });

    consumer.subscribe(Collections.singleton("the_topic"), asyncResult -> {

      if (asyncResult.succeeded()) {
        consumer.partitionsFor("the_topic", asyncResult1 -> {
          if (asyncResult.succeeded()) {
            for (org.apache.kafka.common.PartitionInfo pi : asyncResult1.result()) {
              TopicPartition tp = new TopicPartition("the_topic", pi.partition());
              consumer.position(tp, asyncResult2 -> {
                if (asyncResult2.succeeded()) {
                  ctx.assertTrue(asyncResult2.result() == 0);
                  done.complete();
                } else {
                  ctx.fail();
                }
              });
            }
          } else {
            ctx.fail();
          }
        });

      } else {
        ctx.fail();
      }

    });
  }

  @Test
  public void testPositionNonEmptyTopic(TestContext ctx) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    kafkaCluster.createTopic("the_topic", 1, 1);
    Async batch = ctx.async();
    AtomicInteger index = new AtomicInteger();
    int numMessages = 1000;
    kafkaCluster.useTo().produceStrings(numMessages, batch::complete, ()
            -> new ProducerRecord<>("the_topic", 0, "key-" + index.get(), "value-" + index.getAndIncrement()));
    batch.awaitSuccess(20000);
    Properties config = kafkaCluster.useTo().getConsumerProperties("the_consumer", "the_consumer", OffsetResetStrategy.EARLIEST);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    Context context = vertx.getOrCreateContext();
    consumer = createConsumer(context, config);

    Async done = ctx.async();

    AtomicInteger count = new AtomicInteger(numMessages);
    consumer.exceptionHandler(ctx::fail);
    consumer.handler(rec -> {
      if (count.decrementAndGet() == 0) {
        consumer.partitionsFor("the_topic", asyncResult -> {
          if (asyncResult.succeeded()) {
            for (org.apache.kafka.common.PartitionInfo pi : asyncResult.result()) {
              TopicPartition tp = new TopicPartition("the_topic", pi.partition());
              consumer.position(tp, asyncResult1 -> {
                if (asyncResult1.succeeded()) {
                  ctx.assertTrue(asyncResult1.result() == numMessages);
                  done.complete();
                } else {
                  ctx.fail();
                }
              });
            }
          } else {
            ctx.fail();
          }
        });
      }
    });
    consumer.subscribe(Collections.singleton("the_topic"));
  }



  /*
    Tests beginningOffset
   */
  @Test
  public void testBeginningOffset(TestContext ctx) throws Exception {
   testBeginningEndOffset(ctx, true);
  }

  /*
    Teste endOffset (boolean parameter = false)
   */
  @Test
  public void testEndOffset(TestContext ctx) throws Exception {
    testBeginningEndOffset(ctx, false);
  }

  /*
   Tests test beginningOffset or endOffset, depending on beginningOffset = true or false
  */
  public void testBeginningEndOffset(TestContext ctx, boolean beginningOffset) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    Async batch = ctx.async();
    AtomicInteger index = new AtomicInteger();
    int numMessages = 1000;
    kafkaCluster.useTo().produceStrings(numMessages, batch::complete, () ->
      new ProducerRecord<>("the_topic", 0, "key-" + index.get(), "value-" + index.getAndIncrement()));
    batch.awaitSuccess(20000);

    Properties config = kafkaCluster.useTo().getConsumerProperties("the_consumer", "the_consumer", OffsetResetStrategy.EARLIEST);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    Context context = vertx.getOrCreateContext();
    consumer = createConsumer(context, config);

    consumer.exceptionHandler(ctx::fail);

    Set<TopicPartition> topicPartitions = new HashSet<>();
    TopicPartition topicPartition= new TopicPartition("the_topic", 0);
    topicPartitions.add(topicPartition);

    // Test contains two sub-tests
    Async done = ctx.async(2);
    consumer.handler(handler -> {
      // nothing to do in this test
    });

    consumer.subscribe(Collections.singleton("the_topic"), subscribeRes -> {
        if (subscribeRes.succeeded()) {
          if(beginningOffset) {
            consumer.beginningOffsets(topicPartitions, beginningOffsetResult -> {
              ctx.assertTrue(beginningOffsetResult.succeeded());
              // expect one result
              ctx.assertEquals(1, beginningOffsetResult.result().size());
              // beginning offset must be 0
              ctx.assertEquals(0L, beginningOffsetResult.result().get(topicPartition));
              done.countDown();
            });
            consumer.beginningOffsets(topicPartition, beginningOffsetResult -> {
                ctx.assertTrue(beginningOffsetResult.succeeded());
                // beginning offset must be 0
                ctx.assertEquals(0L, beginningOffsetResult.result());
                done.countDown();
              });
          }
          // Tests for endOffset
          else {
            consumer.endOffsets(topicPartitions, endOffsetResult -> {
              ctx.assertTrue(endOffsetResult.succeeded());
              ctx.assertEquals(1, endOffsetResult.result().size());
              // endOffset must be equal to the number of ingested messages
              ctx.assertEquals((long) numMessages, endOffsetResult.result().get(topicPartition));
              done.countDown();
            });

            consumer.endOffsets(topicPartition, endOffsetResult -> {
              ctx.assertTrue(endOffsetResult.succeeded());
              // endOffset must be equal to the number of ingested messages
              ctx.assertEquals((long) numMessages, endOffsetResult.result());
              done.countDown();
            });
          }
        } else {
          ctx.fail(subscribeRes.cause());
        }
      }
    );
  }


  @Test
  public void testOffsetsForTimes(TestContext ctx) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    Async batch = ctx.async();
    AtomicInteger index = new AtomicInteger();
    int numMessages = 1000;
    long beforeProduce = System.currentTimeMillis();
    kafkaCluster.useTo().produceStrings(numMessages, batch::complete, () ->
      new ProducerRecord<>("the_topic", 0, "key-" + index.get(), "value-" + index.getAndIncrement()));
    batch.awaitSuccess(20000);
    long produceDuration = System.currentTimeMillis() - beforeProduce;

    Properties config = kafkaCluster.useTo().getConsumerProperties("the_consumer", "the_consumer", OffsetResetStrategy.EARLIEST);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    Context context = vertx.getOrCreateContext();
    consumer = createConsumer(context, config);

    consumer.exceptionHandler(ctx::fail);

    TopicPartition topicPartition= new TopicPartition("the_topic", 0);

    // Test contains two sub-tests
    Async done = ctx.async(2);
    consumer.handler(handler -> {
      // nothing to do in this test
    });

    consumer.subscribe(Collections.singleton("the_topic"), subscribeRes -> {
        if (subscribeRes.succeeded()) {
          // search by timestamp
          // take timestamp BEFORE start of ingestion and add half of the ingestion duration to it
          long searchTimestamp = beforeProduce + (produceDuration / 2);
          Map<TopicPartition, Long> topicAndTimestamp = new HashMap<>();
          topicAndTimestamp.put(topicPartition, searchTimestamp);

          consumer.offsetsForTimes(topicAndTimestamp, offsetSearchResult -> {
            ctx.assertTrue(offsetSearchResult.succeeded());
            ctx.assertEquals(1, offsetSearchResult.result().size());
            OffsetAndTimestamp offsetAndTimestamp = offsetSearchResult.result().get(topicPartition);
            // Offset must be somewhere between beginningOffset and endOffset
            ctx.assertTrue(offsetAndTimestamp.offset() > 0L && offsetAndTimestamp.offset() <= (long)numMessages);
            // Timestamp of returned offset must be at >= searchTimestamp
            ctx.assertTrue(offsetAndTimestamp.timestamp() >= searchTimestamp);
            done.countDown();
          });

          consumer.offsetsForTimes(topicPartition, searchTimestamp, offsetSearchResult -> {
            ctx.assertTrue(offsetSearchResult.succeeded());
            OffsetAndTimestamp offsetAndTimestamp = offsetSearchResult.result();
            // Offset must be somewhere between beginningOffset and endOffset
            ctx.assertTrue(offsetAndTimestamp.offset() > 0L && offsetAndTimestamp.offset() <= (long)numMessages);
            // Timestamp of returned offset must be at >= searchTimestamp
            ctx.assertTrue(offsetAndTimestamp.timestamp() >= searchTimestamp);
            done.countDown();
          });

        } else {
          ctx.fail(subscribeRes.cause());
        }
      }
    );
  }

  <K, V> KafkaReadStream<K, V> createConsumer(Context context, Properties config) throws Exception {
    CompletableFuture<KafkaReadStream<K, V>> ret = new CompletableFuture<>();
    context.runOnContext(v -> {
      try {
        ret.complete(createConsumer(context.owner(), config));
      } catch (Exception e) {
        ret.completeExceptionally(e);
      }
    });
    return ret.get(10, TimeUnit.SECONDS);
  }

  abstract <K, V> KafkaReadStream<K, V> createConsumer(Vertx vertx, Properties config);
}
