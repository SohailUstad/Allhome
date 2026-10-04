// Test chat on the prompt page: the real agent with this version's prompt. The conversation and the lead live only
// in this page (memory, not storage); the server stores nothing.
(function () {
    "use strict";
    const root = document.getElementById("testChat");
    if (!root) return;

    const url = root.dataset.url;
    const csrf = { header: root.dataset.csrfHeader, token: root.dataset.csrfToken };
    const log = document.getElementById("testLog");
    const form = document.getElementById("testForm");
    const input = document.getElementById("testInput");
    const send = document.getElementById("testSend");
    const channel = document.getElementById("testChannel");
    const reset = document.getElementById("testReset");
    const leadLine = document.getElementById("testLead");
    let history = [];
    let lead = null;
    let busy = false;

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined && text !== null) node.textContent = text; // never innerHTML
        return node;
    }

    function bubble(kind, text) {
        const div = el("div", "bubble " + kind);
        div.appendChild(el("div", "msg-text", text));
        log.appendChild(div);
        log.scrollTop = log.scrollHeight;
        return div;
    }

    function label(value) {
        return value ? value.toLowerCase().replace(/_/g, " ") : "unknown";
    }

    function showLead(reply) {
        const known = [];
        const l = reply.lead || {};
        for (const [field, name] of [["name", "name"], ["phone", "phone"], ["city", "city"], ["projectType", "project"],
            ["spaces", "spaces"], ["finishInterest", "finish"], ["timeline", "timeline"]]) {
            if (l[field]) known.push(name + ": " + l[field]);
        }
        leadLine.textContent = "Lead: " + label(reply.leadStatus) + " · persona " + label(l.persona) + " · intent "
            + label(l.intent) + (known.length ? " · " + known.join(", ") : "");
    }

    function restart() {
        history = [];
        lead = null;
        log.replaceChildren(el("div", "chat-notice", "New test conversation (" + channel.options[channel.selectedIndex].text + ")."));
        leadLine.textContent = "";
    }

    form.addEventListener("submit", async event => {
        event.preventDefault();
        const text = input.value.trim();
        if (busy || !text) return;
        busy = true;
        send.disabled = true;
        input.value = "";
        bubble("me", text);
        const typing = el("div", "bubble bot typing");
        typing.append(el("span"), el("span"), el("span"));
        log.appendChild(typing);
        log.scrollTop = log.scrollHeight;
        try {
            const headers = { "Content-Type": "application/json", "Accept": "application/json" };
            headers[csrf.header] = csrf.token;
            const response = await fetch(url, { method: "POST", headers, credentials: "same-origin",
                body: JSON.stringify({ message: text, channel: channel.value, history, lead }) });
            const data = await response.json().catch(() => null);
            if (!response.ok) throw new Error(data && data.detail ? data.detail : "The request failed (HTTP " + response.status + ").");
            typing.remove();
            const reply = bubble("bot", data.reply);
            const meta = el("div", "mt-1 small text-secondary");
            if (data.handoff) {
                meta.appendChild(el("span", "badge text-bg-danger me-1", "handoff"));
                if (data.handoffReason) meta.appendChild(el("span", null, data.handoffReason + " "));
            }
            if (data.sources && data.sources.length) meta.appendChild(el("span", null, data.sources.length + " sources"));
            if (meta.childNodes.length) reply.appendChild(meta);
            history.push({ role: "USER", content: text }, { role: "ASSISTANT", content: data.reply });
            lead = data.lead;
            showLead(data);
        } catch (e) {
            typing.remove();
            bubble("error", e.message);
        } finally {
            busy = false;
            send.disabled = false;
            input.focus();
        }
    });
    reset.addEventListener("click", restart);
    channel.addEventListener("change", restart);
    restart();
})();
