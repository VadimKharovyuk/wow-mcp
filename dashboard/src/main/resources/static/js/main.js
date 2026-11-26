// Точка входа страницы: каждый блок живёт в своём модуле и запускается независимо.
import { initStats } from './stats.js';
import { initDigest } from './digest.js';
import { initAnalyze } from './analyze.js';
import { initHunting } from './hunting.js';

initStats();
initDigest();
initAnalyze();
initHunting();
