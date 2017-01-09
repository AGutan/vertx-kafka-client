package io.vertx.kafka.client.consumer.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.kafka.client.consumer.KafkaReadStream;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class KafkaReadStreamImpl<K, V> implements KafkaReadStream<K, V> {

  private final Context context;
  private final AtomicBoolean closed = new AtomicBoolean(true);
  private final Consumer<K, V> consumer;

  private final AtomicBoolean paused = new AtomicBoolean(true);
  private Handler<ConsumerRecord<K, V>> recordHandler;
  private Iterator<ConsumerRecord<K, V>> current; // Accessed on event loop
  private Handler<Collection<TopicPartition>> partitionsRevokedHandler;
  private Handler<Collection<TopicPartition>> partitionsAssignedHandler;

  private ExecutorService worker;

  private final ConsumerRebalanceListener rebalanceListener =  new ConsumerRebalanceListener() {

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {

      Handler<Collection<TopicPartition>> handler = partitionsRevokedHandler;
      if (handler != null) {
        context.runOnContext(v -> {
          handler.handle(partitions);
        });
      }
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {

      Handler<Collection<TopicPartition>> handler = partitionsAssignedHandler;
      if (handler != null) {
        context.runOnContext(v -> {
          handler.handle(partitions);
        });
      }
    }
  };

  public KafkaReadStreamImpl(Context context, Consumer<K, V> consumer) {
    this.context = context;
    this.consumer = consumer;
  }

  private <T> void start(java.util.function.BiConsumer<Consumer<K, V>, Future<T>> task, Handler<AsyncResult<T>> handler) {
    this.worker = Executors.newSingleThreadExecutor();
    this.submitTask(task, handler);
  }

  private <T> void submitTask(java.util.function.BiConsumer<Consumer<K, V>, Future<T>> task, Handler<AsyncResult<T>> handler) {
    this.worker.submit(() -> {
      Future<T> future;
      if (handler != null) {
        future = Future.future();
        future.setHandler(handler);
      } else {
        future = null;
      }
      try {
        task.accept(this.consumer, future);
      } catch (Exception e) {
        if (future != null && !future.isComplete()) {
          future.fail(e);
        }
      }
    });
  }

  private void pollRecords(Handler<ConsumerRecords<K, V>> handler) {
    this.worker.submit(() -> {
      if (!this.closed.get()) {
        try {
          ConsumerRecords<K, V> records = this.consumer.poll(1000);
          if (records != null && records.count() > 0) {
            this.context.runOnContext(v -> handler.handle(records));
          } else {
            this.pollRecords(handler);
          }
        } catch (WakeupException ignore) {
        }
      }
    });
  }

  private void schedule(long delay) {
    if (!this.paused.get()) {

      Handler<ConsumerRecord<K, V>> handler = this.recordHandler;
      if (delay > 0) {
        this.context.owner().setTimer(delay, v -> run(handler));
      } else {
        this.context.runOnContext(v -> run(handler));
      }
    }
  }

  // Access the consumer from the event loop since the consumer is not thread safe
  private void run(Handler<ConsumerRecord<K, V>> handler) {

    if (this.closed.get()) {
      return;
    }

    if (this.current == null || !this.current.hasNext()) {

      this.pollRecords(records -> {

        if (records != null && records.count() > 0) {
          this.current = records.iterator();
          this.schedule(0);
        } else {
          this.schedule(1);
        }
      });

    } else {

      int count = 0;
      while (this.current.hasNext() && count++ < 10) {

        ConsumerRecord<K, V> next = this.current.next();
        if (handler != null) {
          handler.handle(next);
        }
      }
      this.schedule(0);
    }
  }

  @Override
  public KafkaReadStream<K, V> pause(Collection<TopicPartition> topicPartitions) {
    return pause(topicPartitions, null);
  }

  @Override
  public KafkaReadStream<K, V> pause(Collection<TopicPartition> topicPartitions, Handler<AsyncResult<Void>> completionHandler) {

    this.submitTask((consumer, future) -> {
      consumer.pause(topicPartitions);
      if (future != null) {
        future.complete();
      }
    }, completionHandler);

    return this;
  }

  @Override
  public KafkaReadStream<K, V> resume(Collection<TopicPartition> topicPartitions) {
    return this.resume(topicPartitions, null);
  }

  @Override
  public KafkaReadStream<K, V> resume(Collection<TopicPartition> topicPartitions, Handler<AsyncResult<Void>> completionHandler) {

    this.submitTask((consumer, future) -> {
      consumer.resume(topicPartitions);
      if (future != null) {
        future.complete();
      }
    }, completionHandler);

    return this;
  }

  @Override
  public void committed(TopicPartition topicPartition, Handler<AsyncResult<OffsetAndMetadata>> handler) {

    this.submitTask((consumer, future) -> {
      OffsetAndMetadata result = consumer.committed(topicPartition);
      if (future != null) {
        future.complete(result);
      }
    }, handler);
  }

  @Override
  public KafkaReadStream<K, V> seekToEnd(Collection<TopicPartition> topicPartitions) {
    return this.seekToEnd(topicPartitions, null);
  }

  @Override
  public KafkaReadStream<K, V> seekToEnd(Collection<TopicPartition> topicPartitions, Handler<AsyncResult<Void>> completionHandler) {

    this.submitTask((consumer, future) -> {
      consumer.seekToEnd(topicPartitions);
      if (future != null) {
        future.complete();
      }
    }, completionHandler);

    return this;
  }

  @Override
  public KafkaReadStream<K, V> seekToBeginning(Collection<TopicPartition> topicPartitions) {
    return this.seekToBeginning(topicPartitions, null);
  }

  @Override
  public KafkaReadStream<K, V> seekToBeginning(Collection<TopicPartition> topicPartitions, Handler<AsyncResult<Void>> completionHandler) {

    this.submitTask((consumer, future) -> {
      consumer.seekToBeginning(topicPartitions);
      if (future != null) {
        future.complete();
      }
    }, completionHandler);

    return this;
  }

  @Override
  public KafkaReadStream<K, V> seek(TopicPartition topicPartition, long offset) {
    return this.seek(topicPartition, offset, null);
  }

  @Override
  public KafkaReadStream<K, V> seek(TopicPartition topicPartition, long offset, Handler<AsyncResult<Void>> completionHandler) {

    this.submitTask((consumer, future) -> {
      consumer.seek(topicPartition, offset);
      if (future != null) {
        future.complete();
      }
    }, completionHandler);

    return this;
  }

  @Override
  public KafkaReadStream<K, V> partitionsRevokedHandler(Handler<Collection<TopicPartition>> handler) {
    this.partitionsRevokedHandler = handler;
    return this;
  }

  @Override
  public KafkaReadStream<K, V> partitionsAssignedHandler(Handler<Collection<TopicPartition>> handler) {
    this.partitionsAssignedHandler = handler;
    return this;
  }

  @Override
  public KafkaReadStream<K, V> subscribe(Set<String> topics) {
    return subscribe(topics, null);
  }

  @Override
  public KafkaReadStream<K, V> subscribe(Set<String> topics, Handler<AsyncResult<Void>> completionHandler) {

    if (this.recordHandler == null) {
      throw new IllegalStateException();
    }

    if (this.closed.compareAndSet(true, false)) {

      this.start((consumer, future) -> {
        consumer.subscribe(topics, this.rebalanceListener);
        this.resume();
        if (future != null) {
          future.complete();
        }
      }, completionHandler);

    } else {

      this.submitTask((consumer, future) -> {
        consumer.subscribe(topics, this.rebalanceListener);
        if (future != null) {
          future.complete();
        }
      }, completionHandler);
    }

    return this;
  }

  @Override
  public KafkaReadStream<K, V> unsubscribe() {
    return this.unsubscribe(null);
  }

  @Override
  public KafkaReadStream<K, V> unsubscribe(Handler<AsyncResult<Void>> completionHandler) {

    this.submitTask((consumer, future) -> {
      consumer.unsubscribe();
      if (future != null) {
        future.complete();
      }
    }, completionHandler);

    return this;
  }

  @Override
  public KafkaReadStream<K, V> subscription(Handler<AsyncResult<Set<String>>> handler) {

    this.submitTask((consumer, future) -> {
      Set<String> subscription = consumer.subscription();
      if (future != null) {
        future.complete(subscription);
      }
    }, handler);

    return this;
  }

  @Override
  public KafkaReadStream<K, V> assign(Collection<TopicPartition> partitions) {
    return this.assign(partitions, null);
  }

  @Override
  public KafkaReadStream<K, V> assign(Collection<TopicPartition> partitions, Handler<AsyncResult<Void>> completionHandler) {

    if (this.recordHandler == null) {
      throw new IllegalStateException();
    }

    if (this.closed.compareAndSet(true, false)) {

      this.start((consumer, future) -> {
        consumer.assign(partitions);
        this.resume();
        if (future != null) {
          future.complete();
        }
      }, completionHandler);

    } else {

      this.submitTask((consumer, future) -> {
        consumer.assign(partitions);
        if (future != null) {
          future.complete();
        }
      }, completionHandler);
    }

    return this;
  }

  @Override
  public KafkaReadStream<K, V> assignment(Handler<AsyncResult<Set<TopicPartition>>> handler) {

    this.submitTask((consumer, future) -> {
      Set<TopicPartition> partitions = consumer.assignment();
      if (future != null) {
        future.complete(partitions);
      }
    }, handler);

    return this;
  }

  @Override
  public KafkaReadStream<K, V> listTopics(Handler<AsyncResult<Map<String,List<PartitionInfo>>>> handler) {

    this.submitTask((consumer, future) -> {
      Map<String, List<PartitionInfo>> topics = consumer.listTopics();
      if (future != null) {
        future.complete(topics);
      }
    }, handler);

    return this;
  }

  @Override
  public void commit() {
    this.commit((Handler<AsyncResult<Map<TopicPartition, OffsetAndMetadata>>>) null);
  }

  @Override
  public void commit(Handler<AsyncResult<Map<TopicPartition, OffsetAndMetadata>>> completionHandler) {
    this.commit(null, completionHandler);
  }

  @Override
  public void commit(Map<TopicPartition, OffsetAndMetadata> offsets) {
    this.commit(offsets, null);
  }

  @Override
  public void commit(Map<TopicPartition, OffsetAndMetadata> offsets, Handler<AsyncResult<Map<TopicPartition, OffsetAndMetadata>>> completionHandler) {
    this.submitTask((consumer, future) -> {
      OffsetCommitCallback callback = (result, exception) -> {
        if (future != null) {
          if (exception != null) {
            future.fail(exception);
          } else {
            future.complete(result);
          }
        }
      };
      if (offsets == null) {
        consumer.commitAsync(callback);
      } else {
        consumer.commitAsync(offsets, callback);
      }
    }, completionHandler);
  }

  @Override
  public KafkaReadStreamImpl<K, V> exceptionHandler(Handler<Throwable> handler) {
    return this;
  }

  @Override
  public KafkaReadStreamImpl<K, V> handler(Handler<ConsumerRecord<K, V>> handler) {
    this.recordHandler = handler;
    return this;
  }

  @Override
  public KafkaReadStreamImpl<K, V> pause() {
    this.paused.set(true);
    return this;
  }

  @Override
  public KafkaReadStreamImpl<K, V> resume() {
    if (this.paused.compareAndSet(true, false)) {
      this.schedule(0);
    }
    return this;
  }

  @Override
  public KafkaReadStreamImpl<K, V> endHandler(Handler<Void> endHandler) {
    return this;
  }

  @Override
  public void close(Handler<Void> completionHandler) {

    if (this.closed.compareAndSet(false, true)) {
      this.worker.submit(() -> {
        this.consumer.close();
        if (completionHandler != null) {
          this.context.runOnContext(completionHandler);
        }
      });
      this.consumer.wakeup();
    }
  }
}
