import { createHash } from 'node:crypto'
import { readFile, readdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// This is the immutable starting point for NEW desktop databases, not a MySQL data converter.
// Once released, schema changes belong in V2+ desktop migrations; never regenerate a published V1.
const serverBaseline = 61
const root = fileURLToPath(new URL('../../', import.meta.url))
const source = path.join(root, 'services/platform-api/src/main/resources/db/migration')
const target = path.join(root, 'services/platform-api/src/main/resources/db/desktop/V1__desktop_schema.sql')

function splitTopLevel(sql, delimiter) {
  const parts = []
  let depth = 0
  let quote = null
  let start = 0
  for (let index = 0; index < sql.length; index++) {
    const character = sql[index]
    if (quote) {
      if (character === '\\') index++
      else if (character === quote && sql[index + 1] === quote) index++
      else if (character === quote) quote = null
    } else if (`'"\``.includes(character)) quote = character
    else if (character === '(') depth++
    else if (character === ')') depth--
    else if (character === delimiter && depth === 0) {
      parts.push(sql.slice(start, index).trim())
      start = index + 1
    }
  }
  if (quote || depth !== 0) throw new Error('Unbalanced SQL in desktop baseline source')
  parts.push(sql.slice(start).trim())
  return parts.filter(Boolean)
}

const files = (await readdir(source))
  .filter(name => /^V\d+__.*\.sql$/.test(name) && Number(name.match(/^V(\d+)/)[1]) <= serverBaseline)
  .sort((left, right) => Number(left.match(/^V(\d+)/)[1]) - Number(right.match(/^V(\d+)/)[1]))
if (files.length !== serverBaseline) throw new Error('Incomplete server baseline migrations')
const digest = createHash('sha256')
const blocks = []
let excludedDataStatements = 0
for (const name of files) {
  const text = await readFile(path.join(source, name), 'utf8')
  digest.update(name).update('\0').update(text).update('\0')
  const statements = []
  for (let statement of splitTopLevel(text.replace(/^\s*--[^\n]*/gm, ''), ';')) {
    if (/^(INSERT INTO|UPDATE)\b/.test(statement)) {
      excludedDataStatements++
      continue
    }
    if (!/^(CREATE TABLE|ALTER TABLE)\b/.test(statement)) throw new Error(`Unsupported statement in ${name}`)
    statement = statement.replace(/\s+DEFAULT CHARSET=\w+\s+COLLATE=\w+/g, '')
    const alter = statement.match(/^ALTER TABLE (\w+)\s+([\s\S]*)$/)
    if (alter) {
      for (const action of splitTopLevel(alter[2], ',')) statements.push(`ALTER TABLE ${alter[1]} ${action};`)
    } else statements.push(`${statement};`)
  }
  blocks.push(`-- Source: ${name}\n${statements.join('\n')}`)
}
if (excludedDataStatements !== 10) throw new Error('Unexpected data migration change; review the desktop baseline')
const generated = `-- Generated initial schema for a NEW desktop database at server schema V${serverBaseline}.
-- Source SHA-256: ${digest.digest('hex')}
-- Ten data-upgrade statements are excluded because no older desktop database exists.
-- Do not regenerate after publishing; use subsequent versioned desktop migrations.
CREATE ALIAS JSON_LENGTH FOR "io.testhub.platform.desktop.DesktopJsonFunctions.length";
CREATE ALIAS JSON_SEARCH FOR "io.testhub.platform.desktop.DesktopJsonFunctions.search";

${blocks.join('\n\n')}\n`
if (process.argv.includes('--write')) await writeFile(target, generated)
else if (await readFile(target, 'utf8') !== generated) throw new Error('Desktop baseline differs from its fixed server V61 source')
console.log(`Desktop baseline ${process.argv.includes('--write') ? 'generated' : 'verified'} from ${files.length} migrations`)
