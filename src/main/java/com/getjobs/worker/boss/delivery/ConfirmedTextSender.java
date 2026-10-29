package com.getjobs.worker.boss.delivery;

import com.microsoft.playwright.*;
import org.json.JSONObject;

/** Click completion is not a send receipt. The browser caller must hold the Playwright serial executor. */
public final class ConfirmedTextSender {
    public enum Outcome { CONFIRMED, FAILED, UNKNOWN }
    private ConfirmedTextSender() {}
    public static Outcome send(Page page, Locator input, String text) {
        if (text == null || text.isBlank()) return Outcome.FAILED;
        boolean clicked = false;
        try {
            JSONObject before = readState(page, text);
            if (!before.optBoolean("rootFound")) return Outcome.FAILED;
            int oldCount = before.optInt("count");
            Locator send = page.locator("div.send-message, button[type='send'].btn-send, button.btn-send").first();
            if (send.count() == 0) return Outcome.FAILED;
            input.click();
            Object tag = input.evaluate("el => el.tagName.toLowerCase()");
            if ("textarea".equals(tag)) input.fill(text);
            else input.evaluate("(el, msg) => { el.innerText = msg; el.dispatchEvent(new Event('input')); }", text);
            // A click timeout can happen after dispatch. From here failures are conservatively unknown.
            clicked = true;
            send.click();
            int stable = 0;
            for (int i = 0; i < 32; i++) {
                JSONObject now = readState(page, text);
                if (now.optInt("count") > oldCount) {
                    if (now.optBoolean("failed")) return Outcome.FAILED;
                    stable = now.optBoolean("sending") ? 0 : stable + 1;
                    if (stable >= 4) return Outcome.CONFIRMED;
                } else stable = 0;
                page.waitForTimeout(250);
            }
            return Outcome.UNKNOWN;
        } catch (Exception error) {
            return clicked ? Outcome.UNKNOWN : Outcome.FAILED;
        }
    }
    static JSONObject readState(Page page, String text) {
        String script = """
            text => {
              const root = document.querySelector('.chat-message .im-list') || document.querySelector('.chat-record');
              if (!root) return JSON.stringify({rootFound:false,count:0});
              const matches = [...root.querySelectorAll('li.message-item.item-myself')].filter(el => {
                const body = el.querySelector('.message-content');
                return body && (body.innerText || body.textContent || '').trim() === text.trim();
              });
              const last = matches[matches.length-1];
              const classes = last ? [last,...last.querySelectorAll('*')].map(el=>typeof el.className==='string'?el.className:'').join(' ') : '';
              const status = last ? (last.querySelector('.message-status')?.textContent || '') : '';
              return JSON.stringify({rootFound:true,count:matches.length,
                sending:/sending|send-progress|发送中/i.test(classes+' '+status),
                failed:/send-fail|send-error|message-fail|发送失败|重新发送|点击重试/i.test(classes+' '+status)});
            }
            """;
        try {
            for (Frame frame : page.frames()) {
                if (frame.isDetached()) continue;
                try {
                    var state = new JSONObject(String.valueOf(frame.evaluate(script, text)));
                    if (state.optBoolean("rootFound")) return state;
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return new JSONObject().put("rootFound", false).put("count", 0);
    }
}
