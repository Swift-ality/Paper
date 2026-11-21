// Swift - network bot tests for entity selectors.
//
// Starts a real Paper server from run-bots/swift-paper.jar (with ViaVersion + ViaBackwards so the raw
// minecraft-protocol bots, connecting as 1.21.11 clients, can join a 26.3 server), joins two offline bots over TCP,
// and has the op bot "Tester" type a scripted sequence of commands: summon uniquely named entities,
// change tags/data/scores, kill, summon more, and after each round evaluate a large set of selectors.
// Selector results are read back from what the server sends the bot:
//   counts : `execute if entity <sel>`      -> "Test passed, count: N" / "Test failed"
//   order  : `execute as <sel> run tellraw Tester {"selector":"@s"}` -> entity names, in execution order
// Every line the bot receives is written to transcript-<mode>.txt. Run the same script with the Swift
// speed-ups on and off (fresh identical worlds) and the transcripts must be identical.
//
// usage: node selector-bots.mjs <mode> [extra JVM flags...]
//   node selector-bots.mjs on
//   node selector-bots.mjs off -Dswift.selectorIndex=false -Dswift.selectorTagIndex=false ...

import mc from 'minecraft-protocol'
import nbt from 'prismarine-nbt'
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const RUN = path.resolve(HERE, '..', '..', 'run-bots')
const PORT = 25590
const MODE = process.argv[2] || 'on'
const JVM_FLAGS = process.argv.slice(3)
const sleep = ms => new Promise(r => setTimeout(r, ms))

// ---------------------------------------------------------------- server
function prepareServer () {
  fs.rmSync(path.join(RUN, 'world'), { recursive: true, force: true })
  fs.rmSync(path.join(RUN, 'world_nether'), { recursive: true, force: true })
  fs.rmSync(path.join(RUN, 'world_the_end'), { recursive: true, force: true })
  fs.writeFileSync(path.join(RUN, 'eula.txt'), 'eula=true\n')
  fs.writeFileSync(path.join(RUN, 'server.properties'), [
    'online-mode=false', `server-port=${PORT}`, 'level-type=minecraft\\:flat', 'level-seed=swift',
    // void biome: no animals spawn while chunks generate, so both runs create exactly the same entities
    // (same entity ids, so the same iteration order) and their transcripts can be compared line for line
    'generator-settings={"layers"\\:[{"block"\\:"minecraft\\:bedrock","height"\\:1},{"block"\\:"minecraft\\:stone","height"\\:3}],"biome"\\:"minecraft\\:the_void"}',
    'generate-structures=false', 'spawn-protection=0', 'view-distance=4', 'simulation-distance=4',
    'spawn-monsters=false', 'spawn-animals=false', 'max-players=4', 'enable-query=false',
    'enable-command-block=true', 'motd=swift-bot-tests', 'white-list=false', 'enforce-whitelist=false'
  ].join('\n') + '\n')
  // offline-mode UUIDs: name-based v3 of "OfflinePlayer:<name>"; computed by the server, so ops by name via console instead
}

function startServer () {
  return new Promise((resolve, reject) => {
    const args = ['-Xms2G', '-Xmx2G', ...JVM_FLAGS, '-Dcom.mojang.eula.agree=true', '-jar', 'swift-paper.jar', '--nogui']
    const proc = spawn('java', args, { cwd: RUN, stdio: ['pipe', 'pipe', 'pipe'] })
    const logFile = fs.createWriteStream(path.join(RUN, `server-${MODE}.log`))
    let done = false
    const onData = chunk => {
      logFile.write(chunk)
      if (!done && /Done \(/.test(chunk.toString())) {
        done = true
        resolve(proc)
      }
    }
    proc.stdout.on('data', onData)
    proc.stderr.on('data', onData)
    proc.on('exit', code => { if (!done) reject(new Error('server exited early: ' + code)) })
  })
}

const console_ = (proc, cmd) => proc.stdin.write(cmd + '\n')

// ---------------------------------------------------------------- bots
// Raw protocol clients (no movement at all): they confirm teleports, acknowledge chunk batches, send
// chat commands and read system chat. Connected as 1.21.11 through ViaBackwards.
function joinBot (name) {
  return new Promise((resolve, reject) => {
    const client = mc.createClient({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
    client.on('position', p => client.write('teleport_confirm', { teleportId: p.teleportId }))
    client.on('chunk_batch_finished', () => client.write('chunk_batch_received', { chunksPerTick: 20 }))
    client.once('position', () => setTimeout(() => resolve(client), 500))
    client.on('kick_disconnect', r => reject(new Error(`${name} kicked: ${JSON.stringify(r)}`)))
    client.on('disconnect', r => reject(new Error(`${name} disconnected: ${JSON.stringify(r)}`)))
    client.once('error', reject)
  })
}

/** Text of a chat component: literal text, translation key with flattened arguments, extras. Hover/click (UUIDs) ignored. */
function flatten (c) {
  if (c === null || c === undefined) return ''
  if (typeof c === 'string' || typeof c === 'number' || typeof c === 'boolean') return String(c)
  if (Array.isArray(c)) return c.map(flatten).join('')
  let out = ''
  if (c.text !== undefined) out += flatten(c.text)
  if (c[''] !== undefined) out += flatten(c[''])
  if (c.translate !== undefined) out += c.translate + '(' + (c.with || []).map(flatten).join(', ') + ')'
  if (c.extra) out += c.extra.map(flatten).join('')
  return out
}

const transcript = []
let collecting = null
let sentinelWaiter = null

function attach (client) {
  client.on('system_chat', pkt => {
    if (pkt.isActionBar) return
    const text = flatten(nbt.simplify(pkt.content))
    const m = /^#END (\d+)$/.exec(text)
    if (m) {
      if (sentinelWaiter && sentinelWaiter.id === Number(m[1])) {
        const w = sentinelWaiter
        sentinelWaiter = null
        w.resolve()
      }
      return
    }
    if (collecting) collecting.push(text)
  })
}

let seq = 0
async function send (bot, cmd) {
  bot.write('chat_command', { command: cmd })
  await sleep(40)
}

/** Runs commands, waits until the server has answered all of them, records everything it said. */
async function step (bot, label, cmds) {
  collecting = []
  for (const c of cmds) await send(bot, c)
  const id = ++seq
  const done = new Promise((resolve, reject) => {
    sentinelWaiter = { id, resolve }
    setTimeout(() => reject(new Error('timeout waiting for ' + label)), 20000)
  })
  bot.write('chat_command', { command: `tellraw ${bot.username} "#END ${id}"` })
  await done
  transcript.push(`## ${label}`)
  for (const line of collecting) transcript.push('  ' + line)
  collecting = null
}

// ---------------------------------------------------------------- scenario
const TYPES = ['pig', 'cow', 'zombie', 'armor_stand', 'villager', 'marker', 'sheep', 'item']
const ITEMS = ['stick', 'apple', 'bone', 'feather', 'flint', 'string', 'paper', 'clay_ball', 'brick', 'leather']
let next = 1

function summonCmd (i) {
  const type = TYPES[i % TYPES.length]
  const name = 'e' + String(i).padStart(3, '0')
  const tags = []
  if (i % 2 === 0) tags.push('t_a')
  if (i % 3 === 0) tags.push('t_b')
  if (i % 5 === 0) tags.push('t_c')
  const x = (i % 9) * 2 - 8
  const z = Math.floor(i / 9) % 9 * 2 - 8
  const tagNbt = `Tags:[${tags.map(t => `"${t}"`).join(',')}]`
  if (type === 'item') {
    const item = ITEMS[i % ITEMS.length]
    return `summon item ${x} -60 ${z} {Item:{id:"minecraft:${item}",count:${1 + i % 3}},PickupDelay:32767,Age:-32768,NoGravity:1b,CustomName:"${name}",${tagNbt}}`
  }
  if (type === 'marker') return `summon marker ${x} -60 ${z} {CustomName:"${name}",${tagNbt}}`
  if (type === 'armor_stand') return `summon armor_stand ${x} -60 ${z} {NoGravity:1b,CustomName:"${name}",${tagNbt}}`
  return `summon ${type} ${x} -60 ${z} {NoAI:1b,Silent:1b,PersistenceRequired:1b,CustomName:"${name}",${tagNbt}}`
}

const COUNT_SELECTORS = [
  '@e', '@e[type=pig]', '@e[type=zombie]', '@e[type=item]', '@e[type=marker]', '@e[type=villager]', '@e[type=player]',
  '@e[type=!pig]', '@e[type=!pig,type=!cow]', '@e[type=#minecraft:skeletons]', '@e[type=wither]',
  '@e[tag=t_a]', '@e[tag=t_b]', '@e[tag=t_c]', '@e[tag=t_a,tag=t_b]', '@e[tag=t_a,tag=!t_b]', '@e[tag=!t_a]', '@e[tag=]',
  '@e[tag=t_x]', '@e[type=pig,tag=t_a]', '@e[type=item,tag=t_b]', '@e[tag=t_c,type=!zombie]',
  '@e[tag=t_a,limit=3]', '@e[type=cow,limit=1]', '@e[tag=t_b,sort=nearest,limit=2]', '@e[tag=t_b,sort=furthest,limit=2]',
  '@e[name=e010]', '@e[name=!e010,type=pig]', '@e[type=item,name=e008]',
  '@e[nbt={NoAI:1b}]', '@e[nbt={Tags:["t_a"]}]', '@e[type=zombie,nbt={Health:20.0f}]', '@e[nbt={CustomName:"e016"}]',
  '@e[nbt=!{NoAI:1b}]', '@e[tag=t_a,nbt={Silent:1b}]', '@e[nbt={Silent:1b},tag=t_a]', '@e[type=item,nbt={Item:{id:"minecraft:apple"}}]',
  '@e[scores={s=5}]', '@e[scores={s=1..}]', '@e[tag=t_a,scores={s=..4}]',
  '@e[distance=..6]', '@e[distance=4..10,tag=t_a]', '@e[x=0,y=-60,z=0,dx=4,dy=2,dz=4]',
  '@e[team=red]', '@e[team=!red,type=cow]',
  '@a', '@a[tag=t_p]', '@a[name=Other]', '@a[nbt={SelectedItem:{id:"minecraft:diamond"}}]',
  '@a[nbt={Inventory:[{id:"minecraft:emerald"}]}]', '@a[nbt={EnderItems:[{id:"minecraft:gold_ingot"}]}]', '@p', '@s'
]

const ORDER_SELECTORS = [
  '@e[type=!player]', '@e[type=pig]', '@e[type=item]', '@e[tag=t_a]', '@e[tag=t_b,type=!item]', '@e[tag=t_c]',
  '@e[tag=t_a,limit=4]', '@e[type=zombie,limit=2]', '@e[tag=t_a,tag=!t_b,limit=5]', '@e[type=!pig,type=!player,limit=6]',
  '@e[tag=t_b,sort=nearest]', '@e[type=cow,sort=furthest]', '@e[scores={s=5}]', '@e[nbt={Silent:1b},tag=t_c]',
  '@e[tag=t_c,nbt={Silent:1b}]', '@e[name=!e004,type=marker]', '@a'
]

async function checkRound (bot, round) {
  const counts = COUNT_SELECTORS.map(s => `execute if entity ${s}`)
  for (let i = 0; i < counts.length; i += 12) {
    await step(bot, `round ${round} counts ${i}`, counts.slice(i, i + 12))
  }
  for (const s of ORDER_SELECTORS) {
    await step(bot, `round ${round} order ${s}`, [`execute as ${s} run tellraw ${bot.username} [{"text":"> "},{"selector":"@s"}]`])
  }
}

async function scenario (tester, other) {
  await step(tester, 'setup', [
    'gamerule minecraft:spawn_mobs false', 'gamerule minecraft:advance_time false', 'gamerule minecraft:advance_weather false',
    'time set midnight', 'weather clear', 'forceload add -32 -32 32 32', 'tp Tester 0 -60 0', 'tp Other 12 -60 12',
    'scoreboard objectives add s dummy', 'team add red', 'clear Tester', 'give Tester diamond', 'item replace entity Other enderchest.0 with gold_ingot',
    'give Other emerald', 'tag Other add t_p'
  ])
  // the void biome spawns nothing; record that, since the comparison relies on identical entity ids
  await sleep(1000)
  await step(tester, 'world is empty', ['execute if entity @e[type=!player]'])
  const first = []
  for (; next <= 64; next++) first.push(summonCmd(next))
  for (let i = 0; i < first.length; i += 16) await step(tester, `summon ${i}`, first.slice(i, i + 16))
  await step(tester, 'scores and teams', [
    'scoreboard players set @e[tag=t_a,limit=10] s 5', 'scoreboard players set @e[tag=t_c] s 2', 'team join red @e[type=cow,limit=3]'
  ])
  await checkRound(tester, 0)

  const mutations = [
    ['tag @e[tag=t_a,limit=4] remove t_a', 'tag @e[type=pig] add t_x', 'kill @e[name=e007]', 'kill @e[type=item,limit=1]'],
    ['data merge entity @e[name=e010,limit=1] {Tags:["t_x","t_c"]}', 'tag @e[tag=t_x,limit=2] remove t_x', 'tag @e[type=sheep] add t_b'],
    ['kill @e[tag=t_c,type=marker]', 'scoreboard players reset @e[scores={s=5},limit=3] s', 'tag @e[tag=t_b,sort=nearest,limit=3] add t_c'],
    ['data merge entity @e[name=e016,limit=1] {Silent:0b}', 'tag @e[tag=!t_a,type=!player,limit=5] add t_a', 'team leave @e[team=red,limit=1]']
  ]
  for (let r = 0; r < mutations.length; r++) {
    const more = []
    for (let k = 0; k < 6; k++, next++) more.push(summonCmd(next))
    await step(tester, `round ${r + 1} mutate`, [...mutations[r], ...more])
    await checkRound(tester, r + 1)
  }
}

// ---------------------------------------------------------------- main
prepareServer()
const t0 = Date.now()
const server = await startServer()
console.log(`[${MODE}] server up in ${((Date.now() - t0) / 1000).toFixed(1)}s, flags: ${JVM_FLAGS.join(' ') || '(none)'}`)
let exitCode = 0
try {
  const tester = await joinBot('Tester')
  attach(tester)
  const other = await joinBot('Other')
  console_(server, 'op Tester')
  await sleep(1500)
  await scenario(tester, other)
  const file = path.join(RUN, `transcript-${MODE}.txt`)
  fs.writeFileSync(file, transcript.join('\n') + '\n')
  console.log(`[${MODE}] ${transcript.length} transcript lines -> ${file}`)
  tester.end(); other.end()
} catch (e) {
  console.error(`[${MODE}] FAILED: ${e.message}`)
  exitCode = 1
} finally {
  console_(server, 'stop')
  await new Promise(r => server.on('exit', r))
}
process.exit(exitCode)
