// Runs the pending cases of an eval run one request at a time (each request runs one case), then reloads the report.
(function () {
    "use strict";
    const root = document.getElementById("evalProgress");
    if (!root) return;
    const pending = root.dataset.pending ? root.dataset.pending.split(",") : [];
    const total = Number(root.dataset.total);
    const status = document.getElementById("evalStatus");
    const done = document.getElementById("evalDone");
    const bar = document.getElementById("evalBar");
    let finished = total - pending.length;

    async function next() {
        if (pending.length === 0) { status.textContent = "Done. Loading the report…"; location.reload(); return; }
        const caseId = pending[0];
        status.textContent = "Running " + caseId + "…";
        const headers = { "Accept": "application/json" };
        headers[root.dataset.csrfHeader] = root.dataset.csrfToken;
        try {
            const response = await fetch(root.dataset.url + encodeURIComponent(caseId), { method: "POST", headers, credentials: "same-origin" });
            const data = await response.json().catch(() => null);
            if (!response.ok) throw new Error(data && data.detail ? data.detail : "HTTP " + response.status);
            pending.shift();
            finished++;
            done.textContent = String(finished);
            bar.style.width = Math.round(finished * 100 / total) + "%";
            next();
        } catch (e) {
            status.textContent = "Stopped at " + caseId + ": " + e.message + " Reload the page to retry.";
            bar.classList.add("bg-danger");
        }
    }
    next();
})();
