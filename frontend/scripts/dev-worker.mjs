import { appendFileSync, existsSync, mkdirSync, renameSync, rmSync, statSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'
import { setInterval } from 'node:timers'

const directory = fileURLToPath(new URL('../node_modules/.cache/docquery-memory/', import.meta.url))
const log = `${directory}/${process.pid}-heap.jsonl`
let logging = true
function sample() {
  if (!logging) return
  try {
    mkdirSync(directory, { recursive: true })
    if (existsSync(log) && statSync(log).size > 256 * 1024) {
      rmSync(`${log}.previous`, { force: true })
      renameSync(log, `${log}.previous`)
    }
    // Only numeric memory counters: never include requests, environment or credentials.
    appendFileSync(
      log,
      `${JSON.stringify({ time: new Date().toISOString(), pid: process.pid, ...process.memoryUsage() })}\n`,
    )
  } catch {
    logging = false
    console.error(
      '[DocQuery dev] Memory log unavailable; process memory limits remain active on Windows.',
    )
  }
}
sample()
setInterval(sample, 10_000).unref()
await import('../node_modules/vite/bin/vite.js')
