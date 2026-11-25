// Загружает модули бота (src/main/resources/bot/*.js по manifest.txt) в изолированный контекст без игры и браузера.
// Для тестов логики: состояние, клавиши, чат и т. п. подменяются заглушками в самих тестах.
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const BOT_DIR = process.env.WOW_BOT_DIR || path.join(__dirname, '..', 'src', 'main', 'resources', 'bot');

function loadBot() {
  const files = fs.readFileSync(path.join(BOT_DIR, 'manifest.txt'), 'utf8')
    .split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('#'));
  const win = {};
  win.window = win;
  win.console = { log() {}, warn() {}, error() {}, info() {}, debug() {} };
  win.setTimeout = setTimeout; win.clearTimeout = clearTimeout;
  win.setInterval = () => 0; win.clearInterval = () => {};
  win.Math = Math; win.Date = Date; win.JSON = JSON; win.Promise = Promise;
  win.document = { getElementById: () => ({ dispatchEvent() {} }), activeElement: null, addEventListener() {} };
  win.KeyboardEvent = class { constructor(t, o) { this.type = t; Object.assign(this, o); } };
  win.MouseEvent = class { constructor(t, o) { this.type = t; Object.assign(this, o); } };
  vm.createContext(win);
  const src = files.map(f => fs.readFileSync(path.join(BOT_DIR, f), 'utf8')).join(';\n');
  vm.runInContext(src, win, { filename: 'bot.js' });
  win.bot.__win = win;          // только для тестов: подмена глобальных функций (chat и т. п.)
  return win.bot;
}

module.exports = { loadBot };
