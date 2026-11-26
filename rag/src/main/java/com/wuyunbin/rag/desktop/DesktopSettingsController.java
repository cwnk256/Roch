package com.wuyunbin.rag.desktop;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.CONFLICT;

@RestController
@Profile("desktop")
@RequestMapping("/api/desktop")
final class DesktopSettingsController {
    private final DesktopSettingsStore store;
    private final DesktopIndexBinding index;
    private final DesktopActivity activity;
    private final LmStudioClient models;

    DesktopSettingsController(DesktopSettingsStore store, DesktopIndexBinding index, DesktopActivity activity,
                              LmStudioClient models) {
        this.store = store;
        this.index = index;
        this.activity = activity;
        this.models = models;
    }

    record State(DesktopSettings settings, DesktopSettings.Connection activeConnection, DesktopSettings generationSettings,
                 boolean restartRequired, boolean activeTasks, boolean embeddingLocked) {}
    record ModelAddress(String modelOrigin) {}
    record ModelList(List<LmStudioClient.Model> models) {}
    record ModelTest(String modelOrigin, String model, String type) {}

    @GetMapping("/settings")
    synchronized State state() {
        var saved = store.get();
        return new State(saved, store.activeConnection(), store.generationSettings(), store.needsRestart(),
                activity.activeTasks(), index.locked());
    }

    @PutMapping("/settings")
    synchronized State save(@RequestBody DesktopSettings settings) throws IOException {
        var candidate = settings.validated();
        boolean connectionChanged = !candidate.connection().equals(store.activeConnection());
        if (connectionChanged) checkIndexCanSwitch();
        var previous = store.get();
        if (!store.configured() || (connectionChanged && !candidate.connection().equals(previous.connection()))) {
            int actual = models.test(candidate.modelOrigin(), candidate.embeddingModel(), "embedding").dimension();
            if (actual != candidate.embeddingDimension()) {
                throw new IllegalArgumentException("嵌入维度与实际模型不一致，请测试模型后保存。");
            }
        }
        if (!candidate.model().isBlank() && (!candidate.model().equals(previous.model())
                || !candidate.modelOrigin().equals(previous.modelOrigin()))) {
            models.test(candidate.modelOrigin(), candidate.model(), "llm");
        }
        synchronized (activity) {
            if (connectionChanged) checkIndexCanSwitch();
            store.save(candidate);
        }
        return state();
    }

    private void checkIndexCanSwitch() {
        if (activity.indexWritesActive()) {
            throw new ResponseStatusException(CONFLICT, "正在导入或重建索引，请完成后再修改嵌入连接。");
        }
        if (index.locked()) {
            throw new ResponseStatusException(CONFLICT, "知识库已有索引，无法修改嵌入模型或模型服务地址。");
        }
    }

    @PostMapping("/settings/models")
    ModelList list(@RequestBody ModelAddress address) {
        return new ModelList(models.models(address.modelOrigin()));
    }

    @PostMapping("/settings/test-model")
    LmStudioClient.TestResult test(@RequestBody ModelTest request) {
        return models.test(request.modelOrigin(), request.model(), request.type());
    }

    @PostMapping("/shutdown")
    ResponseEntity<Void> shutdown(@RequestParam(defaultValue = "true") boolean restart) {
        if (!activity.prepareShutdown()) {
            throw new ResponseStatusException(CONFLICT, "有操作或报告任务正在运行，请完成后再重启。");
        }
        try {
            if (restart && store.needsRestart() && index.locked()) {
                throw new ResponseStatusException(CONFLICT, "已有新索引，重启前请恢复当前生效的嵌入配置。");
            }
            return ResponseEntity.noContent().build();
        } catch (RuntimeException exception) {
            activity.cancelShutdown();
            throw exception;
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception instanceof java.nio.file.InvalidPathException
                ? "报告输出路径无效。" : exception.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> unavailable(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("error", exception.getReason()));
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<Map<String, String>> writeFailed(IOException exception) {
        return ResponseEntity.unprocessableContent().body(Map.of("error", "无法写入报告目录或保存设置，请检查磁盘和目录权限。"));
    }
}
