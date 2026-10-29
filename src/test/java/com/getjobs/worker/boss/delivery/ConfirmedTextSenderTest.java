package com.getjobs.worker.boss.delivery;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConfirmedTextSenderTest {
    private final Page page=mock(Page.class);
    private final Frame frame=mock(Frame.class);
    private final Locator input=mock(Locator.class), send=mock(Locator.class);
    private void setup() {
        when(page.frames()).thenReturn(List.of(frame));
        when(page.locator(anyString())).thenReturn(send);when(send.first()).thenReturn(send);when(send.count()).thenReturn(1);
        when(input.evaluate(anyString())).thenReturn("textarea");
    }
    @Test void clickWithoutNewMessageIsUnknownNotSuccess() {
        setup();when(frame.evaluate(anyString(),any())).thenReturn("{\"rootFound\":true,\"count\":0}");
        assertEquals(ConfirmedTextSender.Outcome.UNKNOWN,ConfirmedTextSender.send(page,input,"您好"));
        verify(send).click();
    }
    @Test void confirmsOnlyNewStableOwnMessage() {
        setup();when(frame.evaluate(anyString(),any())).thenReturn("{\"rootFound\":true,\"count\":2}","{\"rootFound\":true,\"count\":3,\"sending\":true}","{\"rootFound\":true,\"count\":3}");
        assertEquals(ConfirmedTextSender.Outcome.CONFIRMED,ConfirmedTextSender.send(page,input,"您好"));
        verify(input).fill("您好");
    }
    @Test void failedBubbleIsFailure() {
        setup();when(frame.evaluate(anyString(),any())).thenReturn("{\"rootFound\":true,\"count\":0}","{\"rootFound\":true,\"count\":1,\"failed\":true}");
        assertEquals(ConfirmedTextSender.Outcome.FAILED,ConfirmedTextSender.send(page,input,"您好"));
    }
    @Test void missingReadableRootNeverClicks() {
        setup();when(frame.evaluate(anyString(),any())).thenReturn("{\"rootFound\":false,\"count\":0}");
        assertEquals(ConfirmedTextSender.Outcome.FAILED,ConfirmedTextSender.send(page,input,"您好"));verify(send,never()).click();
    }
}
