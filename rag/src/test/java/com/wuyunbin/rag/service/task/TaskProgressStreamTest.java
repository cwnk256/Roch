package com.wuyunbin.rag.service.task;

import com.wuyunbin.rag.controller.DocumentTaskController;
import com.wuyunbin.rag.dto.DocumentTask;
import com.wuyunbin.rag.dto.DocumentTaskType;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskProgressStreamTest {
    @Test
    void streamsInitialAndSubsequentSnapshotsThenClosesOnFailure() throws Exception {
        var task = DocumentTask.create(DocumentTaskType.SUMMARY, List.of("doc"), "test");
        var publisher = new TaskProgressPublisher();
        var service = mock(DocumentTaskService.class);
        when(service.find(task.getTaskId())).thenReturn(Optional.of(task));
        var mvc = MockMvcBuilders.standaloneSetup(new DocumentTaskController(service, publisher)).build();

        String endpoint = "/api/document-tasks/" + task.getTaskId() + "/events";
        var stream = mvc.perform(get(endpoint)).andReturn();
        assertThat(stream.getResponse().getContentAsString()).contains("event:progress", "\"progress\":0");
        task.markRunning();
        publisher.publish(task);
        task.markFailed("test failure");
        task.setErrorCode("TASK_EXECUTION_ERROR");
        publisher.publish(task);
        var body = mvc.perform(asyncDispatch(stream)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).containsSubsequence("\"status\":\"PENDING\"", "\"status\":\"RUNNING\"", "\"status\":\"FAILED\"")
                .contains("\"errorCode\":\"TASK_EXECUTION_ERROR\"");
        var reconnect = mvc.perform(get(endpoint)).andReturn();
        mvc.perform(asyncDispatch(reconnect)).andExpect(status().isOk());
        mvc.perform(get("/api/document-tasks/missing/events")).andExpect(status().isNotFound());
    }
}
