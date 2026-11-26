// Блок «Советник»: правила, которые советник предлагает боту, и места, где бот упирался (/api/advisor). Только просмотр.
import { $, getJson, num } from './common.js';

const KIND = { avoid_point: 'обходить точку', avoid_spot: 'не охотиться в клетке', penalize_spot: 'понизить приоритет клетки' };

function directiveRow(d) {
  return '<tr class="' + (d.active ? '' : 'muted') + '"><td>' + d.created + '</td><td>' + (KIND[d.kind] || d.kind) + '</td><td>' + d.zone + '</td><td>' +
    num(d.x).toFixed(1) + ', ' + num(d.y).toFixed(1) + '</td><td>' + num(d.r) + '</td><td>' + d.until + '</td><td>' + (d.reason || '') + '</td><td>' +
    (d.active ? (d.status === 'applied' ? 'применено' : 'предложено') : 'истекло') + '</td></tr>';
}

async function load() {
  try {
    const r = await getJson('/api/advisor');
    $('adesc').textContent = r.active
      ? 'Действующих правил: ' + r.active + '. «Предложено» — бот правило не получал (режим show), «применено» — передано боту (режим apply в application.yml).'
      : 'Действующих правил нет (советник предлагает правила при повторяющихся смертях, зависших боях и упорах).';
    $('adirectives').innerHTML = r.directives.length
      ? '<tr><th>Создано</th><th>Правило</th><th>Зона</th><th>Где (x, y)</th><th>Радиус</th><th>До</th><th>Причина</th><th>Статус</th></tr>' + r.directives.map(directiveRow).join('')
      : '';
    $('astuck').innerHTML = r.stuckPoints.length
      ? '<tr><th>Зона</th><th>Где (x, y)</th><th>Упоров</th><th>Последний</th></tr>' +
        r.stuckPoints.map(p => '<tr><td>' + p.zone + '</td><td>' + num(p.x).toFixed(1) + ', ' + num(p.y).toFixed(1) + '</td><td>' + p.n + '</td><td>' + p.last + '</td></tr>').join('')
      : '';
  } catch (e) { $('adesc').textContent = 'Ошибка загрузки советника: ' + e.message; }
}

export function initAdvisor() {
  load();
  setInterval(load, 60000);
}
