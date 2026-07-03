'use strict';

const $ = (sel) => document.querySelector(sel);
const charts = {};

// ---------- helpers ----------
const fmtTime = (iso) => {
  const d = new Date(iso);
  return d.toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
};
const compact = (n) =>
  n == null ? '—' : new Intl.NumberFormat('pt-BR', { notation: 'compact', maximumFractionDigits: 2 }).format(n);
const num = (n, d = 4) => (n == null ? '—' : Number(n).toLocaleString('pt-BR', { maximumFractionDigits: d }));
const pct = (n) => (n == null ? '—' : (Number(n) * 100).toFixed(2) + '%');

const STATE_CLASS = {
  LATERAL: 'lateral', TENDENCIA_ALTA: 'alta', TENDENCIA_BAIXA: 'baixa',
  VOLATILIDADE_EXTREMA: 'extrema', INDEFINIDO: 'indef',
};
const stateBadge = (s) =>
  `<span class="badge ${STATE_CLASS[s] || 'indef'}">${s || 'INDEFINIDO'}</span>`;

function makeChart(id, config) {
  if (charts[id]) charts[id].destroy();
  charts[id] = new Chart($(id).getContext('2d'), config);
}

const GRID = { color: 'rgba(255,255,255,0.06)' };
const TICKS = { color: '#8b949e', maxTicksLimit: 10 };
const baseScales = () => ({
  x: { grid: GRID, ticks: TICKS },
  y: { grid: GRID, ticks: { color: '#8b949e' } },
});
const legend = { labels: { color: '#e6edf3', boxWidth: 12, font: { size: 11 } } };

// ---------- data loading ----------
async function loadPools() {
  const pools = await fetch('/api/pools').then((r) => r.json());
  const sel = $('#poolSelect');
  sel.innerHTML = '';
  if (!pools.length) {
    sel.innerHTML = '<option>(sem pools — rode o app Java)</option>';
    return;
  }
  for (const p of pools) {
    const o = document.createElement('option');
    o.value = p.address;
    o.textContent = p.symbol;
    sel.appendChild(o);
  }
}

async function loadAll() {
  const pool = $('#poolSelect').value;
  const tf = $('#tfSelect').value;
  const limit = $('#limitSelect').value;
  if (!pool || pool.startsWith('(')) return;

  $('#status').textContent = 'carregando…';
  try {
    const [metrics, signals, scanner, fundamentals] = await Promise.all([
      fetch(`/api/metrics?pool=${encodeURIComponent(pool)}&timeframe=${tf}&limit=${limit}`).then((r) => r.json()),
      fetch(`/api/signals?pool=${encodeURIComponent(pool)}&timeframe=${tf}&limit=${limit}`).then((r) => r.json()),
      fetch(`/api/scanner`).then((r) => r.json()),
      fetch(`/api/fundamentals`).then((r) => r.json()),
    ]);
    renderSummary(metrics);
    renderSignalCards(signals);
    renderPriceChart(metrics, signals);
    renderAtrChart(metrics);
    renderBandwidthChart(metrics);
    renderVolumeChart(metrics);
    renderRatioChart(metrics);
    renderScanner(scanner);
    renderSignalsTable(signals);
    renderFundamentals(fundamentals);
    $('#status').textContent = metrics.length
      ? `${metrics.length} candles · atualizado ${new Date().toLocaleTimeString('pt-BR')}`
      : 'sem dados para esta pool/timeframe ainda';
  } catch (e) {
    $('#status').textContent = 'erro ao carregar: ' + e.message;
  }
}

// ---------- renderers ----------
function renderSummary(rows) {
  const el = $('#summaryCards');
  if (!rows.length) { el.innerHTML = ''; return; }
  const last = rows[rows.length - 1];
  const cards = [
    ['Último preço', num(last.close)],
    ['Estado', stateBadge(last.market_state)],
    ['Squeeze', last.is_squeeze ? '<span class="dot yes">● SIM</span>' : '<span class="dot no">○ não</span>'],
    ['Bandwidth', last.bb_bandwidth == null ? '—' : pct(last.bb_bandwidth)],
    ['ATR(14)', num(last.atr_14)],
    ['Vol/TVL', last.volume_tvl_ratio == null ? '—' : num(last.volume_tvl_ratio, 4)],
    ['PoC (24h)', num(last.poc_price)],
    ['Macro 24h', last.macro_high_impact ? '<span class="macro-on">⚠ alto impacto</span>' : '<span class="macro-off">—</span>'],
  ];
  el.innerHTML = cards.map(([k, v]) => `<div class="card"><div class="k">${k}</div><div class="v">${v}</div></div>`).join('');
}

function renderPriceChart(rows, signals = []) {
  const labels = rows.map((r) => fmtTime(r.bucket_time));
  const line = (label, key, color, opts = {}) => ({
    label, data: rows.map((r) => r[key]), borderColor: color, backgroundColor: color,
    borderWidth: 1.5, pointRadius: 0, tension: 0.1, ...opts,
  });

  // Alinha os sinais aos candles por timestamp (só existem onde o predict rodou)
  const sigByTime = new Map(signals.map((s) => [new Date(s.signal_time).getTime(), s]));
  const at = (r) => sigByTime.get(new Date(r.bucket_time).getTime());
  const bandHigh = rows.map((r) => { const s = at(r); return s ? s.range_high : null; });
  const bandLow = rows.map((r) => { const s = at(r); return s ? s.range_low : null; });
  const entries = rows.map((r) => { const s = at(r); return s && s.enter ? r.close : null; });

  makeChart('#priceChart', {
    type: 'line',
    data: {
      labels,
      datasets: [
        line('Close', 'close', '#e6edf3', { borderWidth: 2 }),
        line('BB Upper', 'bb_upper', 'rgba(248,81,73,0.7)', { borderDash: [4, 3] }),
        line('BB Middle', 'bb_middle', 'rgba(210,153,34,0.8)'),
        line('BB Lower', 'bb_lower', 'rgba(63,185,80,0.7)', { borderDash: [4, 3] }),
        line('PoC (24h)', 'poc_price', 'rgba(163,113,247,0.9)', { borderDash: [2, 2] }),
        {
          label: 'Squeeze', data: rows.map((r) => (r.is_squeeze ? r.close : null)),
          borderColor: 'transparent', backgroundColor: '#d29922',
          showLine: false, pointRadius: 4, pointHoverRadius: 6,
        },
        // Banda sugerida pela IA (área sombreada entre high e low)
        {
          label: 'IA · banda ▲', data: bandHigh, borderColor: 'rgba(63,185,80,0.55)',
          borderWidth: 1, pointRadius: 0, borderDash: [3, 3], spanGaps: false, tension: 0,
        },
        {
          label: 'IA · banda ▼', data: bandLow, borderColor: 'rgba(63,185,80,0.55)',
          backgroundColor: 'rgba(63,185,80,0.10)', borderWidth: 1, pointRadius: 0,
          borderDash: [3, 3], fill: '-1', spanGaps: false, tension: 0,
        },
        {
          label: 'IA · entrada', data: entries, borderColor: '#3fb950',
          backgroundColor: '#3fb950', showLine: false, pointStyle: 'triangle',
          pointRadius: 6, pointHoverRadius: 8,
        },
      ],
    },
    options: { responsive: true, maintainAspectRatio: false, interaction: { intersect: false, mode: 'index' },
      plugins: { legend }, scales: baseScales() },
  });
}

function renderSignalCards(signals) {
  const el = $('#signalCards');
  if (!signals || !signals.length) {
    el.innerHTML = '<div class="card"><div class="k">Sinal</div><div class="v" style="font-size:14px;color:var(--muted)">sem sinais ainda — rode <code>ml/predict.py</code></div></div>';
    return;
  }
  const s = signals[signals.length - 1];
  const width = s.range_high_pct != null && s.range_low_pct != null
    ? ((s.range_high_pct - s.range_low_pct) * 100).toFixed(2) + '%' : '—';
  const action = s.enter
    ? '<span class="badge alta">● ENTRAR</span>'
    : '<span class="badge indef">○ aguardar</span>';
  const cards = [
    ['Ação', action],
    ['Confiança', s.confidence == null ? '—' : (Number(s.confidence) * 100).toFixed(1) + '%'],
    ['Preço no sinal', num(s.close)],
    ['Banda inferior', num(s.range_low)],
    ['Banda superior', num(s.range_high)],
    ['Largura da banda', width],
    ['Atualizado', fmtTime(s.signal_time)],
  ];
  el.innerHTML = cards.map(([k, v]) => `<div class="card"><div class="k">${k}</div><div class="v">${v}</div></div>`).join('');
}

function renderSignalsTable(signals) {
  const tb = $('#signalsTable tbody');
  if (!signals || !signals.length) {
    tb.innerHTML = '<tr><td colspan="7">sem sinais ainda — rode ml/predict.py</td></tr>';
    return;
  }
  const recent = signals.slice().reverse().slice(0, 30);   // mais recentes primeiro
  tb.innerHTML = recent.map((s) => {
    const width = s.range_high_pct != null && s.range_low_pct != null
      ? ((s.range_high_pct - s.range_low_pct) * 100).toFixed(2) + '%' : '—';
    const action = s.enter
      ? '<span class="badge alta">ENTRAR</span>'
      : '<span class="badge indef">aguardar</span>';
    return `
      <tr>
        <td>${fmtTime(s.signal_time)}</td>
        <td>${action}</td>
        <td>${s.confidence == null ? '—' : (Number(s.confidence) * 100).toFixed(1) + '%'}</td>
        <td>${num(s.close)}</td>
        <td>${num(s.range_low)}</td>
        <td>${num(s.range_high)}</td>
        <td>${width}</td>
      </tr>`;
  }).join('');
}

function renderAtrChart(rows) {
  makeChart('#atrChart', {
    type: 'line',
    data: { labels: rows.map((r) => fmtTime(r.bucket_time)),
      datasets: [{ label: 'ATR(14)', data: rows.map((r) => r.atr_14),
        borderColor: '#3b82f6', backgroundColor: 'rgba(59,130,246,0.15)', fill: true,
        borderWidth: 1.5, pointRadius: 0, tension: 0.15 }] },
    options: { responsive: true, maintainAspectRatio: false, plugins: { legend }, scales: baseScales() },
  });
}

function renderBandwidthChart(rows) {
  makeChart('#bwChart', {
    type: 'line',
    data: { labels: rows.map((r) => fmtTime(r.bucket_time)),
      datasets: [
        { label: 'Bandwidth', data: rows.map((r) => r.bb_bandwidth),
          borderColor: '#a371f7', backgroundColor: 'rgba(163,113,247,0.12)', fill: true,
          borderWidth: 1.5, pointRadius: 0, tension: 0.15 },
        { label: 'Squeeze', data: rows.map((r) => (r.is_squeeze ? r.bb_bandwidth : null)),
          borderColor: 'transparent', backgroundColor: '#d29922', showLine: false, pointRadius: 3 },
      ] },
    options: { responsive: true, maintainAspectRatio: false, plugins: { legend }, scales: baseScales() },
  });
}

function renderVolumeChart(rows) {
  makeChart('#volChart', {
    type: 'bar',
    data: { labels: rows.map((r) => fmtTime(r.bucket_time)),
      datasets: [{ label: 'Volume token1', data: rows.map((r) => r.volume_token1),
        backgroundColor: 'rgba(59,130,246,0.5)' }] },
    options: { responsive: true, maintainAspectRatio: false, plugins: { legend }, scales: baseScales() },
  });
}

function renderRatioChart(rows) {
  makeChart('#ratioChart', {
    type: 'line',
    data: { labels: rows.map((r) => fmtTime(r.bucket_time)),
      datasets: [{ label: 'Volume/TVL', data: rows.map((r) => r.volume_tvl_ratio),
        borderColor: '#3fb950', backgroundColor: 'rgba(63,185,80,0.12)', fill: true,
        borderWidth: 1.5, pointRadius: 0, tension: 0.15, spanGaps: true }] },
    options: { responsive: true, maintainAspectRatio: false, plugins: { legend }, scales: baseScales() },
  });
}

function renderScanner(rows) {
  const tb = $('#scannerTable tbody');
  if (!rows.length) { tb.innerHTML = '<tr><td colspan="10">sem dados</td></tr>'; return; }
  tb.innerHTML = rows.map((r) => `
    <tr>
      <td>${r.symbol}</td>
      <td>${r.timeframe}</td>
      <td>${stateBadge(r.market_state)}</td>
      <td>${r.is_squeeze ? '<span class="dot yes">●</span>' : '<span class="dot no">○</span>'}</td>
      <td>${r.bb_bandwidth == null ? '—' : pct(r.bb_bandwidth)}</td>
      <td>${num(r.atr_14)}</td>
      <td>${num(r.volume_tvl_ratio, 4)}</td>
      <td>${num(r.poc_price)}</td>
      <td>${r.macro_high_impact ? '<span class="macro-on">⚠</span>' : '<span class="macro-off">—</span>'}</td>
      <td>${fmtTime(r.bucket_time)}</td>
    </tr>`).join('');
}

function renderFundamentals(rows) {
  const tb = $('#fundTable tbody');
  if (!rows.length) { tb.innerHTML = '<tr><td colspan="5">sem dados</td></tr>'; return; }
  tb.innerHTML = rows.map((r) => `
    <tr>
      <td>${r.symbol}</td>
      <td>$${compact(r.tvl_usd)}</td>
      <td>$${compact(r.volume_24h_usd)}</td>
      <td>$${compact(r.fees_24h_usd)}</td>
      <td>${num(r.volume_tvl_ratio, 4)}</td>
    </tr>`).join('');
}

// ---------- init ----------
$('#refreshBtn').addEventListener('click', loadAll);
$('#poolSelect').addEventListener('change', loadAll);
$('#tfSelect').addEventListener('change', loadAll);
$('#limitSelect').addEventListener('change', loadAll);

(async function init() {
  await loadPools();
  await loadAll();
  setInterval(loadAll, 30000); // auto-refresh a cada 30s
})();
