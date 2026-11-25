// Общие помощники страницы: доступ к элементам, числа, форматирование, запросы.
export const $ = id => document.getElementById(id);
export const css = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
export const num = v => Number(v || 0);
export const sum = o => Object.values(o || {}).reduce((a, b) => a + num(b), 0);

/** Деньги из меди: «12з 34с». */
export function money(c) {
  c = num(c); const s = c < 0 ? '-' : ''; c = Math.abs(c);
  const g = Math.floor(c / 10000), sl = Math.floor(c / 100) % 100;
  return s + (g ? g + 'з ' : '') + sl + 'с';
}

/** Секунды → «2ч 5м» или «5м». */
export function dur(sec) {
  sec = num(sec); const h = Math.floor(sec / 3600), m = Math.floor(sec / 60) % 60;
  return h ? h + 'ч ' + m + 'м' : m + 'м';
}

/** Текст ответа сервера; при ошибке бросает Error с сообщением из JSON {error} или с самим текстом. */
export async function getText(url, opts) {
  const r = await fetch(url, opts), t = await r.text();
  if (!r.ok) { let m = t; try { m = JSON.parse(t).error || t; } catch (e) { /* не JSON */ } throw new Error(m); }
  return t;
}

export async function getJson(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(url + ': ' + r.status);
  return r.json();
}
