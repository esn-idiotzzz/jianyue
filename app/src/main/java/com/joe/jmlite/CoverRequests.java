package com.joe.jmlite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bounded, newest-visible-first cover requests with one download per cover key. */
final class CoverRequests<T> implements AutoCloseable {
    interface Ticket { void cancel(); }
    interface Loader<T> { T load(Cancellation cancellation) throws Exception; }
    static final class Cancellation {
        private volatile boolean cancelled;
        private Runnable action;
        boolean isCancelled() { return cancelled; }
        void onCancel(Runnable next) {
            synchronized (this) { if (!cancelled) { action = next; return; } }
            next.run();
        }
        void cancel() {
            Runnable next;
            synchronized (this) { cancelled = true; next = action; action = null; }
            if (next != null) next.run();
        }
    }

    private final HashMap<String, Request> pending = new HashMap<>();
    private final ThreadPoolExecutor workers;
    private long nextSubscriber;

    CoverRequests(int parallelism, int queueSize) {
        LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<Runnable>(queueSize) {
            @Override public boolean offer(Runnable task) { return offerFirst(task); }
        };
        workers = new ThreadPoolExecutor(parallelism, parallelism, 20, TimeUnit.SECONDS,
                queue, runnable -> new Thread(runnable, "online-cover"), new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }

    Ticket load(String key, Loader<T> loader, Consumer<T> complete) {
        Request request;
        long subscriber;
        boolean start;
        synchronized (this) {
            request = pending.get(key);
            start = request == null;
            if (start) { request = new Request(key, loader); pending.put(key, request); }
            subscriber = ++nextSubscriber;
            request.subscribers.put(subscriber, complete);
        }
        final Request active = request;
        final long id = subscriber;
        if (start) {
            try { workers.execute(active); }
            catch (RejectedExecutionException ignored) { finish(active, null); }
        }
        return () -> {
            boolean stop;
            synchronized (CoverRequests.this) {
                active.subscribers.remove(id);
                stop = active.subscribers.isEmpty();
                if (stop) pending.remove(active.key, active);
            }
            if (stop) { workers.remove(active); active.cancellation.cancel(); }
        };
    }

    private void finish(Request request, T value) {
        ArrayList<Consumer<T>> listeners;
        synchronized (this) {
            pending.remove(request.key, request);
            listeners = new ArrayList<>(request.subscribers.values());
            request.subscribers.clear();
        }
        if (!request.cancellation.isCancelled()) for (Consumer<T> listener : listeners) listener.accept(value);
    }

    private final class Request implements Runnable {
        final String key;
        final Loader<T> loader;
        final Cancellation cancellation = new Cancellation();
        final LinkedHashMap<Long, Consumer<T>> subscribers = new LinkedHashMap<>();
        Request(String key, Loader<T> loader) { this.key = key; this.loader = loader; }
        @Override public void run() {
            if (cancellation.isCancelled()) return;
            T value = null;
            try { value = loader.load(cancellation); } catch (Exception ignored) { }
            finish(this, value);
        }
    }

    @Override public void close() {
        ArrayList<Request> active;
        synchronized (this) { active = new ArrayList<>(pending.values()); pending.clear(); }
        for (Request request : active) request.cancellation.cancel();
        workers.shutdownNow();
    }
}
