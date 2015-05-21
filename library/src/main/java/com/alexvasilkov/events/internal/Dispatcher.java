package com.alexvasilkov.events.internal;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.Log;

import com.alexvasilkov.events.Event;
import com.alexvasilkov.events.EventFailure;
import com.alexvasilkov.events.EventResult;
import com.alexvasilkov.events.EventStatus;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Dispatcher {

    private static final long MAX_TIME_IN_MAIN_THREAD = 10L;

    private static final List<EventTarget> TARGETS = new LinkedList<>();
    private static final LinkedList<Task> EXECUTION_QUEUE = new LinkedList<>();

    private static final Set<Event> ACTIVE_EVENTS = new HashSet<>();
    private static final Set<Task> ACTIVE_TASKS = new HashSet<>();

    private static final MainThreadHandler MAIN_THREAD = new MainThreadHandler();
    private static final ExecutorService BACKGROUND_EXECUTOR = Executors.newCachedThreadPool();


    // Registers events target
    public static void register(Object target) {
        MAIN_THREAD.register(target);
    }

    // Unregisters events target
    public static void unregister(Object target) {
        MAIN_THREAD.unregister(target);
    }

    // Schedules event execution
    public static void postEvent(Event event) {
        MAIN_THREAD.postEvent(event);
    }

    // Schedules result callback
    public static void postEventResult(Event event, EventResult result) {
        MAIN_THREAD.postEventResult(event, result);
    }

    // Schedules failure callback
    public static void postEventFailure(Event event, EventFailure failure) {
        MAIN_THREAD.postEventFailure(event, failure);
    }

    // Schedules finished status callback
    public static void postTaskFinished(Task task) {
        MAIN_THREAD.postTaskFinished(task);
    }

    // Schedules tasks execution on main thread
    private static void executeDelayed() {
        MAIN_THREAD.execute();
    }


    // Schedules status updates of all active events for given target.
    // Should be called on main thread.
    private static void scheduleStatusUpdates(EventTarget eventTarget, EventStatus status) {
        for (Event event : ACTIVE_EVENTS) {
            for (EventMethod m : eventTarget.methods) {
                if (event.getKey().equals(m.eventKey) && m.type == EventMethod.Type.STATUS) {
                    Utils.log(event.getKey(), m, "Scheduling status update for new target");
                    EXECUTION_QUEUE.addFirst(Task.create(eventTarget, m, event, status));
                }
            }
        }
    }

    // Schedules status update of given event for all registered targets.
    // Should be called on main thread.
    private static void scheduleStatusUpdates(Event event, EventStatus status) {
        for (EventTarget t : TARGETS) {
            for (EventMethod m : t.methods) {
                if (event.getKey().equals(m.eventKey) && m.type == EventMethod.Type.STATUS) {
                    Utils.log(event.getKey(), m, "Scheduling status update");
                    EXECUTION_QUEUE.add(Task.create(t, m, event, status));
                }
            }
        }
    }

    // Schedules handling of given event for all registered targets.
    // Should be called on main thread.
    private static void scheduleSubscribersInvocation(Event event) {
        for (EventTarget t : TARGETS) {
            for (EventMethod m : t.methods) {
                if (event.getKey().equals(m.eventKey) && m.type == EventMethod.Type.SUBSCRIBE) {

                    // If we'll find equivalent task (same target, same method, similar event)
                    // which is not yet running, then we can skip execution of current event
                    boolean skipEvent = false;

                    for (Task task : ACTIVE_TASKS) {
                        if (task.eventTarget == t && task.eventMethod == m && !task.isRunning
                                && Event.isDeeplyEqual(task.event, event)) {

                            Utils.log(event.getKey(), m, "Similar event found, skipping execution");

                            skipEvent = true;
                            break;
                        }
                    }

                    if (skipEvent) continue;

                    Utils.log(event.getKey(), m, "Scheduling event execution");

                    ((EventBase) event).handlersCount++;

                    Task task = Task.create(t, m, event);
                    EXECUTION_QUEUE.add(task);
                    ACTIVE_TASKS.add(task);
                }
            }
        }
    }

    // Schedules sending result to all registered targets.
    // Should be called on main thread.
    private static void scheduleResultCallbacks(Event event, EventResult result) {
        for (EventTarget t : TARGETS) {
            for (EventMethod m : t.methods) {
                if (event.getKey().equals(m.eventKey) && m.type == EventMethod.Type.RESULT) {
                    Utils.log(event.getKey(), m, "Scheduling result callback");
                    EXECUTION_QUEUE.add(Task.create(t, m, event, result));
                }
            }
        }
    }

    // Schedules sending failure callback to all registered targets.
    // Should be called on main thread.
    private static void scheduleFailureCallbacks(Event event, EventFailure failure) {
        // Sending failure callback for explicit handlers of given event
        for (EventTarget t : TARGETS) {
            for (EventMethod m : t.methods) {
                if (event.getKey().equals(m.eventKey) && m.type == EventMethod.Type.FAILURE) {
                    Utils.log(event.getKey(), m, "Scheduling failure callback");
                    EXECUTION_QUEUE.add(Task.create(t, m, event, failure));
                }
            }
        }

        // Sending failure callback to general handlers (with no particular event key)
        for (EventTarget t : TARGETS) {
            for (EventMethod m : t.methods) {
                if (EventsParams.EMPTY_KEY.equals(m.eventKey) && m.type == EventMethod.Type.FAILURE) {
                    Utils.log(event.getKey(), m, "Scheduling general failure callback");
                    EXECUTION_QUEUE.add(Task.create(t, m, event, failure));
                }
            }
        }
    }


    // Handling registration on main thread
    private static void handleRegistration(Object target) {
        if (target == null) throw new NullPointerException("Target cannot be null");

        for (EventTarget eventTarget : TARGETS) {
            if (eventTarget.target == target) {
                Log.e(Utils.TAG, "Target " + Utils.classToString(target) + " already registered");
                return;
            }
        }

        EventTarget eventTarget = new EventTarget(target);
        TARGETS.add(eventTarget);

        if (EventsParams.isDebug())
            Log.d(Utils.TAG, "Target " + Utils.classToString(target) + " | Registered");

        scheduleStatusUpdates(eventTarget, EventStatus.STARTED);
        executeDelayed();
    }

    // Handling un-registration on main thread
    private static void handleUnRegistration(Object target) {
        if (target == null) throw new NullPointerException("Target cannot be null");

        boolean isUnregistered = false;

        for (Iterator<EventTarget> iterator = TARGETS.iterator(); iterator.hasNext(); ) {
            EventTarget eventTarget = iterator.next();
            if (eventTarget.target == target) {
                eventTarget.isUnregistered = true;
                iterator.remove();
                isUnregistered = true;
                break;
            }
        }

        if (!isUnregistered)
            Log.e(Utils.TAG, "Target " + Utils.classToString(target) + " was not registered");

        if (EventsParams.isDebug())
            Log.d(Utils.TAG, "Target " + Utils.classToString(target) + " | Unregistered");
    }

    // Handling event posting on main thread
    private static void handleEventPost(Event event) {
        Utils.log(event.getKey(), "Handling posted event");

        int sizeBefore = EXECUTION_QUEUE.size();

        scheduleStatusUpdates(event, EventStatus.STARTED);
        scheduleSubscribersInvocation(event);

        if (((EventBase) event).handlersCount == 0) {
            Utils.log(event.getKey(), "No subscribers found");
            // Removing all scheduled STARTED status callbacks
            while (EXECUTION_QUEUE.size() > sizeBefore) EXECUTION_QUEUE.removeLast();
        } else {
            ACTIVE_EVENTS.add(event);
            executeDelayed();
        }
    }

    // Handling event result on main thread
    private static void handleEventResult(Event event, EventResult result) {
        if (!ACTIVE_EVENTS.contains(event)) {
            Utils.logE(event.getKey(), "Cannot send result of finished event");
            return;
        }

        scheduleResultCallbacks(event, result);
        executeDelayed();
    }

    // Handling event failure on main thread
    private static void handleEventFailure(Event event, EventFailure failure) {
        if (!ACTIVE_EVENTS.contains(event)) {
            Utils.logE(event.getKey(), "Cannot send failure callback of finished event");
            return;
        }

        scheduleFailureCallbacks(event, failure);
        executeDelayed();
    }

    // Handling finished event on main thread
    private static void handleTaskFinished(Task task) {
        ACTIVE_TASKS.remove(task);

        if (task.eventMethod.isSingleThread) {
            Utils.log(task, "Single-thread method is no longer in use");
            task.eventMethod.isInUse = false;
        }

        Event event = task.event;

        if (!ACTIVE_EVENTS.contains(event)) {
            Utils.logE(event.getKey(), "Cannot finish already finished event");
            return;
        }

        ((EventBase) event).handlersCount--;

        if (((EventBase) event).handlersCount == 0) {
            // No more running handlers
            ACTIVE_EVENTS.remove(event);
            scheduleStatusUpdates(event, EventStatus.FINISHED);
            executeDelayed();
        }
    }

    // Handling scheduled execution tasks on main thread
    private static void handleTasksExecution() {
        if (EXECUTION_QUEUE.isEmpty()) return; // Nothing to dispatch

        if (EventsParams.isDebug()) Log.d(Utils.TAG, "Dispatching: started");

        long started = SystemClock.uptimeMillis();

        for (Iterator<Task> iterator = EXECUTION_QUEUE.iterator(); iterator.hasNext(); ) {
            final Task task = iterator.next();

            if (task.eventTarget.isUnregistered) continue; // Target is unregistered

            if (task.eventMethod.isBackground) {
                if (task.eventMethod.isSingleThread) {
                    if (task.eventMethod.isInUse) {
                        Utils.log(task, "Single-thread method is already in use, waiting");
                        continue;
                    } else {
                        Utils.log(task, "Single-thread method is in use now");
                        task.eventMethod.isInUse = true;
                    }
                }

                Utils.log(task, "Executing in background");
                BACKGROUND_EXECUTOR.execute(task);
            } else {
                Utils.log(task, "Executing");

                task.run();
            }

            iterator.remove();

            // Checking that we are not spending to much time on main thread
            long time = SystemClock.uptimeMillis() - started;

            if (time > MAX_TIME_IN_MAIN_THREAD) {
                if (EventsParams.isDebug())
                    Log.d(Utils.TAG, "Dispatching: time in main thread "
                            + time + " ms > " + MAX_TIME_IN_MAIN_THREAD + " ms");
                executeDelayed();
                break;
            }
        }

        if (EventsParams.isDebug()) Log.d(Utils.TAG, "Dispatching: finished");
    }


    // Handler class to execute different operations on main thread
    private static class MainThreadHandler extends Handler {

        private static final long MESSAGE_DELAY = 10L;

        private static final int MSG_REGISTER = 0;
        private static final int MSG_UNREGISTER = 1;

        private static final int MSG_EXECUTE = 2;
        private static final int MSG_POST_EVENT = 3;
        private static final int MSG_POST_EVENT_RESULT = 4;
        private static final int MSG_POST_EVENT_FAILURE = 5;
        private static final int MSG_POST_TASK_FINISHED = 6;

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        void register(Object target) {
            sendMessage(obtainMessage(MSG_REGISTER, target)); // No delays for registration
        }

        void unregister(Object target) {
            sendMessage(obtainMessage(MSG_UNREGISTER, target)); // No delays for un-registration
        }

        void execute() {
            if (!hasMessages(MSG_EXECUTE)) sendEmptyMessageDelayed(MSG_EXECUTE, MESSAGE_DELAY);
        }

        void postEvent(Event event) {
            sendMessageDelayed(obtainMessage(MSG_POST_EVENT, event), MESSAGE_DELAY);
        }

        void postEventResult(Event event, EventResult result) {
            Object[] data = new Object[]{event, result};
            sendMessageDelayed(obtainMessage(MSG_POST_EVENT_RESULT, data), MESSAGE_DELAY);
        }

        void postEventFailure(Event event, EventFailure failure) {
            Object[] data = new Object[]{event, failure};
            sendMessageDelayed(obtainMessage(MSG_POST_EVENT_FAILURE, data), MESSAGE_DELAY);
        }

        void postTaskFinished(Task task) {
            sendMessageDelayed(obtainMessage(MSG_POST_TASK_FINISHED, task), MESSAGE_DELAY);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case MSG_REGISTER: {
                    handleRegistration(msg.obj);
                    break;
                }
                case MSG_UNREGISTER: {
                    handleUnRegistration(msg.obj);
                    break;
                }
                case MSG_EXECUTE: {
                    handleTasksExecution();
                    break;
                }
                case MSG_POST_EVENT: {
                    handleEventPost((Event) msg.obj);
                    break;
                }
                case MSG_POST_EVENT_RESULT: {
                    Object[] data = (Object[]) msg.obj;
                    handleEventResult((Event) data[0], (EventResult) data[1]);
                    break;
                }
                case MSG_POST_EVENT_FAILURE: {
                    Object[] data = (Object[]) msg.obj;
                    handleEventFailure((Event) data[0], (EventFailure) data[1]);
                    break;
                }
                case MSG_POST_TASK_FINISHED: {
                    handleTaskFinished((Task) msg.obj);
                    break;
                }
            }
        }
    }

}