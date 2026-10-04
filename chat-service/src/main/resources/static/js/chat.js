// ColourCoats direct chat panel: talks to POST /api/chat with channel WEB_CHAT.
(function () {
    "use strict";
    const ID_KEY = "cc-chat-id";
    const LOG_KEY = "cc-chat-log";
    // Configured once in application.yaml (salesiq.opener) and injected by the page.
    const OPENER = window.CC_OPENER || "Hi there! What can I help you with today?";
    const SUGGESTIONS = ["Where can lime wash be used?", "Which brands do you work with?",
        "Where are your experience centres?", "I'd like a callback"];

    const panel = document.getElementById("chatPanel");
    const launcher = document.getElementById("chatLauncher");
    const body = document.getElementById("chatBody");
    const form = document.getElementById("chatForm");
    const input = document.getElementById("chatInput");
    const send = document.getElementById("chatSend");
    let busy = false;

    // Storage can be blocked (private mode, strict settings): the chat still works, just without memory.
    const store = {
        get(area, key) { try { return window[area].getItem(key); } catch (e) { return null; } },
        set(area, key, value) { try { window[area].setItem(key, value); } catch (e) { /* ignore */ } },
        remove(area, key) { try { window[area].removeItem(key); } catch (e) { /* ignore */ } }
    };
    let conversationId = store.get("localStorage", ID_KEY);
    let log = [];
    try { log = JSON.parse(store.get("sessionStorage", LOG_KEY) || "[]"); } catch (e) { log = []; }

    function save() { store.set("sessionStorage", LOG_KEY, JSON.stringify(log.slice(-60))); }

    function bubble(kind, text) {
        const div = document.createElement("div");
        div.className = "bubble " + kind;
        div.textContent = text; // never innerHTML: replies and input are untrusted text
        body.appendChild(div);
        body.scrollTop = body.scrollHeight;
        return div;
    }

    function notice(text) {
        const div = document.createElement("div");
        div.className = "chat-notice";
        div.textContent = text;
        body.appendChild(div);
        body.scrollTop = body.scrollHeight;
    }

    function suggestions() {
        const wrap = document.createElement("div");
        wrap.className = "chat-suggestions";
        SUGGESTIONS.forEach(function (s) {
            const b = document.createElement("button");
            b.type = "button";
            b.className = "btn btn-sm btn-outline-clay";
            b.textContent = s;
            b.addEventListener("click", function () { wrap.remove(); ask(s); });
            wrap.appendChild(b);
        });
        body.appendChild(wrap);
    }

    function render() {
        body.replaceChildren();
        bubble("bot", OPENER);
        log.forEach(function (m) {
            if (m.kind === "notice") notice(m.text); else bubble(m.kind, m.text);
        });
        if (log.length === 0) suggestions();
    }

    function typing(show) {
        const existing = document.getElementById("chatTyping");
        if (!show) { if (existing) existing.remove(); return; }
        if (existing) return;
        const div = document.createElement("div");
        div.id = "chatTyping";
        div.className = "bubble bot typing";
        div.setAttribute("aria-label", "Aira is typing");
        div.innerHTML = "<span></span><span></span><span></span>";
        body.appendChild(div);
        body.scrollTop = body.scrollHeight;
    }

    function setBusy(value) {
        busy = value;
        send.disabled = value;
        input.disabled = value;
        typing(value);
        if (!value) input.focus();
    }

    async function ask(text) {
        text = (text || "").trim();
        if (!text || busy) return;
        const chips = body.querySelector(".chat-suggestions");
        if (chips) chips.remove();
        bubble("me", text);
        log.push({kind: "me", text: text});
        save();
        input.value = "";
        autosize();
        setBusy(true);
        try {
            const res = await fetch("/api/chat", {
                method: "POST",
                headers: {"Content-Type": "application/json", "Accept": "application/json"},
                body: JSON.stringify({conversationId: conversationId, message: text, channel: "WEB_CHAT"})
            });
            if (!res.ok) throw new Error("HTTP " + res.status);
            const data = await res.json();
            if (data.conversationId && data.conversationId !== conversationId) {
                conversationId = data.conversationId;
                store.set("localStorage", ID_KEY, conversationId);
            }
            setBusy(false);
            bubble("bot", data.reply);
            log.push({kind: "bot", text: data.reply});
            if (data.handoff && !log.some(function (m) { return m.kind === "notice"; })) {
                const n = "A ColourCoats specialist has been notified and will follow up.";
                notice(n);
                log.push({kind: "notice", text: n});
            }
            save();
        } catch (e) {
            setBusy(false);
            bubble("error", "Sorry, I couldn't reach our assistant just now. Please try again in a moment, or use the live chat at the bottom right.");
        }
    }

    function open(prompt) {
        panel.classList.add("open");
        launcher.setAttribute("aria-expanded", "true");
        if (body.childElementCount === 0) render();
        if (prompt) ask(prompt); else input.focus();
    }

    function close() {
        panel.classList.remove("open");
        launcher.setAttribute("aria-expanded", "false");
        launcher.focus();
    }

    function autosize() {
        input.style.height = "auto";
        input.style.height = Math.min(input.scrollHeight, 120) + "px";
    }

    launcher.addEventListener("click", function () { panel.classList.contains("open") ? close() : open(); });
    document.getElementById("chatClose").addEventListener("click", close);
    document.getElementById("chatReset").addEventListener("click", function () {
        if (busy) return;
        conversationId = null;
        log = [];
        store.remove("localStorage", ID_KEY);
        store.remove("sessionStorage", LOG_KEY);
        render();
        input.focus();
    });
    document.querySelectorAll("[data-open-chat]").forEach(function (el) {
        el.addEventListener("click", function () { open(el.getAttribute("data-chat-prompt")); });
    });
    form.addEventListener("submit", function (e) { e.preventDefault(); ask(input.value); });
    input.addEventListener("input", autosize);
    input.addEventListener("keydown", function (e) {
        if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); ask(input.value); }
    });
    document.addEventListener("keydown", function (e) {
        if (e.key === "Escape" && panel.classList.contains("open")) close();
    });
})();
