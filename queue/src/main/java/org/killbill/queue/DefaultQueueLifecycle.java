/* 
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.killbill.queue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weakref.jmx.Managed;

import org.killbill.queue.api.PersistentQueueConfig;
import org.killbill.queue.api.QueueLifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;


public abstract class DefaultQueueLifecycle implements QueueLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DefaultQueueLifecycle.class);

    public static final String QUEUE_NAME = "Queue";

    protected static final long waitTimeoutMs = 15L * 1000L; // 15 seconds
    private final static long ONE_MILLION = 1000L * 1000L;

    private final int nbThreads;
    private final String svcQName;
    protected final PersistentQueueConfig config;
    private boolean isProcessingEvents;
    private volatile int curActiveThreads;

    protected final Executor executor;
    protected final ObjectMapper objectMapper;

    protected final AtomicBoolean isStarted = new AtomicBoolean(false);

    // Allow to disable/re-enable notification processing through JMX
    private final AtomicBoolean isProcessingSuspended;

    public DefaultQueueLifecycle(final String svcQName, final Executor executor, final int nbThreads, final PersistentQueueConfig config) {
        this(svcQName, executor, nbThreads, config, QueueObjectMapper.get());
    }

    public DefaultQueueLifecycle(final String svcQName, final Executor executor, final int nbThreads, final PersistentQueueConfig config, final ObjectMapper objectMapper) {
        this.executor = executor;
        this.nbThreads = nbThreads;
        this.svcQName = svcQName;
        this.config = config;
        this.isProcessingEvents = false;
        this.curActiveThreads = 0;
        this.isProcessingSuspended = new AtomicBoolean(false);
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean startQueue() {
        if (config.isProcessingOff() || !isStarted.compareAndSet(false, true)) {
            return false;
        }

        isProcessingEvents = true;
        curActiveThreads = 0;

        final DefaultQueueLifecycle thePersistentQ = this;
        final CountDownLatch doneInitialization = new CountDownLatch(nbThreads);

        log.info(String.format("%s: Starting with %d threads",
                               svcQName, nbThreads));

        for (int i = 0; i < nbThreads; i++) {
            executor.execute(new Runnable() {
                @Override
                public void run() {

                    log.info(String.format("%s: Thread %s [%d] starting",
                                           svcQName,
                                           Thread.currentThread().getName(),
                                           Thread.currentThread().getId()));

                    synchronized (thePersistentQ) {
                        curActiveThreads++;
                    }

                    doneInitialization.countDown();

                    try {
                        while (true) {
                            if (!isProcessingEvents) {
                                break;
                            }

                            final long beforeLoop = System.nanoTime();
                            try {
                                if (!isProcessingSuspended.get()) {
                                    doProcessEvents();
                                }
                            } catch (Exception e) {
                                log.warn(String.format("%s: Thread  %s  [%d] got an exception, catching and moving on...",
                                                       svcQName,
                                                       Thread.currentThread().getName(),
                                                       Thread.currentThread().getId()), e);
                            } finally {
                                final long afterLoop = System.nanoTime();
                                sleepALittle((afterLoop - beforeLoop) / ONE_MILLION);
                            }
                        }
                    } catch (InterruptedException e) {
                        log.info(String.format("%s: Thread %s got interrupted, exting... ", svcQName, Thread.currentThread().getName()));
                    } catch (Throwable e) {
                        log.error(String.format("%s: Thread %s got an exception, exting... ", svcQName, Thread.currentThread().getName()), e);
                    } finally {
                        log.info(String.format("%s: Thread %s has exited", svcQName, Thread.currentThread().getName()));
                        synchronized (thePersistentQ) {
                            curActiveThreads--;
                            thePersistentQ.notify();
                        }
                    }
                }

                private void sleepALittle(long loopTimeMsec) throws InterruptedException {
                    final long remainingSleepTime = config.getSleepTimeMs() - loopTimeMsec;
                    if (remainingSleepTime > 0) {
                        Thread.sleep(remainingSleepTime);
                    }
                }
            });
        }
        try {
            final boolean success = doneInitialization.await(waitTimeoutMs, TimeUnit.MILLISECONDS);
            if (!success) {

                log.warn(String.format("%s: Failed to wait for all threads to be started, got %d/%d", svcQName, (nbThreads - doneInitialization.getCount()), nbThreads));
            } else {
                log.info(String.format("%s: Done waiting for all threads to be started, got %d/%d", svcQName, (nbThreads - doneInitialization.getCount()), nbThreads));
            }
        } catch (InterruptedException e) {
            log.warn(String.format("%s: Start sequence, got interrupted", svcQName));
        }
        return true;
    }


    @Override
    public void stopQueue() {
        if (config.isProcessingOff() || !isStarted.compareAndSet(true, false)) {
            return;
        }

        int remaining = 0;
        try {
            synchronized (this) {
                isProcessingEvents = false;
                final long ini = System.currentTimeMillis();
                long remainingWaitTimeMs = waitTimeoutMs;
                while (curActiveThreads > 0 && remainingWaitTimeMs > 0) {
                    wait(100);
                    remainingWaitTimeMs = waitTimeoutMs - (System.currentTimeMillis() - ini);
                }
                remaining = curActiveThreads;
            }

        } catch (InterruptedException ignore) {
            log.info(String.format("%s: Stop sequence has been interrupted, remaining active threads = %d", svcQName, curActiveThreads));
        } finally {
            if (remaining > 0) {
                log.error(String.format("%s: Stop sequence completed with %d active remaing threads", svcQName, curActiveThreads));
            } else {
                log.info(String.format("%s: Stop sequence completed with %d active remaing threads", svcQName, curActiveThreads));
            }
            curActiveThreads = 0;
        }
    }

    @Managed(description="suspend processing for all notifications")
    public void suspendNotificationProcessing() {
        isProcessingSuspended.set(true);
    }

    @Managed(description="resume processing for all notifications")
    public void resumeNotificationProcessing() {
        isProcessingSuspended.set(false);
    }

    @Managed(description="check whether notification processing is suspended")
    public boolean isNotificationProcessingSuspended() {
        return isProcessingSuspended.get();
    }

    public static <T> T deserializeEvent(final String className, final ObjectMapper objectMapper, final String json) {
        try {
            final Class<?> claz = Class.forName(className);
            return (T) objectMapper.readValue(json, claz);
        } catch (Exception e) {
            log.error(String.format("Failed to deserialize json object %s for class %s", json, className), e);
            return null;
        }
    }

    public abstract int doProcessEvents();

    public boolean isStarted() {
        return isStarted.get();
    }
}
