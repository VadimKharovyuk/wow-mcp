// Сводка журнала: текст, копирование JSON в буфер, скачивание JSON-файла (/api/digest, /api/digest/text).
import { $, getText } from './common.js';
import { periodMinutes, show, showError, setStatus } from './report-panel.js';

// Копирование в буфер: браузер может запретить navigator.clipboard (после запроса к серверу жест «устаревает», особенно в Safari) —
// тогда пробуем запасной способ через скрытое поле; текст всегда показан в окне, его можно выделить и скопировать вручную.
async function copyText(t) {
  try { await navigator.clipboard.writeText(t); return true; } catch (e) { /* пробуем запасной способ */ }
  try {
    const ta = document.createElement('textarea');
    ta.value = t; ta.style.position = 'fixed'; ta.style.opacity = '0'; document.body.appendChild(ta);
    ta.focus(); ta.select();
    const ok = document.execCommand('copy'); document.body.removeChild(ta); return ok;
  } catch (e) { return false; }
}

async function showDigestText() {
  try { setStatus('собираю…'); show(await getText('/api/digest/text?minutes=' + periodMinutes())); setStatus(''); }
  catch (e) { showError(e); }
}

async function copyDigestJson() {
  try {
    setStatus('собираю…');
    const t = await getText('/api/digest?minutes=' + periodMinutes());
    show(t);                                           // сначала показываем: если копирование не выйдет, текст можно взять из окна
    const kb = Math.round(t.length / 1024 * 10) / 10;
    setStatus(await copyText(t) ? 'JSON скопирован (' + kb + ' КБ)'
      : 'браузер не дал доступ к буферу: нажмите в окне ниже, затем Cmd+A и Cmd+C (' + kb + ' КБ)');
  } catch (e) { showError(e); }
}

export function initDigest() {
  $('bDigest').onclick = showDigestText;
  $('bCopy').onclick = copyDigestJson;
  $('bDownload').onclick = () => { location.href = '/api/digest?download=1&minutes=' + periodMinutes(); };
}
