package io.vertx.kafka.client.consumer.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.kafka.client.common.Helper;
import io.vertx.kafka.client.common.PartitionInfo;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.consumer.KafkaReadStream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class KafkaConsumerImpl<K, V> implements KafkaConsumer<K, V> {

  private final KafkaReadStream<K, V> stream;

  public KafkaConsumerImpl(KafkaReadStream<K, V> stream) {
    this.stream = stream;
  }

  @Override
  public KafkaConsumer<K, V> exceptionHandler(Handler<Throwable> handler) {
    this.stream.exceptionHandler(handler);
    return this;
  }

  @Override
  public KafkaConsumer<K, V> handler(Handler<KafkaConsumerRecord<K, V>> handler) {
    if (handler != null) {
      this.stream.handler(record -> handler.handle(new KafkaConsumerRecordImpl<>(record)));
    } else {
      this.stream.handler(null);
    }
    return this;
  }

  @Override
  public KafkaConsumer<K, V> pause() {
    this.stream.pause();
    return this;
  }

  @Override
  public KafkaConsumer<K, V> resume() {
    this.stream.resume();
    return this;
  }

  @Override
  public KafkaConsumer<K, V> pause(Set<TopicPartition> topicPartitions) {
    return this.pause(topicPartitions, null);
  }

  @Override
  public KafkaConsumer<K, V> pause(Set<TopicPartition> topicPartitions, Handler<AsyncResult<Void>> completionHandler) {
    this.stream.pause(Helper.to(topicPartitions), completionHandler);
    return this;
  }

  @Override
  public KafkaConsumer<K, V> resume(Set<TopicPartition> topicPartitions) {
    return this.resume(topicPartitions, null);
  }

  @Override
  public KafkaConsumer<K, V> resume(Set<TopicPartition> topicPartitions, Handler<AsyncResult<Void>> completionHandler) {
    this.stream.resume(Helper.to(topicPartitions), completionHandler);
    return this;
  }

  @Override
  public KafkaConsumer<K, V> endHandler(Handler<Void> endHandler) {
    this.stream.endHandler(endHandler);
    return this;
  }

  @Override
  public KafkaConsumer<K, V> subscribe(Set<String> topics) {
    this.stream.subscribe(topics);
    return this;
  }

  @Override
  public KafkaConsumer<K, V> subscribe(Set<String> topics, Handler<AsyncResult<Void>> completionHandler) {
    this.stream.subscribe(topics, completionHandler);
    return this;
  }

  @Override
  public KafkaConsumer<K, V> assign(Set<TopicPartition> topicPartitions) {
    this.stream.assign(Helper.to(topicPartitions));
    return this;
  }

  @Override
  public KafkaConsumer<K, V> assign(Set<TopicPartition> topicPartitions, Handler<AsyncResult<Void>> completionHandler) {
    this.stream.assign(Helper.to(topicPartitions), completionHandler);
    return this;
  }

  @Override
  public KafkaConsumer<K, V> assignment(Handler<AsyncResult<Set<TopicPartition>>> handler) {
    this.stream.assignment(done -> {

      if (done.succeeded()) {
        handler.handle(Future.succeededFuture(Helper.from(done.result())));
      } else {
        handler.handle(Future.failedFuture(done.cause()));
      }

    });
    return this;
  }

  @Override
  public KafkaConsumer<K, V> listTopics(Handler<AsyncResult<Map<String,List<PartitionInfo>>>> handler) {
    this.stream.listTopics(done -> {

      if (done.succeeded()) {
        // TODO: use Helper class and stream approach
        Map<String,List<PartitionInfo>> topics = new HashMap<>();

        for (Map.Entry<String,List<org.apache.kafka.common.PartitionInfo>> topicEntry: done.result().entrySet()) {

          List<PartitionInfo> partitions = new ArrayList<>();

          for (org.apache.kafka.common.PartitionInfo kafkaPartitionInfo: topicEntry.getValue()) {

            PartitionInfo partitionInfo = new PartitionInfo();

            partitionInfo
              .setInSyncReplicas(
                Stream.of(kafkaPartitionInfo.inSyncReplicas()).map(Helper::from).collect(Collectors.toList()))
              .setLeader(Helper.from(kafkaPartitionInfo.leader()))
              .setPartition(kafkaPartitionInfo.partition())
              .setReplicas(
                Stream.of(kafkaPartitionInfo.replicas()).map(Helper::from).collect(Collectors.toList()))
              .setTopic(kafkaPartitionInfo.topic());

            partitions.add(partitionInfo);

          }

          topics.put(topicEntry.getKey(), partitions);
        }
        handler.handle(Future.succeededFuture(topics));
      } else {
        handler.handle(Future.failedFuture(done.cause()));
      }

    });
    return this;
  }

  @Override
  public KafkaConsumer<K, V> unsubscribe() {
    this.stream.unsubscribe();
    return this;
  }

  @Override
  public KafkaConsumer<K, V> unsubscribe(Handler<AsyncResult<Void>> completionHandler) {
    this.stream.unsubscribe(completionHandler);
    return this;
  }

  @Override
  public KafkaConsumer<K, V> subscription(Handler<AsyncResult<Set<String>>> handler) {
    this.stream.subscription(handler);
    return this;
  }

  @Override
  public KafkaConsumer<K, V> partitionsRevokedHandler(Handler<Set<TopicPartition>> handler) {
    this.stream.partitionsRevokedHandler(Helper.adaptHandler(handler));
    return this;
  }

  @Override
  public KafkaConsumer<K, V> partitionsAssignedHandler(Handler<Set<TopicPartition>> handler) {
    this.stream.partitionsAssignedHandler(Helper.adaptHandler(handler));
    return this;
  }

  @Override
  public void commit() {
    this.stream.commit();
  }

  @Override
  public void commit(Handler<AsyncResult<Void>> completionHandler) {
    this.stream.commit(completionHandler != null ? ar -> ar.map((Object) null) : null);
  }

  @Override
  public void close(Handler<Void> completionHandler) {
    this.stream.close(completionHandler);
  }

  @Override
  public KafkaReadStream<K, V> asStream() {
    return this.stream;
  }
}
