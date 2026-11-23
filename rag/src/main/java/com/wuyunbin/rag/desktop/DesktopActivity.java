package com.wuyunbin.rag.desktop;

import com.wuyunbin.rag.dto.DocumentTaskStatus;
import com.wuyunbin.rag.persistence.task.DocumentTaskJpaRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("desktop")
final class DesktopActivity {
    private final DocumentTaskJpaRepository tasks;
    private int activeWrites;
    private int activeIndexWrites;
    private boolean closing;

    DesktopActivity(DocumentTaskJpaRepository tasks) {
        this.tasks = tasks;
    }

    synchronized boolean beginWrite() {
        if (closing) return false;
        activeWrites++;
        return true;
    }

    synchronized void endWrite() {
        activeWrites--;
    }

    synchronized boolean beginIndexWrite(DesktopSettingsStore settings) {
        if (closing || !settings.configured() || settings.needsRestart()) return false;
        activeIndexWrites++;
        return true;
    }

    synchronized void endIndexWrite() {
        activeIndexWrites--;
    }

    synchronized boolean indexWritesActive() {
        return activeIndexWrites > 0;
    }

    synchronized boolean active() {
        return activeWrites > 0 || activeIndexWrites > 0 || activeTasks();
    }

    boolean activeTasks() {
        return tasks.existsByStatusIn(List.of(DocumentTaskStatus.PENDING, DocumentTaskStatus.RUNNING));
    }

    synchronized boolean prepareShutdown() {
        if (closing || active()) return false;
        closing = true;
        return true;
    }

    synchronized void cancelShutdown() {
        closing = false;
    }
}
