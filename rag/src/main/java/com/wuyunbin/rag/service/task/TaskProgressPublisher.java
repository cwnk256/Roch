package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.dto.DocumentTask;
import com.wuyunbin.rag.dto.DocumentTaskResponse;
import com.wuyunbin.rag.dto.DocumentTaskStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Supplier;

@Component
public class TaskProgressPublisher {
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<Subscription>> listeners = new ConcurrentHashMap<>();

    public SseEmitter open(String taskId, Supplier<DocumentTaskResponse> snapshot) {
        var subscription = new Subscription(taskId);
        // Register before reading: a concurrent commit is either in the snapshot or in the stream.
        synchronized (subscription) {
            listeners.compute(taskId, (key, existing) -> {
                var group = existing == null ? new CopyOnWriteArraySet<Subscription>() : existing;
                group.add(subscription);
                return group;
            });
            try {
                subscription.send(snapshot.get());
            } catch (RuntimeException ex) {
                subscription.close();
                throw ex;
            }
        }
        return subscription.emitter;
    }

    public void publish(DocumentTask task) {
        var group = listeners.get(task.getTaskId());
        if (group != null) {
            var snapshot = DocumentTaskResponse.from(task);
            group.forEach(subscription -> subscription.send(snapshot));
        }
    }

    private final class Subscription {
        private final String taskId;
        private final SseEmitter emitter = new SseEmitter(120_000L);
        private boolean closed;
        private DocumentTaskResponse last;

        private Subscription(String taskId) {
            this.taskId = taskId;
            emitter.onCompletion(this::close);
            emitter.onTimeout(this::close);
            emitter.onError(error -> close());
        }

        private synchronized void send(DocumentTaskResponse update) {
            if (closed || (last != null && (update.progress() < last.progress() || last.equals(update)))) return;
            try {
                emitter.send(SseEmitter.event().name("progress").data(update));
                last = update;
                if (update.status() == DocumentTaskStatus.COMPLETED || update.status() == DocumentTaskStatus.FAILED) {
                    close();
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException disconnected) {
                close();
            }
        }

        private synchronized void close() {
            if (closed) return;
            closed = true;
            listeners.computeIfPresent(taskId, (key, group) -> {
                group.remove(this);
                return group.isEmpty() ? null : group;
            });
        }
    }
}
