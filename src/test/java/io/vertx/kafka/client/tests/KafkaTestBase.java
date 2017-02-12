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

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.kafka.client.consumer.KafkaReadStream;
import io.vertx.kafka.client.producer.KafkaWriteStream;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Base class for tests
 */
public class KafkaTestBase {

  static void close(TestContext ctx, Consumer<Handler<Void>> producer) {
    if (producer != null) {
      Async closeAsync = ctx.async();
      producer.accept(v -> {
        closeAsync.complete();
      });
      closeAsync.awaitSuccess(10000);
    }
  }

  static void close(TestContext ctx, KafkaWriteStream<?, ?> producer) {
    if (producer != null) {
      close(ctx, handler -> producer.close(2000L, handler));
    }
  }

  static void close(TestContext ctx, KafkaReadStream<?, ?> consumer) {
    if (consumer != null) {
      KafkaTestBase.close(ctx, consumer::close);
    }
  }


  static <K, V> KafkaWriteStream<K, V> producer(Consumer<Future<KafkaWriteStream<K, V>>> builder) throws Exception {
    CompletableFuture<KafkaWriteStream<K, V>> fut = new CompletableFuture<>();
    builder.accept(Future.<KafkaWriteStream<K, V>>future().setHandler(ar -> {
      if (ar.succeeded()) {
        fut.complete(ar.result());
      } else {
        fut.completeExceptionally(ar.cause());
      }
    }));
    return fut.get(10, TimeUnit.SECONDS);
  }
}
