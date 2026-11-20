// Swift - server performance bench: a mob farm next to a player, MSPT compared across modes.
//
// For each mode: fresh flat world (fixed seed, no natural spawning), a glass pen of 45x45 blocks around
// spawn, 1400 mobs summoned in it (400 villagers, 300 cows, 300 sheep, 200 pigs, 200 chickens), one
// spectator bot in the middle so every mob is inside Paper's entity activation range. After a warm-up
// the server's own /mspt (1-minute average) is read, plus min/max over the window.
//
// usage: node perf-bench.mjs [mode ...]      modes: vanilla, swift, parallel (default: all three)
//   expects run-bench/swift-paper.jar and run-bench/plugins/ViaVersion.jar + ViaBackwards.jar
import mc from 'minecraft-protocol'
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const RUN = path.resolve(HERE, '..', '..', 'run-bench')
const PORT = 25571
const sleep = ms => new Promise(r => setTimeout(r, ms))
const OFF = ['-Dswift.selectorIndex=false', '-Dswift.selectorTagIndex=false', '-Dswift.nbtKeyFilter=false', '-Dswift.flatBrainLoop=false',
  '-Dswift.poiSearchBitset=false', '-Dswift.poiRetain=false', '-Dswift.poiEmptySearchCache=false', '-Dswift.spawnCostSkip=false']
const MODES = {
  vanilla: [...OFF, '-Dswift.asyncEntities=false'],
  swift: ['-Dswift.asyncEntities=false'],
  parallel: ['-Dswift.asyncEntities=true', '-Dswift.asyncTracker=true']
}
const WARMUP_S = Number(process.env.WARMUP_S || 45)
const MEASURE_S = 60
// SCENARIO=farm (default): 400 villagers + 1000 animals at noon; SCENARIO=zombies: 3000 zombies at midnight;
// SCENARIO=huge: 10000 zombies + 10000 villagers at midnight, 61x61 pen split by a glass wall (zombies see the
// villagers and keep trying to reach them, but cannot, so the count stays stable)
const SCENARIO = process.env.SCENARIO || 'farm'

function prepare () {
  for (const d of ['world', 'world_nether', 'world_the_end']) fs.rmSync(path.join(RUN, d), { recursive: true, force: true })
  fs.rmSync(path.join(RUN, 'config', 'swift.yml'), { force: true })
  fs.writeFileSync(path.join(RUN, 'eula.txt'), 'eula=true\n')
  fs.writeFileSync(path.join(RUN, 'server.properties'), [
    'online-mode=false', `server-port=${PORT}`, 'level-type=minecraft\\:flat', 'level-seed=swift-bench',
    'generator-settings={"layers"\\:[{"block"\\:"minecraft\\:bedrock","height"\\:1},{"block"\\:"minecraft\\:dirt","height"\\:2},{"block"\\:"minecraft\\:grass_block","height"\\:1}],"biome"\\:"minecraft\\:plains"}',
    'generate-structures=false', 'spawn-protection=0', 'view-distance=6', 'simulation-distance=6',
    'spawn-monsters=false', 'spawn-animals=false', 'max-players=4', 'white-list=false', 'motd=swift-bench'
  ].join('\n') + '\n')
}

function startServer (mode, flags) {
  return new Promise((resolve, reject) => {
    const heap = SCENARIO === 'huge' ? '10G' : '4G'
    const proc = spawn('java', [`-Xms${heap}`, `-Xmx${heap}`, ...flags, '-jar', 'swift-paper.jar', '--nogui'], { cwd: RUN, stdio: ['pipe', 'pipe', 'pipe'] })
    const log = fs.createWriteStream(path.join(RUN, `bench-${SCENARIO}-${mode}.log`))
    proc.lines = []
    let done = false
    let buf = ''
    const onData = chunk => {
      log.write(chunk)
      buf += chunk.toString()
      let i
      while ((i = buf.indexOf('\n')) >= 0) {
        // eslint-disable-next-line no-control-regex
        proc.lines.push(buf.slice(0, i).replace(/\x1b\[[0-9;]*m/g, ''))
        buf = buf.slice(i + 1)
      }
      if (!done && /Done \(/.test(chunk.toString())) { done = true; resolve(proc) }
    }
    proc.stdout.on('data', onData)
    proc.stderr.on('data', onData)
    proc.on('exit', code => { if (!done) reject(new Error('server exited early: ' + code)) })
  })
}

function joinBot (name) {
  return new Promise((resolve, reject) => {
    const client = mc.createClient({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
    client.on('position', p => client.write('teleport_confirm', { teleportId: p.teleportId }))
    client.on('chunk_batch_finished', () => client.write('chunk_batch_received', { chunksPerTick: 20 }))
    client.once('position', () => setTimeout(() => resolve(client), 500))
    client.on('kick_disconnect', r => reject(new Error(`${name} kicked: ${JSON.stringify(r)}`)))
  })
}

/**
 * Sends a console command and returns the console lines printed after it: once a line matches `until`
 * (waiting at most `maxMs`; console commands run once per tick, which takes seconds on an overloaded
 * server), or after `waitMs` when no `until` is given.
 */
async function command (proc, cmd, waitMs = 300, until = null, maxMs = 300_000) {
  const from = proc.lines.length
  proc.stdin.write(cmd + '\n')
  if (!until) {
    await sleep(waitMs)
    return proc.lines.slice(from)
  }
  const deadline = Date.now() + maxMs
  while (Date.now() < deadline) {
    if (proc.lines.slice(from).some(l => until.test(l))) {
      await sleep(200)
      return proc.lines.slice(from)
    }
    await sleep(100)
  }
  return proc.lines.slice(from)
}

const TRIPLE = /\d+\.\d+\/\d+\.\d+\/\d+\.\d+/
const COUNT = /Count: \d+|Test failed/
const SPARK_URL = /https:\/\/spark\.lucko\.me\/[A-Za-z0-9]+/

async function mspt (proc) {
  const out = await command(proc, 'mspt', 0, TRIPLE)
  // "◴ 1.2/0.9/3.4, 1.1/0.8/3.4, 1.2/0.8/5.0" = avg/min/max for 5 s, 10 s, 1 min
  const line = out.find(l => /\d+\.\d+\/\d+\.\d+\/\d+\.\d+/.test(l))
  if (!line) return null
  const triples = [...line.matchAll(/(\d+\.\d+)\/(\d+\.\d+)\/(\d+\.\d+)/g)].map(m => m.slice(1).map(Number))
  return { s5: triples[0], s10: triples[1], m1: triples[2] }
}

async function runMode (mode) {
  prepare()
  const proc = await startServer(mode, MODES[mode])
  const bot = await joinBot('Bench')
  await command(proc, 'op Bench')
  await command(proc, 'gamerule spawn_mobs false')
  await command(proc, 'gamerule advance_time false')
  await command(proc, SCENARIO === 'farm' ? 'time set noon' : 'time set midnight')
  await command(proc, 'gamemode spectator Bench')
  await command(proc, 'tp Bench 0 -50 0')
  const R = SCENARIO === 'huge' ? 30 : 22
  for (let y = -60; y <= -58; y++) {
    await command(proc, `fill ${-R - 1} ${y} ${-R - 1} ${R + 1} ${y} ${-R - 1} glass`, 50)
    await command(proc, `fill ${-R - 1} ${y} ${R + 1} ${R + 1} ${y} ${R + 1} glass`, 50)
    await command(proc, `fill ${-R - 1} ${y} ${-R - 1} ${-R - 1} ${y} ${R + 1} glass`, 50)
    await command(proc, `fill ${R + 1} ${y} ${-R - 1} ${R + 1} ${y} ${R + 1} glass`, 50)
  }
  if (SCENARIO === 'huge') {
    for (let y = -60; y <= -57; y++) await command(proc, `fill 0 ${y} ${-R} 0 ${y} ${R} glass`, 50)
  }
  const plan = SCENARIO === 'zombies' ? [['zombie', 3000]]
    : SCENARIO === 'huge' ? [['zombie', 10000], ['villager', 10000]]
    : [['villager', 400], ['cow', 300], ['sheep', 300], ['pig', 200], ['chicken', 200]]
  let n = 0
  for (const [type, count] of plan) {
    for (let i = 0; i < count; i++, n++) {
      let x = -R + (n * 7) % (2 * R + 1)
      const z = -R + Math.floor(n * 7 / (2 * R + 1)) * 3 % (2 * R + 1)
      if (SCENARIO === 'huge') x = (type === 'zombie' ? -1 : 1) * (1 + (i * 7) % R)
      proc.stdin.write(`summon minecraft:${type} ${x} -60 ${z} {PersistenceRequired:1b}\n`)
      if (n % 100 === 99) await sleep(SCENARIO === 'huge' ? 60 : 100)
    }
  }
  await sleep(1000)
  const entities = (await command(proc, 'execute if entity @e[type=!player]', 0, COUNT)).find(l => COUNT.test(l)) || ''
  console.log(`[${mode}] ${n} mobs summoned (${entities.trim()}); warming up ${WARMUP_S}s`)
  await sleep(WARMUP_S * 1000)
  // spark: every thread (main + Swift entity workers), sleeping threads ignored; stopped (and uploaded) after the window
  await command(proc, 'spark profiler start --thread * --ignore-sleeping', 0, /[Pp]rofiler|spark/)
  const samples = []
  const windowEnd = Date.now() + MEASURE_S * 1000
  while (Date.now() < windowEnd) {
    await sleep(10_000)
    const m = await mspt(proc)
    if (m) samples.push(m.s10[0])
  }
  const final = await mspt(proc)
  const sparkOut = await command(proc, 'spark profiler stop', 0, SPARK_URL, 600_000)
  const spark = (sparkOut.join(' ').match(SPARK_URL) || [null])[0]
  const tps = (await command(proc, 'tps', 0, /TPS from last/)).find(l => /TPS/.test(l)) || ''
  const alive = (await command(proc, 'execute if entity @e[type=!player]', 0, COUNT)).find(l => COUNT.test(l)) || ''
  bot.end()
  await command(proc, 'stop', 100)
  await new Promise(r => proc.on('exit', r))
  const result = { mode, avg1m: final?.m1[0], min1m: final?.m1[1], max1m: final?.m1[2], samples10s: samples, spark, tps: tps.replace(/^.*?TPS/, 'TPS').trim(), alive: alive.replace(/^.*INFO\]: /, '').trim() }
  console.log(`[${mode}] MSPT 1m avg ${result.avg1m} (min ${result.min1m}, max ${result.max1m}); 10s samples ${samples.join(', ')}; ${result.tps}; ${result.alive}; spark ${spark}`)
  return result
}

const modes = process.argv.slice(2).length ? process.argv.slice(2) : ['vanilla', 'swift', 'parallel']
const results = []
for (const mode of modes) results.push(await runMode(mode))
fs.writeFileSync(path.join(RUN, `bench-results-${SCENARIO}.json`), JSON.stringify(results, null, 2))
console.log('\nmode      MSPT(1m avg)  min   max')
for (const r of results) console.log(`${r.mode.padEnd(9)} ${String(r.avg1m).padStart(8)}  ${String(r.min1m).padStart(6)} ${String(r.max1m).padStart(6)}  ${r.spark}`)
process.exit(0)
