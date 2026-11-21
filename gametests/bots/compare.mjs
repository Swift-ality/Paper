// Swift - run the selector bot scenario with every Swift speed-up on, then with all off, on fresh
// identical worlds, and require identical transcripts. Exit code 0 = identical.
//
// usage: node compare.mjs            (expects run-bots/swift-paper.jar; build with ./gradlew createPaperclipJar)
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const RUN = path.resolve(HERE, '..', '..', 'run-bots')
const OFF = [
  '-Dswift.selectorIndex=false', '-Dswift.selectorTagIndex=false', '-Dswift.nbtKeyFilter=false', '-Dswift.flatBrainLoop=false',
  '-Dswift.poiSearchBitset=false', '-Dswift.poiRetain=false', '-Dswift.poiEmptySearchCache=false', '-Dswift.spawnCostSkip=false', '-Dswift.allyFastPath=false -Dswift.nearestFirstLineOfSight=false'
]

for (const [mode, flags] of [['on', []], ['off', OFF]]) {
  const r = spawnSync(process.execPath, [path.join(HERE, 'selector-bots.mjs'), mode, ...flags], { stdio: 'inherit' })
  if (r.status !== 0) {
    console.error(`run '${mode}' failed`)
    process.exit(2)
  }
}
const on = fs.readFileSync(path.join(RUN, 'transcript-on.txt'), 'utf8').split('\n')
const off = fs.readFileSync(path.join(RUN, 'transcript-off.txt'), 'utf8').split('\n')
const firstDiff = on.findIndex((line, i) => line !== off[i])
if (on.length === off.length && firstDiff === -1) {
  const counts = on.filter(l => /conditional\.(pass_count|fail)/.test(l)).length
  const order = on.filter(l => l.startsWith('  > ')).length
  console.log(`IDENTICAL: ${on.length} lines (${counts} selector counts, ${order} execution-order lines)`)
  process.exit(0)
}
console.log(`DIFFERENT: on ${on.length} lines, off ${off.length} lines; first difference at line ${firstDiff + 1}:`)
console.log(`  on : ${on[firstDiff]}`)
console.log(`  off: ${off[firstDiff]}`)
process.exit(1)
