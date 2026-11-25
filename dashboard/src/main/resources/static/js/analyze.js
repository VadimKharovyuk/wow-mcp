// Разбор журнала моделью: выбор модели, запуск, показ последнего результата (/api/analyze, /api/analyzer, /api/analyze/last).
import { $, getText, getJson } from './common.js';
import { periodMinutes, show, showError, setStatus } from './report-panel.js';

async function runAnalyze() {
  const b = $('bAnalyze'); b.disabled = true; const t0 = Date.now();
  const tick = setInterval(() => setStatus('модель думает… ' + Math.round((Date.now() - t0) / 1000) + ' с'), 1000);
  try {
    const r = JSON.parse(await getText('/api/analyze?minutes=' + periodMinutes() + '&model=' + encodeURIComponent($('dmodel').value), { method: 'POST' }));
    show(r.text); setStatus(r.model + ', ' + Math.round(r.ms / 1000) + ' с');
  } catch (e) { showError(e); }
  finally { clearInterval(tick); b.disabled = false; }
}

async function loadModels() {
  try {
    const c = await getJson('/api/analyzer');
    $('dmodel').innerHTML = c.models.map(m => '<option' + (m === c.model ? ' selected' : '') + '>' + m + '</option>').join('');
  } catch (e) { /* список моделей необязателен: останется пустым */ }
}

async function loadLast() {
  try {
    const r = await getJson('/api/analyze/last');
    if (r.text) { show(r.text); setStatus('последний разбор: ' + r.model + ', ' + new Date(r.at).toLocaleTimeString('ru')); }
  } catch (e) { /* разбора ещё не было */ }
}

export function initAnalyze() {
  $('bAnalyze').onclick = runAnalyze;
  loadModels();
  loadLast();
}
