import { spawn } from 'node:child_process'
import { fileURLToPath, URL } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const entry = fileURLToPath(new URL('./dev-worker.mjs', import.meta.url))
const args = ['--host', '127.0.0.1', ...process.argv.slice(2)]
const windows = process.platform === 'win32'
const child = spawn(
  windows ? 'powershell.exe' : process.execPath,
  windows
    ? [
        '-NoProfile',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        fileURLToPath(new URL('./dev-guard.ps1', import.meta.url)),
        '-NodePath',
        process.execPath,
        '-ParentId',
        String(process.pid),
      ]
    : ['--max-old-space-size=768', entry, ...args],
  {
    cwd: root,
    stdio: 'inherit',
    windowsHide: true,
    env: { ...process.env, DOCQUERY_DEV_ARGS: JSON.stringify(args) },
  },
)
child.on('error', (error) => {
  console.error('[DocQuery dev] Unable to start protected server:', error.message)
  process.exitCode = 1
})
child.on('exit', (code) => {
  process.exitCode = code ?? 1
})
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, () => {
    child.kill(signal)
  })
}
