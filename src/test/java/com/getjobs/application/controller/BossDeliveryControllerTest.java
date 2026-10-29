package com.getjobs.application.controller;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.boss.delivery.DeliveryConfiguration;
import com.getjobs.worker.boss.delivery.DeliveryTaskStore;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossDeliveryControllerTest {

    @Test
    void newDeliveryRejectsWhenAlreadyRunning() {
        var jobs = mock(BossJobService.class);
        var store = mock(DeliveryTaskStore.class);
        var configs = mock(ConfigService.class);
        var pw = mock(PlaywrightManager.class);
        when(pw.isLoggedIn("boss")).thenReturn(true);
        when(jobs.reserveDelivery(false)).thenThrow(new IllegalStateException("当前已有投递任务正在运行中"));

        var controller = new BossDeliveryController(jobs, store, configs, pw);
        var response = controller.startNewDelivery();

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertFalse((Boolean) response.getBody().get("success"));
        assertTrue(((String) response.getBody().get("message")).contains("运行中"));
        verify(jobs, never()).executeReservedDelivery(any(), any());
    }

    @Test
    void newDeliveryRejectsWhenUnknownCountPositive() {
        var jobs = mock(BossJobService.class);
        var store = mock(DeliveryTaskStore.class);
        var configs = mock(ConfigService.class);
        var pw = mock(PlaywrightManager.class);
        when(pw.isLoggedIn("boss")).thenReturn(true);
        when(jobs.reserveDelivery(false)).thenThrow(new IllegalArgumentException("存在未核实的发送状态，请先在进度卡片核对"));

        var controller = new BossDeliveryController(jobs, store, configs, pw);
        var response = controller.startNewDelivery();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse((Boolean) response.getBody().get("success"));
        assertTrue(((String) response.getBody().get("message")).contains("未核实"));
        verify(jobs, never()).executeReservedDelivery(any(), any());
    }

    @Test
    void newDeliverySucceedsAndReturnsTaskId() {
        var jobs = mock(BossJobService.class);
        var store = mock(DeliveryTaskStore.class);
        var configs = mock(ConfigService.class);
        var pw = mock(PlaywrightManager.class);
        when(pw.isLoggedIn("boss")).thenReturn(true);
        var reservation = new BossJobService.DeliveryReservation("new-task-uuid-123", false, new BossConfig());
        when(jobs.reserveDelivery(false)).thenReturn(reservation);

        var controller = new BossDeliveryController(jobs, store, configs, pw);
        var response = controller.startNewDelivery();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        assertEquals("new-task-uuid-123", response.getBody().get("taskId"));
    }

    @Test
    void resumeDeliveryRejectsWhenEmptyStore() {
        var jobs = mock(BossJobService.class);
        var store = mock(DeliveryTaskStore.class);
        var configs = mock(ConfigService.class);
        var pw = mock(PlaywrightManager.class);
        when(pw.isLoggedIn("boss")).thenReturn(true);
        when(jobs.reserveDelivery(true)).thenThrow(new IllegalArgumentException("当前没有可继续的旧任务，请新建任务"));

        var controller = new BossDeliveryController(jobs, store, configs, pw);
        var response = controller.resumeDelivery();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse((Boolean) response.getBody().get("success"));
        assertTrue(((String) response.getBody().get("message")).contains("当前没有可继续的旧任务"));
        verify(jobs, never()).executeReservedDelivery(any(), any());
    }

    @Test
    void resumeDeliveryRejectsWhenConfigChanged() {
        var jobs = mock(BossJobService.class);
        var store = mock(DeliveryTaskStore.class);
        var configs = mock(ConfigService.class);
        var pw = mock(PlaywrightManager.class);
        when(pw.isLoggedIn("boss")).thenReturn(true);
        when(jobs.reserveDelivery(true)).thenThrow(new IllegalStateException("配置已变更，已拒绝继续旧任务；如需使用新配置，请新建投递任务。"));

        var controller = new BossDeliveryController(jobs, store, configs, pw);
        var response = controller.resumeDelivery();

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertFalse((Boolean) response.getBody().get("success"));
        assertTrue(((String) response.getBody().get("message")).contains("配置已变更"));
        verify(jobs, never()).executeReservedDelivery(any(), any());
    }

    @Test
    void resumeDeliverySucceedsWhenConfigMatches() {
        var jobs = mock(BossJobService.class);
        var store = mock(DeliveryTaskStore.class);
        var configs = mock(ConfigService.class);
        var pw = mock(PlaywrightManager.class);
        when(pw.isLoggedIn("boss")).thenReturn(true);

        var config = new BossConfig();
        config.setCityCode(List.of("A"));
        config.setTargetCount(50);
        var reservation = new BossJobService.DeliveryReservation("t1", true, config);
        when(jobs.reserveDelivery(true)).thenReturn(reservation);

        var controller = new BossDeliveryController(jobs, store, configs, pw);
        var response = controller.resumeDelivery();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        assertEquals("t1", response.getBody().get("taskId"));
    }

    @Test
    void tasksHistoryReturnsStoreHistory() {
        var jobs = mock(BossJobService.class);
        var store = mock(DeliveryTaskStore.class);
        var configs = mock(ConfigService.class);
        var pw = mock(PlaywrightManager.class);
        when(store.history(5)).thenReturn(List.of(Map.of("taskId", "t1"), Map.of("taskId", "t2")));

        var controller = new BossDeliveryController(jobs, store, configs, pw);
        var response = controller.getTaskHistory(5);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) response.getBody();
        assertEquals(2, list.size());
    }
}
