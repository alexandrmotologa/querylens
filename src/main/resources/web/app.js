// QueryLens Dashboard Client Application
document.addEventListener('DOMContentLoaded', () => {
    const statusText = document.getElementById('status-text');
    const liveIndicator = document.getElementById('live-indicator');
    const valQps = document.getElementById('val-qps');
    const valTotalQueries = document.getElementById('val-total-queries');
    const valUptime = document.getElementById('val-uptime');
    const valViolations = document.getElementById('val-violations');
    const badgeViolations = document.getElementById('badge-violations');
    const valP99 = document.getElementById('val-p99');
    const valP50 = document.getElementById('val-p50');
    const violationCountTag = document.getElementById('violation-count-tag');

    const violationsBody = document.getElementById('violations-body');
    const streamBody = document.getElementById('stream-body');
    const topQueriesBody = document.getElementById('top-queries-body');

    let streamRows = [];
    const MAX_STREAM_ROWS = 25;
    let knownViolations = new Set();

    // Setup Server-Sent Events (SSE)
    function connectSse() {
        const evtSource = new EventSource('/api/events');

        evtSource.onopen = () => {
            statusText.textContent = 'Live Connected';
            liveIndicator.className = 'status-indicator live';
        };

        evtSource.onerror = () => {
            statusText.textContent = 'Reconnecting...';
            liveIndicator.className = 'status-indicator';
        };

        evtSource.addEventListener('query', (e) => {
            try {
                const data = JSON.parse(e.data);
                handleQueryEvent(data);
            } catch (err) {
                console.error('Error parsing query event:', err);
            }
        });

        evtSource.addEventListener('anti_pattern', (e) => {
            try {
                const data = JSON.parse(e.data);
                handleViolationEvent(data);
            } catch (err) {
                console.error('Error parsing anti_pattern event:', err);
            }
        });
    }

    function handleQueryEvent(event) {
        const exec = event.execution;
        if (!exec) return;

        // Remove empty state if present
        const emptyRow = streamBody.querySelector('.empty-state-row');
        if (emptyRow) {
            emptyRow.remove();
        }

        const tr = document.createElement('tr');
        const qType = exec.fingerprint ? exec.fingerprint.queryType : 'QUERY';
        const badgeClass = getBadgeClass(qType);

        tr.innerHTML = `
            <td><span class="badge ${badgeClass}">${escapeHtml(qType)}</span></td>
            <td><strong>${Math.round(exec.durationMs)} ms</strong></td>
            <td>${exec.rowCount}</td>
            <td><span class="code-inline">${escapeHtml(truncate(exec.rawSql, 90))}</span></td>
        `;

        streamBody.insertBefore(tr, streamBody.firstChild);
        streamRows.push(tr);

        if (streamRows.length > MAX_STREAM_ROWS) {
            const old = streamRows.shift();
            if (old && old.parentNode) {
                old.parentNode.removeChild(old);
            }
        }
    }

    function handleViolationEvent(event) {
        const report = event.report;
        if (!report || knownViolations.has(report.id)) return;
        knownViolations.add(report.id);

        const emptyRow = violationsBody.querySelector('.empty-state-row');
        if (emptyRow) {
            emptyRow.remove();
        }

        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td><span class="badge badge-violation">${escapeHtml(report.title || report.type)}</span></td>
            <td><strong>${report.repetitionCount}x</strong></td>
            <td>${report.durationMs} ms</td>
            <td><span class="code-inline">${escapeHtml(truncate(report.parentQuery || 'None', 60))}</span></td>
            <td><span class="code-inline">${escapeHtml(truncate(report.offendingQuery, 60))}</span></td>
            <td class="highlight-recommendation">${escapeHtml(report.recommendation || 'Examine query plan')}</td>
        `;

        violationsBody.insertBefore(tr, violationsBody.firstChild);
        fetchStats();
    }

    async function fetchStats() {
        try {
            const res = await fetch('/api/stats');
            if (!res.ok) return;
            const stats = await res.json();

            valQps.textContent = Number(stats.queriesPerSecond || 0).toFixed(1);
            valTotalQueries.textContent = Number(stats.totalQueries || 0).toLocaleString();
            valUptime.textContent = `Uptime: ${stats.uptimeSeconds || 0}s`;

            const violations = Number(stats.totalViolations || 0);
            valViolations.textContent = violations.toLocaleString();
            badgeViolations.textContent = violations.toString();
            violationCountTag.textContent = `${violations} detected`;

            valP99.textContent = stats.globalP99Ms || 0;
            valP50.textContent = `P50: ${stats.globalP50Ms || 0} ms`;
        } catch (err) {
            console.debug('Failed to fetch stats:', err);
        }
    }

    async function fetchViolations() {
        try {
            const res = await fetch('/api/violations');
            if (!res.ok) return;
            const list = await res.json();
            if (!Array.isArray(list) || list.length === 0) return;

            const emptyRow = violationsBody.querySelector('.empty-state-row');
            if (emptyRow) {
                emptyRow.remove();
            }

            list.forEach(report => {
                if (!knownViolations.has(report.id)) {
                    knownViolations.add(report.id);
                    const tr = document.createElement('tr');
                    tr.innerHTML = `
                        <td><span class="badge badge-violation">${escapeHtml(report.title || report.type)}</span></td>
                        <td><strong>${report.repetitionCount}x</strong></td>
                        <td>${report.durationMs} ms</td>
                        <td><span class="code-inline">${escapeHtml(truncate(report.parentQuery || 'None', 60))}</span></td>
                        <td><span class="code-inline">${escapeHtml(truncate(report.offendingQuery, 60))}</span></td>
                        <td class="highlight-recommendation">${escapeHtml(report.recommendation || 'Examine query plan')}</td>
                    `;
                    violationsBody.appendChild(tr);
                }
            });
        } catch (err) {
            console.debug('Failed to fetch violations:', err);
        }
    }

    async function fetchTopQueries() {
        try {
            const res = await fetch('/api/queries');
            if (!res.ok) return;
            const list = await res.json();
            if (!Array.isArray(list) || list.length === 0) return;

            topQueriesBody.innerHTML = '';
            list.slice(0, 10).forEach(q => {
                const tr = document.createElement('tr');
                const badgeClass = getBadgeClass(q.type);
                tr.innerHTML = `
                    <td><span class="badge ${badgeClass}">${escapeHtml(q.type || 'SQL')}</span></td>
                    <td><strong>${Number(q.count).toLocaleString()}</strong></td>
                    <td>${q.p50Ms} ms</td>
                    <td><strong style="color: var(--accent-rose)">${q.p99Ms} ms</strong></td>
                    <td><span class="code-inline">${escapeHtml(truncate(q.sql, 80))}</span></td>
                `;
                topQueriesBody.appendChild(tr);
            });
        } catch (err) {
            console.debug('Failed to fetch top queries:', err);
        }
    }

    function getBadgeClass(type) {
        switch ((type || '').toUpperCase()) {
            case 'SELECT': return 'badge-select';
            case 'INSERT': return 'badge-insert';
            case 'UPDATE': return 'badge-update';
            case 'DELETE': return 'badge-delete';
            default: return 'badge-select';
        }
    }

    function truncate(str, max) {
        if (!str) return '';
        return str.length > max ? str.substring(0, max) + '...' : str;
    }

    function escapeHtml(text) {
        if (!text) return '';
        const map = {
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#039;'
        };
        return text.replace(/[&<>"']/g, m => map[m]);
    }

    // Initialize polling and SSE stream
    connectSse();
    fetchStats();
    fetchViolations();
    fetchTopQueries();

    setInterval(fetchStats, 2000);
    setInterval(fetchTopQueries, 4000);
});
