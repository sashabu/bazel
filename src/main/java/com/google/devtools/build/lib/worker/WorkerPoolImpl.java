// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.worker;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.worker.WorkerProcessStatus.Status;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Implementation of the WorkerPool.
 *
 * <p>TODO(b/323880131): Remove documentation once we completely remove the legacy implementation.
 *
 * <p>Difference in internal implementation from {@code WorkerPoolLegacy}:
 *
 * <ul>
 *   <li>Legacy: WorkerPoolLegacy wraps multiple {@code SimpleWorkerPool} for each mnemonic. Each
 *       SimpleWorkerPool contains {@code Worker} instances capped per {@code WorkerKey}.
 *   <li>Current: This implementation flattens this to have a single {@code WorkerKeyPool} for each
 *       worker key (we don't need the indirection in referencing both mnemonic and worker key since
 *       the mnemonic is part of the key).
 *   <li>Legacy: SimpleWorkerPool extends {@code GenericKeyedObjectPool} that handles the logic to
 *       concurrent calls to borrow, return, invalidate and evict workers.
 *   <li>Current: WorkerKeyPool replaces this functionality directly, but can only handle pool logic
 *       for a single key (as compared to SimpleWorkerPool that handles multiple worker keys of the
 *       same mnemonic). Additionally, it bakes in pool shrinking logic so that we can handle
 *       concurrent calls.
 * </ul>
 */
public class WorkerPoolImpl implements WorkerPool {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /** Unless otherwise specified, the max number of workers per WorkerKey. */
  private static final int DEFAULT_MAX_SINGLEPLEX_WORKERS = 4;

  /** Unless otherwise specified, the max number of multiplex workers per WorkerKey. */
  private static final int DEFAULT_MAX_MULTIPLEX_WORKERS = 8;

  private final WorkerFactory factory;

  private final ImmutableMap<String, Integer> singleplexMaxInstances;
  private final ImmutableMap<String, Integer> multiplexMaxInstances;
  private final ConcurrentHashMap<WorkerKey, WorkerKeyPool> pools = new ConcurrentHashMap<>();

  public WorkerPoolImpl(WorkerPoolConfig config) {
    this.factory = config.getWorkerFactory();
    this.singleplexMaxInstances =
        getMaxInstances(config.getWorkerMaxInstances(), DEFAULT_MAX_SINGLEPLEX_WORKERS);
    this.multiplexMaxInstances =
        getMaxInstances(config.getWorkerMaxMultiplexInstances(), DEFAULT_MAX_MULTIPLEX_WORKERS);
  }

  private static ImmutableMap<String, Integer> getMaxInstances(
      List<Entry<String, Integer>> maxInstances, int defaultMaxWorkers) {
    LinkedHashMap<String, Integer> newConfigBuilder = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : maxInstances) {
      if (entry.getValue() != null) {
        newConfigBuilder.put(entry.getKey(), entry.getValue());
      } else if (entry.getKey() != null) {
        newConfigBuilder.put(entry.getKey(), defaultMaxWorkers);
      }
    }
    return ImmutableMap.copyOf(newConfigBuilder);
  }

  @Override
  public int getMaxTotalPerKey(WorkerKey key) {
    return getPool(key).getAvailableQuota();
  }

  @Override
  public int getNumActive(WorkerKey key) {
    return getPool(key).getNumActive();
  }

  @Override
  public ImmutableSet<Integer> evictWorkers(ImmutableSet<Integer> workerIdsToEvict)
      throws InterruptedException {
    // TODO: Without having the Worker objects themselves, we can't directly pass the worker to the
    // pool to be evicted.
    ImmutableSet<Integer> evictedWorkerIds =
        pools.values().stream()
            .flatMap(p -> p.evictWorkers(workerIdsToEvict).stream())
            .collect(toImmutableSet());
    return workerIdsToEvict.stream().filter(evictedWorkerIds::contains).collect(toImmutableSet());
  }

  @Override
  public ImmutableSet<Integer> getIdleWorkers() throws InterruptedException {
    return pools.values().stream()
        .flatMap(p -> p.getIdleWorkers().stream())
        .collect(toImmutableSet());
  }

  @Override
  public Worker borrowObject(WorkerKey key) throws IOException, InterruptedException {
    return getPool(key).borrowWorker();
  }

  @Override
  public void returnObject(WorkerKey key, Worker obj) {
    getPool(key).returnWorker(/* worker= */ obj);
  }

  @Override
  public void invalidateObject(WorkerKey key, Worker obj) throws InterruptedException {
    invalidateWorker(
        /* worker= */ obj, /* shouldShrinkPool= */ obj.getStatus().isPendingEviction());
  }

  /**
   * TODO(b/323880131): This should be the main interface once the we remove the legacy worker pool
   * implementation.
   */
  private void invalidateWorker(Worker worker, boolean shouldShrinkPool) {
    getPool(worker.getWorkerKey()).invalidateWorker(worker, shouldShrinkPool);
  }

  @Override
  public void reset() {
    for (WorkerKeyPool pool : pools.values()) {
      pool.reset();
    }
  }

  @Override
  public void close() {
    for (WorkerKeyPool pool : pools.values()) {
      pool.close();
    }
  }

  private WorkerKeyPool getPool(WorkerKey key) {
    return pools.computeIfAbsent(key, this::createPool);
  }

  private WorkerKeyPool createPool(WorkerKey key) {
    if (key.isMultiplex()) {
      return new WorkerKeyPool(
          key,
          multiplexMaxInstances.getOrDefault(key.getMnemonic(), DEFAULT_MAX_MULTIPLEX_WORKERS));
    }
    return new WorkerKeyPool(
        key,
        singleplexMaxInstances.getOrDefault(key.getMnemonic(), DEFAULT_MAX_SINGLEPLEX_WORKERS));
  }

  /**
   * Actual pool implementation that handles the borrowing, returning and invalidation of workers of
   * a single worker key.
   */
  private class WorkerKeyPool {

    private final WorkerKey key;
    private final int max;
    // We maintain this as a separate counter from the activeSet so that we can create workers
    // without locking the pool, while maintaining atomicity on
    private final AtomicInteger acquired = new AtomicInteger(0);
    private final AtomicInteger shrunk = new AtomicInteger(0);

    private final BlockingDeque<Worker> idleQueue = new LinkedBlockingDeque<>();

    /**
     * The waiting queue is meant to block borrowers when there are no workers available. With
     * workers as a resource, workers are only borrowed when they get they are available, so this
     * doesn't get used, i.e. there shouldn't be any borrowers waiting here, where the {@code
     * ResourceManager} handles the proper synchronization to ensure that workers are borrowed
     * together with its allocated resources.
     *
     * <p>Regardless, this implementation is still included to ensure correctness such that that
     * multiple threads can still borrow concurrently, without needing to check how many workers are
     * actually available (blocking if unavailable).
     */
    private final BlockingDeque<WorkerLatch> waitingQueue = new LinkedBlockingDeque<>();

    private final Set<Worker> activeSet = new HashSet<>();

    public WorkerKeyPool(WorkerKey key, int max) {
      this.key = key;
      this.max = max;
    }

    private synchronized Set<Integer> evictWorkers(Set<Integer> workerIdsToEvict) {
      Set<Integer> evictedWorkerIds = new HashSet<>();
      for (Worker worker : idleQueue) {
        if (workerIdsToEvict.contains(worker.getWorkerId())) {
          evictedWorkerIds.add(worker.getWorkerId());
          worker.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE);
          // Currently when evicting idle workers, we do not shrink the pool. The pool is only
          // shrunk when we have to postpone invalidation of the worker.
          invalidateWorker(worker, /* shouldShrinkPool= */ false);
          idleQueue.remove(worker);
          logger.atInfo().log(
              "Evicted %s worker (id %d, key hash %d).",
              worker.getWorkerKey().getMnemonic(),
              worker.getWorkerId(),
              worker.getWorkerKey().hashCode());
        }
        // TODO(b/323880131): Move postponing of invalidation from {@code WorkerLifecycleManager}
        // here, since all we need to do is to update the statuses. We keep it like this for now
        // to preserve the existing behavior.
      }
      return evictedWorkerIds;
    }

    private synchronized int getNumActive() {
      return acquired.get();
    }

    private synchronized int getAvailableQuota() {
      return max - shrunk.get();
    }

    // Callers should atomically check to confirm that workers are available before calling this
    // method or risk being blocked waiting for a worker to be available.
    private Worker borrowWorker() throws IOException, InterruptedException {
      Worker worker = null;
      WorkerLatch latch = null;
      // We don't want to hold the lock on the pool while creating or waiting for a worker or quota
      // to be available.
      synchronized (this) {
        while (!idleQueue.isEmpty()) {
          worker = idleQueue.takeLast();
          if (factory.validateWorker(worker.getWorkerKey(), worker)) {
            acquired.incrementAndGet();
            break;
          }
          invalidateWorker(worker, /* shouldShrinkPool= */ false);
          worker = null;
        }

        if (worker == null) {
          // If we were unable to get an idle worker, then either create or wait for one.
          if (getAvailableQuota() - getNumActive() > 0) {
            // No idle workers, but we have space to create another.
            acquired.incrementAndGet();
          } else {
            latch = new WorkerLatch();
            waitingQueue.add(latch);
          }
        }
      }

      if (latch != null) {
        // Wait until the resources are available. We cannot do this why synchronized because that
        // would deadlock by blocking other threads from returning and thus freeing up quota for
        // this to proceed.
        worker = latch.await();
      }

      if (worker == null) {
        worker = factory.create(key);
      }

      activeSet.add(worker);

      checkArgument(
          getAvailableQuota() - getNumActive() >= 0,
          "Worker pool (mnemonic %s) does not have space to create another worker.",
          key.getMnemonic());
      return worker;
    }

    private synchronized void returnWorker(Worker worker) {
      if (!factory.validateWorker(worker.getWorkerKey(), worker)) {
        invalidateWorker(worker, true);
        return;
      }

      activeSet.remove(worker);

      WorkerLatch latch = waitingQueue.poll();
      if (latch != null) {
        // Pass the worker directly to the waiting thread.
        latch.countDown(worker);
      } else {
        acquired.decrementAndGet();
        idleQueue.addLast(worker);
      }
    }

    private synchronized void invalidateWorker(Worker worker, boolean shouldShrinkPool) {
      factory.destroyWorker(worker.getWorkerKey(), worker);

      if (activeSet.remove(worker)) {
        acquired.decrementAndGet();
      } else {
        idleQueue.remove(worker);
        return;
      }

      // We don't want to shrink the pool to 0.
      if (shouldShrinkPool && getAvailableQuota() > 1) {
        shrunk.incrementAndGet();
        return;
      }

      // If invalidating the worker has resulted in the freeing up of effective quota for waiting
      // threads (also taking into account whether it was shrunk), then we signal for the next
      // waiting thread proceed.
      WorkerLatch latch = waitingQueue.poll();
      if (latch != null) {
        // We signal to the waiting thread that it can proceed, but it has to create a worker
        // for itself.
        latch.countDown(null);
        // We need to increment while synchronized here, so that we don't race with another
        // thread that might borrow (thus taking up the quota) before the waiting thread actually
        // manages to proceed (and increment on its own).
        acquired.getAndIncrement();
      }
    }

    /**
     * It is not important that we synchronize here, the {@code WorkerLifecycleManager} takes the
     * idle workers and figures out (non-atomically with respect to this instance) which workers to
     * evict. So it is possible that an idle worker gets acquired before it decides to evict a
     * previously idle worker.
     */
    public Set<Integer> getIdleWorkers() {
      return idleQueue.stream().map(Worker::getWorkerId).collect(toImmutableSet());
    }

    private void reset() {
      shrunk.set(0);
      logger.atInfo().log(
          "clearing shrunk values for %s (key hash %d) worker pool",
          key.getMnemonic(), key.hashCode());
    }

    private void close() {}
  }

  /**
   * Used to pass workers from threads that are returning the worker to the pool, bypassing the
   * queue.
   */
  private static class WorkerLatch {
    final CountDownLatch latch = new CountDownLatch(1);
    @Nullable volatile Worker worker = null;

    /** Returns a worker instance that has been freed, or null if the worker needs to be created. */
    @Nullable
    public Worker await() throws InterruptedException {
      latch.await();
      return worker;
    }

    public void countDown(@Nullable Worker worker) {
      this.worker = worker;
      latch.countDown();
    }
  }
}
