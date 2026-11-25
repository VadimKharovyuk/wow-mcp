// Блок «Статистика»: карточки, графики убийств, опыта, добычи, карта убийств и таблица запусков.
import { $, css, num, sum, money, dur, getJson } from './common.js';

const charts = {};

function draw(id, cfg) {
  if (charts[id]) charts[id].destroy();
  charts[id] = new Chart($(id), cfg);   // Chart — глобальный объект Chart.js (подключён обычным скриптом до модулей)
}

function card(l, v, s) {
  return '<div class="card"><div class="l">' + l + '</div><div class="v">' + v + '</div><div class="s">' + (s || '') + '</div></div>';
}

function renderCards(sm) {
  const kb = num(sm.kills.bot), kp = num(sm.kills.player), hrs = Math.max(sm.hours, 1);
  const botH = sm.botSeconds / 3600;
  $('cards').innerHTML =
    card('Убито ботом', kb, botH > 0.05 ? (kb / botH).toFixed(1) + ' в час работы' : '') +
    card('Убито мной', kp, '') +
    card('Добыча (окон)', sum(sm.loot), 'бот ' + num(sm.loot.bot) + ', я ' + num(sm.loot.player)) +
    card('Пусто / не найден', sum(sm.lootMiss), Object.entries(sm.lootMiss || {}).map(([k, v]) => k + ': ' + v).join('; ')) +
    card('Смерти', sum(sm.deaths), 'бот ' + num(sm.deaths.bot) + ', я ' + num(sm.deaths.player)) +
    card('Опыт', sm.xpGained, (sm.xpGained / hrs).toFixed(0) + ' в час') +
    card('Деньги (итог)', money(sm.moneyNet), 'продажи: ' + money(sm.sells.money) + ' (' + sm.sells.items + ' предм.)') +
    card('Бот работал', dur(sm.botSeconds), '');
}

function renderTimeline(sm, tl) {
  const times = [...new Set(tl.map(r => r.t))].sort();
  const series = (kind, src) => times.map(t => num((tl.find(r => r.t === t && r.kind === kind && r.source === src) || {}).n));
  const lab = times.map(t => new Date(t).toLocaleString('ru', sm.hours <= 48
    ? { hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit' } : { day: '2-digit', month: '2-digit' }));
  draw('cKills', { type: 'bar', data: { labels: lab, datasets: [
    { label: 'бот', data: series('kill', 'bot'), backgroundColor: css('--bot') },
    { label: 'я', data: series('kill', 'player'), backgroundColor: css('--player') }] },
    options: { scales: { x: { stacked: true }, y: { stacked: true, beginAtZero: true } } } });
  draw('cLoot', { type: 'bar', data: { labels: lab, datasets: [
    { label: 'добыча бот', data: series('loot', 'bot'), backgroundColor: css('--bot') },
    { label: 'добыча я', data: series('loot', 'player'), backgroundColor: css('--player') },
    { label: 'смерти', data: times.map((t, i) => series('death', 'bot')[i] + series('death', 'player')[i]), backgroundColor: css('--bad') }] },
    options: { scales: { y: { beginAtZero: true } } } });
}

function renderXp(pr) {
  const p = pr.slice().reverse();   // снимки идут от новых к старым
  draw('cXp', { type: 'line', data: { labels: p.map(r => new Date(r.ts).toLocaleTimeString('ru', { hour: '2-digit', minute: '2-digit' })),
    datasets: [{ label: 'опыт на уровне ' + (p.length ? p[p.length - 1].level : ''), data: p.map(r => num(r.xp)), borderColor: css('--good'), pointRadius: 0, tension: 0.2 }] },
    options: { scales: { y: { beginAtZero: false } } } });
}

function renderSpots(spots) {
  const bubble = r => ({ x: num(r.cx), y: num(r.cy), r: 3 + Math.sqrt(num(r.kills)) * 2 });
  draw('cSpots', { type: 'bubble', data: { datasets: [
    { label: 'бот', data: spots.filter(r => num(r.bot) >= num(r.player)).map(bubble), backgroundColor: css('--bot') + '99' },
    { label: 'я', data: spots.filter(r => num(r.bot) < num(r.player)).map(bubble), backgroundColor: css('--player') + '99' }] },
    options: { scales: { x: { min: 0, max: 100 }, y: { min: 0, max: 100, reverse: true } } } });
}

function renderJobs(jobs) {
  $('jobs').innerHTML = '<tr><th>Начало</th><th>Задача</th><th>Длит.</th><th>Статус</th><th>Итог</th><th>Убито</th><th>Добыча</th><th>Пусто</th><th>Смерти</th></tr>' +
    jobs.map(j => '<tr><td>' + new Date(j.started_at).toLocaleString('ru') + '</td><td>' + j.name + '</td><td>' + dur(j.seconds) + '</td><td>' +
      (j.status || '') + '</td><td>' + (j.result || '') + '</td><td>' + j.kills + '</td><td>' + j.loot + '</td><td>' + j.loot_miss + '</td><td>' + j.deaths + '</td></tr>').join('');
}

async function load() {
  const h = $('hours').value;
  try {
    const [sm, tl, pr, jobs, spots] = await Promise.all(
      ['summary?hours=' + h, 'timeline?hours=' + h, 'progress?hours=' + h, 'jobs?limit=20', 'spots?hours=' + h].map(u => getJson('/api/' + u)));
    $('err').textContent = '';
    renderCards(sm); renderTimeline(sm, tl); renderXp(pr); renderSpots(spots); renderJobs(jobs);
  } catch (e) { $('err').textContent = 'Ошибка загрузки: ' + e.message; }
}

/** Запускает блок: первая загрузка, смена периода и обновление раз в 30 секунд. */
export function initStats() {
  $('hours').addEventListener('change', load);
  load();
  setInterval(load, 30000);
}
