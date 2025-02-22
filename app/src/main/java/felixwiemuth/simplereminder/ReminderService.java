/*
 * Copyright (C) 2018-2021 Felix Wiemuth and contributors (see CONTRIBUTORS.md)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package felixwiemuth.simplereminder;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.Date;

import felixwiemuth.simplereminder.data.Reminder;
import felixwiemuth.simplereminder.ui.EditReminderDialogActivity;
import felixwiemuth.simplereminder.util.DateTimeUtil;
import felixwiemuth.simplereminder.util.EnumUtil;
import felixwiemuth.simplereminder.util.ImplementationError;
import lombok.Builder;

/**
 * Responsible for reminder scheduling and notifications. Handles scheduled reminders when they are due. May only be started with an intent created via the provided intent builder ({@link #intentBuilder()}).
 * <p>
 * Expects that the notification channel {@link #NOTIFICATION_CHANNEL_REMINDER} exists. Use {@link #createNotificationChannel(Context)} to create it.
 *
 * @author Felix Wiemuth
 */
public class ReminderService extends IntentService {
    /**
     * ID of the main notification channel "Reminder".
     */
    public static final String NOTIFICATION_CHANNEL_REMINDER = "Reminder";
    private static final String EXTRA_INT_ID = "felixwiemuth.simplereminder.ReminderService.extra.ID";
    private static final String EXTRA_LONG_NEXT_NAG_TIME = "felixwiemuth.simplereminder.ReminderService.extra.NEXT_NAG_TIME";
    private static final String ACTION_START = "felixwiemuth.simplereminder.ReminderService.action.START";

    private static Uri defaultSound;

    /**
     * Specifies the arguments to call this service.
     */
    @Builder
    public static class Arguments {
        @Builder.Default
        private int id = -1;
        /**
         * In case of {@link Action#NAG}, when to next repeat the notification.
         */
        @Builder.Default
        private final long nextNagTime = Long.MIN_VALUE;
        private Action action;

        public static class ArgumentsBuilder {

            public static class IncompleteArgumentsException extends RuntimeException {
                public IncompleteArgumentsException(String message) {
                    super(message);
                }
            }

            /**
             * Create the intent. May only be called after all fields have been set.
             *
             * @param context
             * @return
             * @throws IncompleteArgumentsException if not all fields have been set
             */
            public Intent build(Context context) throws IncompleteArgumentsException {
                Intent intent = new Intent(context, ReminderService.class);

                if (id < 0) {
                    throw new IncompleteArgumentsException("Id not specified or not valid (must be >=0).");
                }
                if (action == null) {
                    throw new IncompleteArgumentsException("Action not specified.");
                }

                intent.putExtra(ReminderService.EXTRA_INT_ID, id);
                if (nextNagTime != Long.MIN_VALUE) {
                    intent.putExtra(ReminderService.EXTRA_LONG_NEXT_NAG_TIME, nextNagTime);
                }
                // Note: Setting an action seems to prevent extras being removed from intents, see https://stackoverflow.com/questions/15343840/intent-extras-missing-when-activity-started.
                // It happened that the service was called without extras after introducing editing of reminders.
                // This might, however, also have happened due to accidental reuse of intents when rescheduling a reminder
                // (the intent used to cancel the original alarm could have been considered equal and reuesed for the new alarm).
                intent.setAction(ACTION_START);
                EnumUtil.serialize(action).to(intent);

                return intent;
            }

            /**
             * Create a pending intent that will start the service. Sets correct request code.
             * Uses flag {@link PendingIntent#FLAG_CANCEL_CURRENT} to make sure no old intent is reused.
             *
             * @param context
             * @return
             */
            public PendingIntent buildPendingIntent(Context context) {
                // NOTE: This relies on reminder IDs always being even integers.
                int requestCode;
                switch (action) {
                    case NOTIFY:
                    case NAG:
                        requestCode = id;
                        break;
                    case MARK_DONE:
                        requestCode = id + 1;
                        break;
                    default:
                        throw new ImplementationError("Unknown action.");
                }
                return PendingIntent.getService(context, requestCode, build(context), PendingIntent.FLAG_CANCEL_CURRENT);
            }
        }
    }

    public static Arguments.ArgumentsBuilder intentBuilder() {
        return Arguments.builder();
    }

    /**
     * Get a pending intent to be used to cancel an alarm with a pending {@link Action#NOTIFY} intent created with {@link #intentBuilder()}.
     *
     * @param context
     * @param id      the ID of the reminder to be cancelled
     * @return
     */
    public static PendingIntent getCancelAlarmNotifyIntent(Context context, int id) {
        // Note: This intent is only used to be passed to AlarmManager.cancel(...), so it shouldn't start the service.
        // It must be equal to the intent used when scheduling the alarm (extras are not compared)
        Intent intent = new Intent(context, ReminderService.class);
        intent.setAction(ACTION_START);
        // Note that it shouldn't matter whether this reuses an intent that matches; it is enough that it matches to cancel the alarm.
        return PendingIntent.getService(context, id, intent, 0); // Request code must be the same as when scheduled.
    }

    public ReminderService() {
        super("SimpleReminder Reminder Service");
    }

    interface ReminderAction {
        void run(Context context, Reminder reminder, long nextNagTime);
    }

    enum Action {
        NOTIFY(
                (context, reminder, nextNagTime) -> {
                    showReminder(context, reminder);
                }
        ),
        /**
         * Repeat the notification and schedule the next repetition for nagging reminders.
         */
        NAG(
                (context, reminder, nextNagTime) -> {
                    nag(context, reminder, nextNagTime);
                }
        ),
        MARK_DONE(
                (context, reminder, nextNagTime) -> {
                    // Cancel possible further alarms (nagging reminders)
                    cancelReminder(context, reminder.getId());

                    reminder.setStatus(Reminder.Status.DONE);
                    ReminderManager.updateReminder(context, reminder, false);
                }
        );

        private ReminderAction reminderAction;

        Action(ReminderAction reminderAction) {
            this.reminderAction = reminderAction;
        }

        void run(Context context, Reminder reminder, long nextNagTime) {
            reminderAction.run(context, reminder, nextNagTime);
        }
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        if (intent == null) {
            Log.w("ReminderService", "Service called with no intent.");
            return;
        }
        if (!intent.hasExtra(EXTRA_INT_ID)) {
            throw new IllegalArgumentException("ReminderService called without reminder ID extra.");
        }
        int id = intent.getExtras().getInt(EXTRA_INT_ID, -1);
        long nextNagTime = intent.getExtras().getLong(EXTRA_LONG_NEXT_NAG_TIME, Long.MIN_VALUE);
        Action action = EnumUtil.deserialize(Action.class).from(intent);
        /* Try to get the reminder with the given ID. Note that race conditions with reminder updates
           are possible which usually would lead to the intent (alarm) being cancelled but the
           service has already been invoked. In case of reminder deletion, it does not exist
           anymore and we ignore this intent (it is not relevant anymore). In case of rescheduling
           or marking done via the reminders list, the reminder can still be queried and the intent
           will be processed. This can for example lead to a reminder just having been rescheduled
           being shown immediately, which is undesired but only happens when the user reschedules
           close enough to when the reminder becomes due. There is also a good chance that the user
           will notice it (if not e.g. in Do Not Disturb mode). In addition, the notification will
           be shown again on the next due time if not dismissed until then.
         */
        Reminder reminder;
        try {
            reminder = ReminderManager.getReminder(this, id);
        } catch (ReminderManager.ReminderNotFoundException ex) {
            return;
        }
        action.run(this, reminder, nextNagTime);
    }

    /**
     * Show the reminder as appropriate and update its status. Should be used on due reminders.
     *
     * @param context
     * @param reminder
     */
    public static void showReminder(Context context, Reminder reminder) {
        sendNotification(context, reminder);
        reminder.setStatus(Reminder.Status.NOTIFIED);
        ReminderManager.updateReminder(context, reminder, false);
        if (reminder.isNagging()) {
            scheduleNextNag(context, reminder, reminder.getDate().getTime() + reminder.getNaggingRepeatIntervalInMillis());
        }
    }

    public static void nag(Context context, Reminder reminder, long nextNagTime) {
        // Send the same notification again (replaces the previous)
        sendNotification(context, reminder);

        // Schedule next repetition
        scheduleNextNag(context, reminder, nextNagTime);
    }

    private static void scheduleNextNag(Context context, Reminder reminder, long nextNagTime) {
        // Add exactly 60s to the timestamp
        long nextNextNagTime = nextNagTime + reminder.getNaggingRepeatIntervalInMillis();

        PendingIntent nagIntent = intentBuilder()
                .id(reminder.getId())
                .action(Action.NAG)
                .nextNagTime(nextNextNagTime)
                .buildPendingIntent(context);
        scheduleExact(context, new Date(nextNagTime), nagIntent);
    }

    /**
     * Send a notification with swipe and click actions related to the reminder.
     *
     * @param context
     * @param reminder
     */
    private static void sendNotification(Context context, Reminder reminder) {
        PendingIntent markDoneIntent = intentBuilder()
                .id(reminder.getId())
                .action(Action.MARK_DONE)
                .buildPendingIntent(context);

        Intent editReminderIntent = EditReminderDialogActivity.getIntentEditReminder(context, reminder.getId());
        PendingIntent editReminderPendingIntent = PendingIntent.getActivity(context, Reminder.getRequestCodeAddReminderDialogActivityPendingIntent(reminder.getId()), editReminderIntent, 0);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_REMINDER)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(context.getString(R.string.notification_title))
                .setContentText(reminder.getText())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(reminder.getText()))
                .setContentIntent(editReminderPendingIntent)
                .setDeleteIntent(markDoneIntent)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                // Applies for Android < 8
                .setPriority(Integer.valueOf(Prefs.getStringPref(R.string.prefkey_priority, "0", context)));

        // Applies for Android < 8
        if (Prefs.getBooleanPref(R.string.prefkey_enable_sound, false, context)) {
            builder.setSound(getDefaultSound()); // Set default notification sound
        }

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        notificationManager.notify(reminder.getId(), builder.build());
    }


    /**
     * Schedules a reminder if its time is not in the past.
     *
     * @param context
     * @param reminder
     */
    public static void scheduleReminder(Context context, Reminder reminder) {
        PendingIntent notifyIntent = intentBuilder()
                .id(reminder.getId())
                .action(Action.NOTIFY)
                .buildPendingIntent(context);

        scheduleExact(context, reminder.getDate(), notifyIntent);
    }

    private static void scheduleExact(Context context, Date date, PendingIntent pendingIntent) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, date.getTime(), pendingIntent);
            Log.d("ReminderService", "Set alarm (\"exact and allow while idle\") for " + DateTimeUtil.formatDateTime(date));
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, date.getTime(), pendingIntent);
            Log.d("ReminderService", "Set alarm (\"exact\") for " + DateTimeUtil.formatDateTime(date));
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, date.getTime(), pendingIntent);
            Log.d("ReminderService", "Set alarm for " + DateTimeUtil.formatDateTime(date));
        }

    }

    /**
     * Cancel a reminder, i.e., cancel if scheduled, remove notification if present.
     *
     * @param context
     * @param id
     */
    public static void cancelReminder(Context context, int id) {
        // Cancel possible notification
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        notificationManager.cancel(id);

        // Cancel possibly scheduled alarm
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        alarmManager.cancel(getCancelAlarmNotifyIntent(context, id));
    }

    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = context.getString(R.string.channel_name);
            String description = context.getString(R.string.channel_description);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_REMINDER, name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private static Uri getDefaultSound() {
        if (defaultSound == null) {
            defaultSound = Uri.parse("content://settings/system/notification_sound");
        }
        return defaultSound;
    }
}
