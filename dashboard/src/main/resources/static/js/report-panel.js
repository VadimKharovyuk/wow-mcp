// Общая панель «Разбор журнала бота»: выбор периода, поле вывода и строка статуса. Сводка (digest.js) и разбор моделью (analyze.js)
// пользуются одной панелью, но не знают друг о друге.
import { $ } from './common.js';

/** Выбранный период в минутах. */
export const periodMinutes = () => $('dmin').value;

/** Показать текст в окне вывода. */
export const show = text => { $('dout').textContent = text; };

/** Показать сообщение об ошибке и очистить статус. */
export const showError = e => { setStatus(''); show('Ошибка: ' + e.message); };

export const setStatus = t => { $('dstatus').textContent = t || ''; };
