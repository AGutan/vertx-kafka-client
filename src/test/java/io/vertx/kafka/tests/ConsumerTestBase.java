package io.vertx.kafka.tests;

import io.debezium.kafka.KafkaCluster;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.kafka.consumer.KafkaReadStream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public abstract class ConsumerTestBase extends KafkaClusterTestBase {

  private Vertx vertx;
  private KafkaReadStream<String, String> consumer;

  @Before
  public void beforeTest() {
    vertx = Vertx.vertx();
  }

  @After
  public void afterTest(TestContext ctx) {
    if (consumer != null) {
      Async close = ctx.async();
      consumer.close(v -> {
        close.complete();
      });
      consumer = null;
      close.awaitSuccess(10000);
    }
    vertx.close(ctx.asyncAssertSuccess());
    super.afterTest(ctx);
  }


  @Test
  public void testConsume(TestContext ctx) throws Exception {
    KafkaCluster kafkaCluster = kafkaCluster().addBrokers(1).startup();
    Async batch = ctx.async();
    AtomicInteger index = new AtomicInteger();
    int numMessages = 20000;
    kafkaCluster.useTo().produceStrings(numMessages, batch::complete,  () ->
        new ProducerRecord<>("the_topic", 0, "key-" + index.get(), "value-" + index.getAndIncrement()));
    batch.awaitSuccess(10000);
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
    int numMessages = 10000;
    kafkaCluster.useTo().produceStrings(numMessages, batch::complete,  () ->
        new ProducerRecord<>("the_topic", 0, "key-" + index.get(), "value-" + index.getAndIncrement()));
    batch.awaitSuccess(10000);
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

  abstract <K, V> KafkaReadStream<K, V> createConsumer(Vertx vertx, Properties config);
}
