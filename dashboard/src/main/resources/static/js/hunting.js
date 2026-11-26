// Блок «Лучшие места охоты»: опыт в час по клеткам карты за выбранный период (/api/hunting/best).
import { $, getJson, num } from './common.js';

const fmt = n => num(n).toLocaleString('ru');

function row(s, i) {
  const vs = num(s.vsAveragePct);
  const cls = vs > 0 ? 'good' : vs < 0 ? 'bad' : '';
  return '<tr' + (i === 0 ? ' class="top"' : '') + '><td>' + (i + 1) + '</td><td>' + s.zone + '</td><td>' + s.cx + ', ' + s.cy + '</td><td>' +
    fmt(s.xpPerHour) + '</td><td class="' + cls + '">' + (vs > 0 ? '+' : '') + vs + '%</td><td>' + s.killsPerHour + '</td><td>' + fmt(s.kills) +
    '</td><td>' + s.hours + '</td><td>' + s.deaths + '</td><td>' + s.stuck + '</td><td>' + fmt(s.score) + '</td><td>' + s.confidence + '</td></tr>';
}

async function load() {
  const h = $('hhours').value;
  try {
    const r = await getJson('/api/hunting/best?hours=' + h + '&limit=15');
    $('hsummary').textContent = r.windows
      ? 'Уровень ' + r.level + ': учтено ' + r.windows + ' окон по 10 минут (' + r.hours + ' ч), средний опыт ' + fmt(r.avgXpPerHour) + ' в час.'
      : 'Недостаточно данных за этот период: нужны окна по 10 минут, где работал только бот.';
    $('hspots').innerHTML = r.spots.length
      ? '<tr><th>#</th><th>Зона</th><th>Клетка (x, y)</th><th>Опыт/час</th><th>К среднему</th><th>Убийств/час</th><th>Убийств</th><th>Часов</th><th>Смерти</th><th>Упоры</th><th>Оценка</th><th>Надёжность</th></tr>'
        + r.spots.map(row).join('')
      : '';
    $('herr').textContent = '';
  } catch (e) { $('herr').textContent = 'Ошибка загрузки мест охоты: ' + e.message; }
}

export function initHunting() {
  $('hhours').addEventListener('change', load);
  load();
  setInterval(load, 60000);
}
