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
    const sparklinePath = document.getElementById('sparkline-path');

    const btnPauseStream = document.getElementById('btn-pause-stream');
    const pauseIcon = document.getElementById('pause-icon');
    const pauseLabel = document.getElementById('pause-label');
    const streamStatusTag = document.getElementById('stream-status-tag');

    const filterSearch = document.getElementById('filter-search');
    const filterType = document.getElementById('filter-type');
    const filterMinLatency = document.getElementById('filter-min-latency');

    const violationsBody = document.getElementById('violations-body');
    const streamBody = document.getElementById('stream-body');
    const topQueriesBody = document.getElementById('top-queries-body');
    const toastContainer = document.getElementById('toast-container');

    let streamRows = [];
    const MAX_STREAM_ROWS = 30;
    let knownViolations = new Set();
    let isStreamPaused = false;
    let pausedQueue = [];
    let qpsHistory = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0];

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
                if (isStreamPaused) {
                    pausedQueue.push(data);
                    if (pausedQueue.length > MAX_STREAM_ROWS) pausedQueue.shift();
                } else {
                    handleQueryEvent(data);
                }
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

    // Toggle Pause/Resume Stream
    btnPauseStream.addEventListener('click', () => {
        isStreamPaused = !isStreamPaused;
        if (isStreamPaused) {
            btnPauseStream.className = 'btn-control paused';
            pauseIcon.textContent = '▶';
            pauseLabel.textContent = 'Resume';
            streamStatusTag.textContent = 'Paused';
        } else {
            btnPauseStream.className = 'btn-control';
            pauseIcon.textContent = '⏸';
            pauseLabel.textContent = 'Pause';
            streamStatusTag.textContent = 'Streaming';

            // Flush buffered queries
            while (pausedQueue.length > 0) {
                handleQueryEvent(pausedQueue.shift());
            }
        }
    });

    // Filter listeners
    filterSearch.addEventListener('input', applyFilters);
    filterType.addEventListener('change', applyFilters);
    filterMinLatency.addEventListener('input', applyFilters);

    function applyFilters() {
        const search = filterSearch.value.trim().toLowerCase();
        const type = filterType.value.trim().toUpperCase();
        const minMs = parseFloat(filterMinLatency.value) || 0;

        const rows = streamBody.querySelectorAll('tr:not(.empty-state-row)');
        rows.forEach(row => {
            const rowType = row.dataset.type || '';
            const rowMs = parseFloat(row.dataset.ms) || 0;
            const rowSql = row.dataset.sql || '';

            const matchesSearch = !search || rowSql.toLowerCase().includes(search);
            const matchesType = !type || rowType === type;
            const matchesLatency = rowMs >= minMs;

            if (matchesSearch && matchesType && matchesLatency) {
                row.style.display = '';
            } else {
                row.style.display = 'none';
            }
        });
    }

    function handleQueryEvent(event) {
        const exec = event.execution;
        if (!exec) return;

        const emptyRow = streamBody.querySelector('.empty-state-row');
        if (emptyRow) {
            emptyRow.remove();
        }

        const tr = document.createElement('tr');
        const qType = exec.fingerprint ? exec.fingerprint.queryType : 'QUERY';
        const ms = Math.round(exec.durationMs);
        const badgeClass = getBadgeClass(qType);

        tr.dataset.type = qType;
        tr.dataset.ms = ms.toString();
        tr.dataset.sql = exec.rawSql;

        tr.innerHTML = `
            <td><span class="badge ${badgeClass}">${escapeHtml(qType)}</span></td>
            <td><strong>${ms} ms</strong></td>
            <td>${exec.rowCount}</td>
            <td><span class="code-inline">${escapeHtml(truncate(exec.rawSql, 85))}</span></td>
            <td>
                <div class="action-buttons">
                    <button class="btn-action" onclick="copyText('${escapeJs(exec.rawSql)}', 'Query copied')">Copy SQL</button>
                    <button class="btn-action" onclick="copyText('EXPLAIN (ANALYZE, BUFFERS) ${escapeJs(exec.rawSql)};', 'EXPLAIN copied')">EXPLAIN</button>
                </div>
            </td>
        `;

        streamBody.insertBefore(tr, streamBody.firstChild);
        streamRows.push(tr);

        if (streamRows.length > MAX_STREAM_ROWS) {
            const old = streamRows.shift();
            if (old && old.parentNode) {
                old.parentNode.removeChild(old);
            }
        }

        applyFilters();
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
        const srcBadge = report.sourceLocation ? `<span class="badge-source">📍 ${escapeHtml(report.sourceLocation)}</span>` : '<span style="color: var(--text-muted)">Unknown</span>';

        tr.innerHTML = `
            <td><span class="badge badge-violation">${escapeHtml(report.title || report.type)}</span></td>
            <td>${srcBadge}</td>
            <td><strong>${report.repetitionCount}x</strong></td>
            <td>${report.durationMs} ms</td>
            <td><span class="code-inline">${escapeHtml(truncate(report.parentQuery || 'None', 55))}</span></td>
            <td><span class="code-inline">${escapeHtml(truncate(report.offendingQuery, 55))}</span></td>
            <td class="highlight-recommendation">${escapeHtml(report.recommendation || 'Examine query plan')}</td>
            <td>
                <button class="btn-action" onclick="copyText('${escapeJs(report.recommendation)}', 'Recommendation copied')">Copy Fix</button>
            </td>
        `;

        violationsBody.insertBefore(tr, violationsBody.firstChild);
        fetchStats();
    }

    async function fetchStats() {
        try {
            const res = await fetch('/api/stats');
            if (!res.ok) return;
            const stats = await res.json();

            const qps = Number(stats.queriesPerSecond || 0);
            valQps.textContent = qps.toFixed(1);
            valTotalQueries.textContent = Number(stats.totalQueries || 0).toLocaleString();
            valUptime.textContent = `Uptime: ${stats.uptimeSeconds || 0}s`;

            const violations = Number(stats.totalViolations || 0);
            valViolations.textContent = violations.toLocaleString();
            badgeViolations.textContent = violations.toString();
            violationCountTag.textContent = `${violations} detected`;

            valP99.textContent = stats.globalP99Ms || 0;
            valP50.textContent = `P50: ${stats.globalP50Ms || 0} ms`;

            // Update sparkline
            updateSparkline(qps);
        } catch (err) {
            console.debug('Failed to fetch stats:', err);
        }
    }

    function updateSparkline(currentQps) {
        qpsHistory.shift();
        qpsHistory.push(currentQps);

        const max = Math.max(...qpsHistory, 5);
        const points = qpsHistory.map((v, i) => {
            const x = (i / (qpsHistory.length - 1)) * 200;
            const y = 28 - (v / max) * 24;
            return `${x.toFixed(1)},${y.toFixed(1)}`;
        });

        const d = `M0,30 L0,${(28 - (qpsHistory[0] / max) * 24).toFixed(1)} ` +
            points.map(p => `L${p}`).join(' ') +
            ` L200,30 Z`;

        sparklinePath.setAttribute('d', d);
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
                    const srcBadge = report.sourceLocation ? `<span class="badge-source">📍 ${escapeHtml(report.sourceLocation)}</span>` : '<span style="color: var(--text-muted)">Unknown</span>';

                    tr.innerHTML = `
                        <td><span class="badge badge-violation">${escapeHtml(report.title || report.type)}</span></td>
                        <td>${srcBadge}</td>
                        <td><strong>${report.repetitionCount}x</strong></td>
                        <td>${report.durationMs} ms</td>
                        <td><span class="code-inline">${escapeHtml(truncate(report.parentQuery || 'None', 55))}</span></td>
                        <td><span class="code-inline">${escapeHtml(truncate(report.offendingQuery, 55))}</span></td>
                        <td class="highlight-recommendation">${escapeHtml(report.recommendation || 'Examine query plan')}</td>
                        <td>
                            <button class="btn-action" onclick="copyText('${escapeJs(report.recommendation)}', 'Recommendation copied')">Copy Fix</button>
                        </td>
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
                    <td><span class="code-inline">${escapeHtml(truncate(q.sql, 75))}</span></td>
                    <td>
                        <button class="btn-action" onclick="copyText('${escapeJs(q.sql)}', 'Query template copied')">Copy</button>
                    </td>
                `;
                topQueriesBody.appendChild(tr);
            });
        } catch (err) {
            console.debug('Failed to fetch top queries:', err);
        }
    }

    window.copyText = function(text, toastMsg) {
        if (!navigator.clipboard) {
            const ta = document.createElement('textarea');
            ta.value = text;
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
            showToast(toastMsg || 'Copied to clipboard');
            return;
        }

        navigator.clipboard.writeText(text).then(() => {
            showToast(toastMsg || 'Copied to clipboard');
        }).catch(err => {
            console.error('Clipboard copy failed:', err);
        });
    };

    function showToast(message) {
        const toast = document.createElement('div');
        toast.className = 'toast';
        toast.textContent = message;
        toastContainer.appendChild(toast);
        setTimeout(() => {
            if (toast.parentNode) {
                toast.parentNode.removeChild(toast);
            }
        }, 2200);
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

    function escapeJs(text) {
        if (!text) return '';
        return text.replace(/\\/g, '\\\\')
                   .replace(/'/g, "\\'")
                   .replace(/"/g, '\\"')
                   .replace(/\n/g, '\\n')
                   .replace(/\r/g, '\\r');
    }

    // Initialize polling and SSE stream
    connectSse();
    fetchStats();
    fetchViolations();
    fetchTopQueries();

    setInterval(fetchStats, 2000);
    setInterval(fetchTopQueries, 4000);
});
