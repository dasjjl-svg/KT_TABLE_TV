import { spawn } from 'node:child_process';
import { access } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadSetup } from '../panel/server/config.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const children = new Set(), timers = new Set();
let stopping = false;
function start(name, executable, args) {
  let attempts = 0;
  function launch() {
    if (stopping) return;
    const began = Date.now(), child = spawn(executable, args, { cwd: root, stdio: 'inherit', env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' } });
    children.add(child);
    let ended = false;
    const retry = () => {
      if (ended) return; ended = true; children.delete(child);
      if (stopping) return;
      attempts = Date.now() - began > 60000 ? 0 : Math.min(attempts + 1, 5);
      const wait = Math.min(30000, 1000 * 2 ** attempts);
      console.error(`${name}: ${wait / 1000}초 후 다시 시작합니다.`);
      const timer = setTimeout(() => { timers.delete(timer); launch(); }, wait); timers.add(timer);
    };
    child.on('error', () => {
      if (!child.pid) retry();
      else console.error(`${name}: 프로세스 상태를 확인하지 못했습니다.`);
    });
    child.once('exit', retry);
  }
  launch();
}
function stop() {
  if (stopping) return; stopping = true;
  for (const timer of timers) clearTimeout(timer);
  for (const child of children) child.kill('SIGTERM');
  const timer = setTimeout(() => { for (const child of children) child.kill('SIGKILL'); }, 5000); timer.unref();
}
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, stop);
try {
  await loadSetup(join(root, 'runtime/setup.json'));
  await access(join(root, 'panel/dist/index.html'));
  start('Home Assistant 패널', process.execPath, ['panel/server/index.mjs']);
  start('CT1105-SB 화면', process.execPath, ['panel/server/legacy.mjs']);
  try { await access(join(root, 'runtime/panel-maintenance/config.json')); start('기기 관리 연결', 'python3', ['hardware/panel_maintenance_server.py', 'serve', '--lifetime', '604800']); } catch {}
  console.log('http://localhost:4173 · 종료하려면 Ctrl+C를 누르세요.');
} catch { console.error('먼저 Setup.command를 실행해 설정과 웹 빌드를 완료하세요.'); process.exitCode = 1; }
