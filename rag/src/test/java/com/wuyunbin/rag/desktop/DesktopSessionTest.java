package com.wuyunbin.rag.desktop;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DesktopSessionTest {
    private static final String TOKEN = "test-desktop-session-token-valid-length";
    private static final String HOST = "127.0.0.1:18099";

    @Test
    void acceptsLocalAuthorizedRequestsAndRejectsCrossOriginWrites() throws Exception {
        DesktopActivity activity = mock(DesktopActivity.class);
        when(activity.beginWrite()).thenReturn(true);
        var filter = new DesktopSessionFilter(TOKEN, activity, mock(DesktopSettingsStore.class));

        assertFalse(reachesHandler(filter, request("GET"), null, null));
        assertFalse(reachesHandler(filter, request("PUT"), TOKEN, "https://example.com"));
        assertTrue(reachesHandler(filter, request("GET"), TOKEN, null));
        assertTrue(reachesHandler(filter, request("PUT"), TOKEN, "http://" + HOST));
        verify(activity).beginWrite();
        verify(activity).endWrite();
    }

    @Test
    void rejectsNewWritesWhileShuttingDown() throws Exception {
        var filter = new DesktopSessionFilter(TOKEN, mock(DesktopActivity.class), mock(DesktopSettingsStore.class));
        assertFalse(reachesHandler(filter, request("PUT"), TOKEN, "http://" + HOST));
    }

    @Test
    void appliesTheIndexGuardToTheMappedImportPath() throws Exception {
        var activity = mock(DesktopActivity.class);
        var settings = mock(DesktopSettingsStore.class);
        when(activity.beginWrite()).thenReturn(true);
        var filter = new DesktopSessionFilter(TOKEN, activity, settings);
        var upload = request("POST");
        upload.setRequestURI("/api/documents;version=first/pdf");
        assertFalse(reachesHandler(filter, upload, TOKEN, "http://" + HOST));
        verify(activity).beginIndexWrite(settings);
    }

    private MockHttpServletRequest request(String method) {
        var request = new MockHttpServletRequest(method, "/api/documents");
        request.setLocalPort(18099);
        request.addHeader("Host", HOST);
        return request;
    }

    private boolean reachesHandler(DesktopSessionFilter filter, MockHttpServletRequest request, String session, String origin)
            throws Exception {
        if (session != null) request.setCookies(new Cookie("roch_desktop", session));
        if (origin != null) request.addHeader("Origin", origin);
        var called = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, resp) -> called.set(true));
        return called.get();
    }
}
