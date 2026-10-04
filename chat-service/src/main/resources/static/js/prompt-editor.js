// "Ask AI" panel of the prompt page: the operator describes a change, the chosen model proposes section edits,
// the operator accepts them into a draft, refines them or discards them. Everything the AI returns is shown as text.
(function () {
    "use strict";
    const root = document.getElementById("aiEditor");
    if (!root) return;

    const base = root.dataset.base;
    const versionId = root.dataset.version;
    const csrf = { header: root.dataset.csrfHeader, param: root.dataset.csrfParam, token: root.dataset.csrfToken };
    const form = document.getElementById("aiAskForm");
    const instruction = document.getElementById("aiInstruction");
    const section = document.getElementById("aiSection");
    const modelSelect = document.getElementById("aiModel");
    const effortWrap = document.getElementById("aiEffortWrap");
    const effortSelect = document.getElementById("aiEffort");
    const modelHint = document.getElementById("aiModelHint");
    const askButton = document.getElementById("aiAsk");
    const status = document.getElementById("aiStatus");
    const result = document.getElementById("aiResult");
    const recentWrap = document.getElementById("aiRecentWrap");
    const recentList = document.getElementById("aiRecent");
    let models = [];
    let busy = false;

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined && text !== null) node.textContent = text; // never innerHTML
        return node;
    }

    async function request(method, url, body) {
        const headers = { "Accept": "application/json" };
        if (body !== undefined) headers["Content-Type"] = "application/json";
        headers[csrf.header] = csrf.token;
        const response = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body),
            credentials: "same-origin" });
        const text = await response.text();
        let data = null;
        try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
        if (!response.ok) {
            const message = data && data.detail ? data.detail
                : response.status === 401 || response.status === 403 ? "Your session expired. Reload the page and sign in again."
                : "The request failed (HTTP " + response.status + ").";
            throw new Error(message);
        }
        return data;
    }

    // ---- Models ------------------------------------------------------------------------------------------------

    function selectedModel() { return models.find(m => m.model.id === modelSelect.value); }

    function showEffort() {
        const choice = selectedModel();
        effortSelect.replaceChildren();
        if (!choice) { effortWrap.hidden = true; modelHint.textContent = ""; return; }
        const model = choice.model;
        effortWrap.hidden = !model.reasoning;
        for (const effort of model.efforts) {
            const option = el("option", null, effort);
            option.value = effort;
            option.selected = effort === model.defaultEffort;
            effortSelect.appendChild(option);
        }
        modelHint.textContent = model.description || "";
    }

    async function loadModels() {
        try {
            models = await request("GET", base + "/ai-editor/models");
        } catch (e) {
            modelHint.textContent = e.message;
            return;
        }
        modelSelect.replaceChildren();
        for (const choice of models) {
            const option = el("option", null, choice.model.label + (choice.available === false ? " (not available for this key)" : ""));
            option.value = choice.model.id;
            option.disabled = choice.available === false;
            option.selected = choice.preselected && choice.available !== false;
            modelSelect.appendChild(option);
        }
        showEffort();
    }

    function modelSettings() {
        const choice = selectedModel();
        return { model: modelSelect.value, effort: choice && choice.model.reasoning ? effortSelect.value : null };
    }

    // ---- Waiting -----------------------------------------------------------------------------------------------

    function waiting(label) {
        busy = true;
        askButton.disabled = true;
        const started = Date.now();
        status.replaceChildren(el("span", "spinner-border spinner-border-sm me-2"), el("span", null, label));
        const counter = el("span", "text-secondary ms-1");
        status.appendChild(counter);
        const timer = setInterval(() => { counter.textContent = "(" + Math.round((Date.now() - started) / 1000) + " s)"; }, 1000);
        return () => { clearInterval(timer); busy = false; askButton.disabled = false; status.replaceChildren(); };
    }

    function failure(message) {
        result.replaceChildren(el("div", "alert alert-danger py-2 small mb-0", message));
    }

    // ---- Showing a proposal --------------------------------------------------------------------------------------

    function postForm(url, fields) {
        const f = el("form");
        f.method = "post";
        f.action = url;
        const token = el("input");
        token.type = "hidden"; token.name = csrf.param; token.value = csrf.token;
        f.appendChild(token);
        for (const [name, value] of Object.entries(fields || {})) {
            const input = el("input");
            input.type = "hidden"; input.name = name; input.value = value;
            f.appendChild(input);
        }
        document.body.appendChild(f);
        f.submit();
    }

    function diffBlock(lines) {
        const box = el("div", "prompt-diff");
        for (const line of lines) {
            const kind = line.kind === "ADDED" ? " added" : line.kind === "REMOVED" ? " removed" : "";
            const row = el("div", "diff-line" + kind);
            row.appendChild(el("span", "diff-mark", line.kind === "ADDED" ? "+" : line.kind === "REMOVED" ? "−" : " "));
            row.appendChild(el("span", null, line.text === "" ? " " : line.text));
            box.appendChild(row);
        }
        return box;
    }

    function meta(p) {
        const parts = [p.model + (p.effort ? " · " + p.effort + " reasoning" : "")];
        if (p.durationMs != null) parts.push(Math.round(p.durationMs / 100) / 10 + " s");
        if (p.promptTokens != null) {
            let tokens = p.promptTokens + " in / " + p.completionTokens + " out";
            if (p.reasoningTokens) tokens += " (" + p.reasoningTokens + " reasoning)";
            parts.push(tokens + " tokens");
        }
        return el("div", "small text-secondary mt-2", parts.join(" · "));
    }

    function refineBox(p, label, placeholder) {
        const wrap = el("div", "mt-3");
        const id = "aiRefine-" + p.id;
        const caption = el("label", "form-label small text-secondary mb-1", label);
        caption.htmlFor = id;
        const area = el("textarea", "form-control form-control-sm");
        area.id = id; area.rows = 2; area.maxLength = 4000; area.placeholder = placeholder;
        const button = el("button", "btn btn-outline-clay btn-sm mt-2", "Send");
        button.type = "button";
        button.addEventListener("click", async () => {
            if (busy || !area.value.trim()) return;
            const done = waiting("Asking " + modelSelect.options[modelSelect.selectedIndex].text + " again…");
            try {
                show(await request("POST", base + "/ai-edits/" + p.id + "/refine", Object.assign({ feedback: area.value }, modelSettings())));
                loadRecent();
            } catch (e) { failure(e.message); } finally { done(); }
        });
        wrap.append(caption, area, button);
        return wrap;
    }

    function show(p) {
        result.replaceChildren();
        const card = el("div", "border rounded-3 p-3 bg-white");
        const head = el("div", "d-flex flex-wrap align-items-center gap-2 mb-2");
        const labels = { PROPOSED: ["Proposal", "text-bg-primary"], QUESTION: ["Question", "text-bg-info"],
            REFUSED: ["Not a prompt change", "text-bg-secondary"], FAILED: ["Failed", "text-bg-danger"],
            ACCEPTED: ["Accepted", "text-bg-success"], REFINED: ["Refined", "text-bg-light border"],
            DISCARDED: ["Discarded", "text-bg-light border"] };
        const [text, badge] = labels[p.status] || [p.status, "text-bg-secondary"];
        head.appendChild(el("span", "badge " + badge, text));
        card.appendChild(head);
        if (p.summary) card.appendChild(el("p", "mb-2", p.summary));
        if (p.question) card.appendChild(el("p", "mb-2 fw-medium", p.question));
        if (p.refusal) card.appendChild(el("p", "mb-2", p.refusal));
        if (p.error) card.appendChild(el("div", "alert alert-danger py-2 small", p.error));
        if (p.outdated && p.status === "PROPOSED") {
            card.appendChild(el("div", "alert alert-warning py-2 small", "The prompt changed after this proposal; ask again to use it."));
        }

        for (const change of p.changes || []) {
            const block = el("div", "mt-3");
            const title = el("div", "d-flex flex-wrap align-items-center gap-2 mb-1");
            title.appendChild(el("strong", null, change.title));
            if (change.protectedSection) title.appendChild(el("span", "badge text-bg-warning", "Protected"));
            block.appendChild(title);
            if (change.reason) block.appendChild(el("div", "small text-secondary mb-1", change.reason));
            block.appendChild(diffBlock(change.diff));
            card.appendChild(block);
        }
        if (p.conflicts && p.conflicts.length) {
            const box = el("div", "alert alert-warning py-2 small mt-3 mb-0");
            box.appendChild(el("div", "fw-semibold mb-1", "Check these"));
            const list = el("ul", "mb-0 ps-3");
            for (const conflict of p.conflicts) list.appendChild(el("li", null, conflict));
            box.appendChild(list);
            card.appendChild(box);
        }
        card.appendChild(meta(p));

        const open = p.status === "PROPOSED" || p.status === "QUESTION" || p.status === "REFUSED";
        if (p.status === "PROPOSED" && !p.outdated) {
            const actions = el("div", "d-flex flex-wrap align-items-center gap-2 mt-3");
            let confirm = null;
            if (p.needsConfirmation) {
                const check = el("div", "form-check w-100");
                confirm = el("input", "form-check-input");
                confirm.type = "checkbox"; confirm.id = "aiConfirm-" + p.id;
                const label = el("label", "form-check-label small", "I understand this changes a protected section.");
                label.htmlFor = confirm.id;
                check.append(confirm, label);
                actions.appendChild(check);
            }
            const accept = el("button", "btn btn-clay btn-sm", "Accept into draft");
            accept.type = "button";
            accept.addEventListener("click", () => {
                if (confirm && !confirm.checked) { confirm.classList.add("is-invalid"); return; }
                postForm(base + "/ai-edits/" + p.id + "/accept", confirm ? { confirmProtected: "true" } : {});
            });
            actions.appendChild(accept);
            card.appendChild(actions);
        }
        if (open) {
            if (p.status === "QUESTION") card.appendChild(refineBox(p, "Your answer", "Answer the question…"));
            else card.appendChild(refineBox(p, "Refine", "e.g. Shorter, and only for Instagram."));
            const discard = el("button", "btn btn-link btn-sm text-secondary px-0 mt-2", "Discard this proposal");
            discard.type = "button";
            discard.addEventListener("click", () => postForm(base + "/ai-edits/" + p.id + "/discard"));
            card.appendChild(discard);
        }
        result.appendChild(card);
    }

    // ---- Earlier requests ----------------------------------------------------------------------------------------

    async function loadRecent() {
        let items;
        try { items = await request("GET", base + "/" + versionId + "/ai-edits"); } catch (e) { return; }
        recentList.replaceChildren();
        recentWrap.hidden = items.length === 0;
        for (const item of items) {
            const li = el("li", "mb-1");
            const link = el("a", null, (item.refinement ? "↳ " : "") + item.instruction);
            link.href = "#";
            link.addEventListener("click", async event => {
                event.preventDefault();
                try { show(await request("GET", base + "/ai-edits/" + item.id)); } catch (e) { failure(e.message); }
            });
            li.append(link, el("span", "text-secondary ms-2",
                item.status.toLowerCase() + " · " + item.model + (item.effort ? " (" + item.effort + ")" : "")));
            recentList.appendChild(li);
        }
    }

    // ---- Asking ----------------------------------------------------------------------------------------------------

    form.addEventListener("submit", async event => {
        event.preventDefault();
        if (busy || !instruction.value.trim()) return;
        const done = waiting("Asking " + modelSelect.options[modelSelect.selectedIndex].text + "… reasoning models can take a minute or more.");
        try {
            show(await request("POST", base + "/" + versionId + "/ai-edits",
                Object.assign({ instruction: instruction.value, section: section.value }, modelSettings())));
            loadRecent();
        } catch (e) {
            failure(e.message);
        } finally {
            done();
        }
    });
    modelSelect.addEventListener("change", showEffort);

    loadModels();
    loadRecent();
})();
