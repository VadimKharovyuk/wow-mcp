// Точка входа страницы: каждый блок живёт в своём модуле и запускается независимо.
import { initStats } from './stats.js';
import { initDigest } from './digest.js';
import { initAnalyze } from './analyze.js';

initStats();
initDigest();
initAnalyze();
