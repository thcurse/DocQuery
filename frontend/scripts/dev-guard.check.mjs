import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath, URL } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'

const guard = fileURLToPath(new URL('./dev-guard.ps1', import.meta.url))
const enabled = process.platform === 'win32'
const scratch = fileURLToPath(
  new URL('../node_modules/.cache/docquery-guard-tests/', import.meta.url),
)
mkdirSync(scratch, { recursive: true })
function cleanup(directory) {
  assert.equal(dirname(directory), scratch.replace(/[\\/]$/, ''))
  rmSync(directory, { recursive: true, force: true })
}
function command(entry, parent = 0) {
  return [
    '-NoProfile',
    '-ExecutionPolicy',
    'Bypass',
    '-File',
    guard,
    '-NodePath',
    process.execPath,
    '-EntryPoint',
    entry,
    '-ProcessLimitMiB',
    '128',
    '-HeapMiB',
    '32',
    '-ParentId',
    String(parent),
  ]
}
function environment(args = []) {
  return { ...process.env, DOCQUERY_DEV_ARGS: JSON.stringify(args) }
}
function stopped(pid) {
  try {
    process.kill(pid, 0)
    return false
  } catch (error) {
    if (error.code === 'ESRCH') return true
    throw error
  }
}
async function waitStopped(pid) {
  for (let i = 0; i < 50; i++) {
    if (stopped(pid)) return
    await delay(100)
  }
  assert.fail(`Owned test child ${pid} survived guard shutdown`)
}

test('guard forwards arguments exactly and preserves exit status', { skip: !enabled }, () => {
  const directory = mkdtempSync(join(scratch, 'run-'))
  try {
    const entry = join(directory, 'space in filename.mjs')
    writeFileSync(entry, 'console.log(JSON.stringify(process.argv.slice(2)));process.exit(7)')
    const args = ['with space', 'quote"inside', 'C:\\trailing\\', '', '中文']
    const result = spawnSync('powershell.exe', command(entry), {
      env: environment(args),
      encoding: 'utf8',
      timeout: 15_000,
      windowsHide: true,
    })
    assert.equal(result.status, 7, result.stderr)
    assert.deepEqual(JSON.parse(result.stdout.trim()), args)
  } finally {
    cleanup(directory)
  }
})

test(
  'native allocation pressure stops the job and its descendants',
  { skip: !enabled },
  async () => {
    const directory = mkdtempSync(join(scratch, 'run-'))
    let descendant
    try {
      const entry = join(directory, 'pressure.mjs')
      const marker = join(directory, 'child.pid')
      writeFileSync(
        entry,
        `import {spawn} from 'node:child_process';import {writeFileSync} from 'node:fs';
      const child=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});
      writeFileSync(process.argv[2],String(child.pid));
      const buffers=[];setInterval(()=>buffers.push(Buffer.alloc(2*1024*1024,1)),25);`,
      )
      const result = spawnSync('powershell.exe', command(entry), {
        env: environment([marker]),
        encoding: 'utf8',
        timeout: 15_000,
        windowsHide: true,
      })
      descendant = Number(readFileSync(marker, 'utf8'))
      assert.equal(result.status, 137, result.stderr)
      assert.match(result.stderr, /process-memory-high/)
      await waitStopped(descendant)
    } finally {
      if (descendant && !stopped(descendant)) process.kill(descendant)
      cleanup(directory)
    }
  },
)

test(
  'OS limit rejects a single allocation that jumps over the polling threshold',
  { skip: !enabled },
  () => {
    const directory = mkdtempSync(join(scratch, 'run-'))
    try {
      const entry = join(directory, 'burst.mjs')
      writeFileSync(
        entry,
        'console.log("ALLOCATION_BEGIN");const buffer=Buffer.alloc(256*1024*1024,1);console.log("UNEXPECTED_SUCCESS",buffer.length)',
      )
      const result = spawnSync('powershell.exe', command(entry), {
        env: environment(),
        encoding: 'utf8',
        timeout: 15_000,
        windowsHide: true,
      })
      assert.match(result.stdout, /ALLOCATION_BEGIN/)
      assert.doesNotMatch(result.stdout, /UNEXPECTED_SUCCESS/)
      assert.notEqual(result.status, 0)
      assert.equal(result.error, undefined)
    } finally {
      cleanup(directory)
    }
  },
)

test('abrupt supervisor exit kills a running descendant tree', { skip: !enabled }, async () => {
  const directory = mkdtempSync(join(scratch, 'run-'))
  let supervisor, descendant
  try {
    const marker = join(directory, 'child.pid')
    const entry = join(directory, 'idle.mjs')
    writeFileSync(
      entry,
      `import {spawn} from 'node:child_process';import {writeFileSync} from 'node:fs';
      const child=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});
      writeFileSync(process.argv[2],String(child.pid));setInterval(()=>{},1000);`,
    )
    supervisor = spawn('powershell.exe', command(entry), {
      env: environment([marker]),
      stdio: 'ignore',
      windowsHide: true,
    })
    for (let i = 0; i < 100; i++) {
      try {
        descendant = Number(readFileSync(marker, 'utf8'))
        break
      } catch {
        await delay(100)
      }
    }
    assert.ok(descendant, 'worker did not start')
    supervisor.kill()
    await waitStopped(descendant)
  } finally {
    supervisor?.kill()
    if (descendant && !stopped(descendant)) process.kill(descendant)
    cleanup(directory)
  }
})

test(
  'parent launcher exit is detected even while the supervisor remains alive',
  { skip: !enabled },
  async () => {
    const directory = mkdtempSync(join(scratch, 'run-'))
    let parent, supervisor, descendant
    try {
      parent = spawn(
        process.execPath,
        ['--max-old-space-size=32', '-e', 'setInterval(()=>{},1000)'],
        { stdio: 'ignore', windowsHide: true },
      )
      const marker = join(directory, 'child.pid')
      const entry = join(directory, 'idle.mjs')
      writeFileSync(
        entry,
        `import {spawn} from 'node:child_process';import {writeFileSync} from 'node:fs';
      const child=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});
      writeFileSync(process.argv[2],String(child.pid));setInterval(()=>{},1000);`,
      )
      supervisor = spawn('powershell.exe', command(entry, parent.pid), {
        env: environment([marker]),
        stdio: 'ignore',
        windowsHide: true,
      })
      for (let i = 0; i < 100; i++) {
        try {
          descendant = Number(readFileSync(marker, 'utf8'))
          break
        } catch {
          await delay(100)
        }
      }
      assert.ok(descendant, 'worker did not start')
      parent.kill()
      await waitStopped(descendant)
      await waitStopped(supervisor.pid)
    } finally {
      parent?.kill()
      supervisor?.kill()
      if (descendant && !stopped(descendant)) process.kill(descendant)
      cleanup(directory)
    }
  },
)
