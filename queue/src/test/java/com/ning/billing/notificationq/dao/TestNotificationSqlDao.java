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

package com.ning.billing.notificationq.dao;

import java.util.List;
import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.tweak.HandleCallback;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.ning.billing.TestSetup;
import com.ning.billing.notificationq.DefaultNotification;
import com.ning.billing.notificationq.Notification;
import com.ning.billing.notificationq.dao.NotificationSqlDao.NotificationSqlMapper;
import com.ning.billing.queue.PersistentQueueEntryLifecycle.PersistentQueueEntryLifecycleState;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class TestNotificationSqlDao extends TestSetup {

    private static final String hostname = "Yop";

    private static final long TENANT_RECORD_ID = 37;

    private NotificationSqlDao dao;

    @BeforeClass(groups = "slow")
    public void beforeClass() throws Exception {
        super.beforeClass();
        dao = getDBI().onDemand(NotificationSqlDao.class);
    }

    @Test(groups = "slow")
    public void testBasic() throws InterruptedException {
        final long searchKey1 = 1242L;
        final String ownerId = UUID.randomUUID().toString();

        final String eventJson = UUID.randomUUID().toString();
        final DateTime effDt = new DateTime();
        final Notification notif = new DefaultNotification("testBasic", hostname, eventJson.getClass().getName(), eventJson, UUID.randomUUID(), UUID.randomUUID(), effDt,
                                                           searchKey1, TENANT_RECORD_ID);
        dao.insertNotification(notif);

        Thread.sleep(1000);
        final DateTime now = new DateTime();
        final List<Notification> notifications = dao.getReadyNotifications(now.toDate(), hostname, 3);
        assertNotNull(notifications);
        assertEquals(notifications.size(), 1);

        Notification notification = notifications.get(0);
        assertEquals(notification.getNotificationKey(), eventJson);
        validateDate(notification.getEffectiveDate(), effDt);
        assertEquals(notification.getOwner(), null);
        assertEquals(notification.getProcessingState(), PersistentQueueEntryLifecycleState.AVAILABLE);
        assertEquals(notification.getNextAvailableDate(), null);

        final DateTime nextAvailable = now.plusMinutes(5);
        final int res = dao.claimNotification(ownerId, nextAvailable.toDate(), notification.getRecordId(), now.toDate());
        assertEquals(res, 1);
        dao.insertClaimedHistory(ownerId, now.toDate(), notification.getRecordId(), searchKey1, TENANT_RECORD_ID);

        notification = fetchNotification(notification.getRecordId());
        assertEquals(notification.getNotificationKey(), eventJson);
        validateDate(notification.getEffectiveDate(), effDt);
        assertEquals(notification.getOwner(), ownerId);
        assertEquals(notification.getProcessingState(), PersistentQueueEntryLifecycleState.IN_PROCESSING);
        validateDate(notification.getNextAvailableDate(), nextAvailable);

        dao.clearNotification(notification.getRecordId(), ownerId);

        notification = fetchNotification(notification.getRecordId());
        assertEquals(notification.getNotificationKey(), eventJson);
        validateDate(notification.getEffectiveDate(), effDt);
        //assertEquals(notification.getOwner(), null);
        assertEquals(notification.getProcessingState(), PersistentQueueEntryLifecycleState.PROCESSED);
        validateDate(notification.getNextAvailableDate(), nextAvailable);
    }

    private Notification fetchNotification(final Long recordId) {
        return getDBI().withHandle(new HandleCallback<Notification>() {
            @Override
            public Notification withHandle(final Handle handle) throws Exception {
                return handle.createQuery("   select" +
                                          " record_id " +
                                          ", class_name" +
                                          ", event_json" +
                                          ", user_token" +
                                          ", future_user_token" +
                                          ", created_date" +
                                          ", creating_owner" +
                                          ", effective_date" +
                                          ", queue_name" +
                                          ", processing_owner" +
                                          ", processing_available_date" +
                                          ", processing_state" +
                                          ", search_key1" +
                                          ", search_key2" +
                                          "    from notifications " +
                                          " where " +
                                          " record_id = '" + recordId + "';")
                             .map(new NotificationSqlMapper())
                             .first();
            }
        });
    }

    private void validateDate(DateTime input, DateTime expected) {
        if (input == null && expected != null) {
            Assert.fail("Got input date null");
        }
        if (input != null && expected == null) {
            Assert.fail("Was expecting null date");
        }
        expected = truncateAndUTC(expected);
        input = truncateAndUTC(input);
        Assert.assertEquals(input, expected);
    }

    private DateTime truncateAndUTC(final DateTime input) {
        if (input == null) {
            return null;
        }
        final DateTime result = input.minus(input.getMillisOfSecond());
        return result.toDateTime(DateTimeZone.UTC);
    }
}
