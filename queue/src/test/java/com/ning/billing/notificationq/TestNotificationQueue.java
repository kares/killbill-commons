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

package com.ning.billing.notificationq;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.joda.time.DateTime;
import org.skife.jdbi.v2.Transaction;
import org.skife.jdbi.v2.TransactionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.ning.billing.TestSetup;
import com.ning.billing.notificationq.api.NotificationEvent;
import com.ning.billing.notificationq.api.NotificationQueueService;
import com.ning.billing.notificationq.api.NotificationQueueService.NotificationQueueHandler;
import com.ning.billing.notificationq.api.NotificationQueue;
import com.ning.billing.notificationq.dao.DummySqlTest;
import com.ning.billing.clock.ClockMock;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;

import static com.jayway.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.testng.Assert.assertEquals;

public class TestNotificationQueue extends TestSetup {

    private final Logger log = LoggerFactory.getLogger(TestNotificationQueue.class);

    private final static UUID TOKEN_ID = UUID.randomUUID();
    private final static long SEARCH_KEY_1 = 65;
    private final static long SEARCH_KEY_2 = 34;

    private NotificationQueueService queueService;

    private int eventsReceived;

    private static final class TestNotificationKey implements NotificationEvent, Comparable<TestNotificationKey> {

        private final String value;

        @JsonCreator
        public TestNotificationKey(@JsonProperty("value") final String value) {
            super();
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        @Override
        public int compareTo(TestNotificationKey arg0) {
            return value.compareTo(arg0.value);
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append(value);
            return sb.toString();
        }
    }

    @Override
    @BeforeClass(groups = "slow")
    public void beforeClass() throws Exception {
        super.beforeClass();
        queueService = new DefaultNotificationQueueService(getDBI(), clock, getNotificationQueueConfig());
    }

    @Override
    @BeforeMethod(groups = "slow")
    public void beforeMethod() throws Exception {
        super.beforeMethod();
        eventsReceived = 0;
    }

    /**
     * Test that we can post a notification in the future from a transaction and get the notification
     * callback with the correct key when the time is ready
     *
     * @throws Exception
     */
    @Test(groups = "slow")
    public void testSimpleNotification() throws Exception {

        final Map<NotificationEvent, Boolean> expectedNotifications = new TreeMap<NotificationEvent, Boolean>();

        final NotificationQueue queue = queueService.createNotificationQueue("test-svc",
                                                                             "foo",
                                                                             new NotificationQueueHandler() {
                                                                                 @Override
                                                                                 public void handleReadyNotification(final NotificationEvent eventJson, final DateTime eventDateTime, final UUID userToken, final Long searchKey1, final Long searchKey2) {
                                                                                     synchronized (expectedNotifications) {
                                                                                         log.info("Handler received key: " + eventJson);

                                                                                         expectedNotifications.put(eventJson, Boolean.TRUE);
                                                                                         expectedNotifications.notify();
                                                                                     }
                                                                                 }
                                                                             });

        queue.startQueue();

        final UUID key = UUID.randomUUID();
        final DummyObject obj = new DummyObject("foo", key);
        final DateTime now = new DateTime();
        final DateTime readyTime = now.plusMillis(2000);
        final NotificationEvent eventJson = new TestNotificationKey(key.toString());

        expectedNotifications.put(eventJson, Boolean.FALSE);

        DummySqlTest dummyDbi = getDBI().onDemand(DummySqlTest.class);
        dummyDbi.inTransaction(new Transaction<Object, DummySqlTest>() {
            @Override
            public Object inTransaction(final DummySqlTest transactional, final TransactionStatus status) throws Exception {
                queue.recordFutureNotificationFromTransaction(transactional, readyTime, eventJson, TOKEN_ID, SEARCH_KEY_1, SEARCH_KEY_2);
                log.info("Posted key: " + eventJson);
                return null;
            }
        });


        // Move time in the future after the notification effectiveDate
        ((ClockMock) clock).setDeltaFromReality(3000);


        // Notification should have kicked but give it at least a sec' for thread scheduling
        await().atMost(1, MINUTES)
                .until(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws
                                          Exception {
                        return expectedNotifications.get(eventJson);
                    }
                }

                      );

        queue.stopQueue();
        Assert.assertTrue(expectedNotifications.get(eventJson));
    }

    @Test(groups = "slow")
    public void testManyNotifications() throws Exception {
        final Map<NotificationEvent, Boolean> expectedNotifications = new TreeMap<NotificationEvent, Boolean>();

        final NotificationQueue queue = queueService.createNotificationQueue("test-svc",
                                                                             "many",
                                                                             new NotificationQueueHandler() {
                                                                                 @Override
                                                                                 public void handleReadyNotification(final NotificationEvent eventJson, final DateTime eventDateTime, final UUID userToken, final Long searchKey1, final Long searchKey2) {
                                                                                     synchronized (expectedNotifications) {
                                                                                         log.info("Handler received key: " + eventJson.toString());

                                                                                         expectedNotifications.put(eventJson, Boolean.TRUE);
                                                                                         expectedNotifications.notify();
                                                                                     }
                                                                                 }
                                                                             });
        queue.startQueue();

        final DateTime now = clock.getUTCNow();
        final int MAX_NOTIFICATIONS = 100;
        for (int i = 0; i < MAX_NOTIFICATIONS; i++) {

            final int nextReadyTimeIncrementMs = 1000;

            final UUID key = UUID.randomUUID();
            final DummyObject obj = new DummyObject("foo", key);
            final int currentIteration = i;

            final NotificationEvent eventJson = new TestNotificationKey(new Integer(i).toString());
            expectedNotifications.put(eventJson, Boolean.FALSE);


            DummySqlTest dummyDbi = getDBI().onDemand(DummySqlTest.class);
            dummyDbi.inTransaction(new Transaction<Object, DummySqlTest>() {
                @Override
                public Object inTransaction(final DummySqlTest transactional, final TransactionStatus status) throws Exception {
                    queue.recordFutureNotificationFromTransaction(transactional, now.plus((currentIteration + 1) * nextReadyTimeIncrementMs),
                                                                  eventJson, TOKEN_ID, SEARCH_KEY_1, SEARCH_KEY_2);
                    return null;
                }
            });

            // Move time in the future after the notification effectiveDate
            if (i == 0) {
                ((ClockMock) clock).setDeltaFromReality(nextReadyTimeIncrementMs);
            } else {
                ((ClockMock) clock).addDeltaFromReality(nextReadyTimeIncrementMs);
            }
        }

        // Wait a little longer since there are a lot of callback that need to happen
        int nbTry = MAX_NOTIFICATIONS + 1;
        boolean success = false;
        do {
            synchronized (expectedNotifications) {
                final Collection<Boolean> completed = Collections2.filter(expectedNotifications.values(), new Predicate<Boolean>() {
                    @Override
                    public boolean apply(final Boolean input) {
                        return input;
                    }
                });

                if (completed.size() == MAX_NOTIFICATIONS) {
                    success = true;
                    break;
                }
                log.info(String.format("BEFORE WAIT : Got %d notifications at time %s (real time %s)", completed.size(), clock.getUTCNow(), new DateTime()));
                expectedNotifications.wait(1000);
            }
        } while (nbTry-- > 0);

        queue.stopQueue();
        log.info("GOT SIZE " + Collections2.filter(expectedNotifications.values(), new Predicate<Boolean>() {
            @Override
            public boolean apply(final Boolean input) {
                return input;
            }
        }).size());
        assertEquals(success, true);
    }

    /**
     * Test that we can post a notification in the future from a transaction and get the notification
     * callback with the correct key when the time is ready
     *
     * @throws Exception
     */
    @Test(groups = "slow")
    public void testMultipleHandlerNotification() throws Exception {
        final Map<NotificationEvent, Boolean> expectedNotificationsFred = new TreeMap<NotificationEvent, Boolean>();
        final Map<NotificationEvent, Boolean> expectedNotificationsBarney = new TreeMap<NotificationEvent, Boolean>();

        final NotificationQueue queueFred = queueService.createNotificationQueue("UtilTest", "Fred", new NotificationQueueHandler() {
            @Override
            public void handleReadyNotification(final NotificationEvent eventJson, final DateTime eventDateTime, final UUID userToken, final Long searchKey1, final Long searchKey2) {
                log.info("Fred received key: " + eventJson);
                expectedNotificationsFred.put(eventJson, Boolean.TRUE);
                eventsReceived++;
            }
        });

        final NotificationQueue queueBarney = queueService.createNotificationQueue("UtilTest", "Barney", new NotificationQueueHandler() {
            @Override
            public void handleReadyNotification(final NotificationEvent eventJson, final DateTime eventDateTime, final UUID userToken, final Long searchKey1, final Long searchKey2) {
                log.info("Barney received key: " + eventJson);
                expectedNotificationsBarney.put(eventJson, Boolean.TRUE);
                eventsReceived++;
            }
        });
        queueFred.startQueue();
        //		We don't start Barney so it can never pick up notifications

        final UUID key = UUID.randomUUID();
        final DummyObject obj = new DummyObject("foo", key);
        final DateTime now = new DateTime();
        final DateTime readyTime = now.plusMillis(2000);
        final NotificationEvent eventJsonFred = new TestNotificationKey("Fred");

        final NotificationEvent eventJsonBarney = new TestNotificationKey("Barney");

        expectedNotificationsFred.put(eventJsonFred, Boolean.FALSE);
        expectedNotificationsFred.put(eventJsonBarney, Boolean.FALSE);

        DummySqlTest dummyDbi = getDBI().onDemand(DummySqlTest.class);
        dummyDbi.inTransaction(new Transaction<Object, DummySqlTest>() {
            @Override
            public Object inTransaction(final DummySqlTest transactional, final TransactionStatus status) throws Exception {
                queueFred.recordFutureNotificationFromTransaction(transactional, readyTime, eventJsonFred, TOKEN_ID, SEARCH_KEY_1, SEARCH_KEY_2);
                log.info("posted key: " + eventJsonFred.toString());
                queueBarney.recordFutureNotificationFromTransaction(transactional, readyTime, eventJsonBarney, TOKEN_ID, SEARCH_KEY_1, SEARCH_KEY_2);
                log.info("posted key: " + eventJsonBarney.toString());
                return null;
            }
        });

        // Move time in the future after the notification effectiveDate
        ((ClockMock) clock).setDeltaFromReality(3000);

        // Note the timeout is short on this test, but expected behaviour is that it times out.
        // We are checking that the Fred queue does not pick up the Barney event
        try {
            await().atMost(5, TimeUnit.SECONDS).until(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    return eventsReceived >= 2;
                }
            });
            Assert.fail("There should only have been one event for the queue to pick up - it got more than that");
        } catch (Exception e) {
            // expected behavior
        }

        queueFred.stopQueue();
        Assert.assertTrue(expectedNotificationsFred.get(eventJsonFred));
        Assert.assertFalse(expectedNotificationsFred.get(eventJsonBarney));
    }
}
