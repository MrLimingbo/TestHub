// Live-service QA cleanup: only a freshly registered, private test account may be removed.
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import {execFile, spawn} from 'node:child_process'
import {promisify} from 'node:util'

const run = promisify(execFile)
const identifier = value => { assert.match(value, /^[a-zA-Z0-9_]+$/); return '`' + value + '`' }
const literal = value => { assert.match(value, /^[a-zA-Z0-9@._-]+$/); return "'" + value + "'" }

export async function ownedAccountDatabase({container, database, user, password}) {
  for (const value of [container, database, user]) assert.match(value, /^[a-zA-Z0-9_-]+$/)
  assert.ok(password, 'Explicit QA database credentials are required')
  async function sql(query) {
    return new Promise((resolve, reject) => {
      const child = spawn('docker', ['exec', '-i', '-e', 'MYSQL_PWD', container, 'mysql', '--batch', '--raw', '--skip-column-names', '-u' + user, database], {
        env: {...process.env, MYSQL_PWD:password}, stdio:['pipe','pipe','pipe'],
      })
      let stdout = ''
      child.stdout.on('data', bytes => { stdout += bytes })
      // SQL diagnostics may contain private fixture values; keep them out of acceptance logs.
      child.stderr.resume()
      child.once('error', reject)
      child.once('close', code => code === 0 ? resolve(stdout.trim().split('\n').filter(Boolean).map(row => row.split('\t'))) : reject(new Error('Owned-account SQL operation failed (exit ' + code + ')')))
      child.stdin.end(query)
    })
  }
  const schema = literal(database)
  const columns = await sql(`SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=${schema}`)
  const primary = await sql(`SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=${schema} AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION`)
  const foreign = await sql(`SELECT TABLE_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=${schema} AND REFERENCED_TABLE_NAME IS NOT NULL`)
  const tables = [...new Set(columns.map(row => row[0]))].sort()
  const fields = table => columns.filter(row => row[0] === table).map(row => row[1])
  const keys = table => primary.filter(row => row[0] === table).map(row => row[1])
  assert.ok(tables.every(table => keys(table).length), 'Every table must have a primary key')
  async function fingerprint() {
    const rows = await sql('CHECKSUM TABLE ' + tables.map(identifier).join(',') + ' EXTENDED')
    assert.equal(rows.length, tables.length)
    assert.ok(rows.every(row => /^\d+$/.test(row[1])), 'Table checksum unavailable')
    return crypto.createHash('sha256').update(JSON.stringify(rows)).digest('hex')
  }
  async function owned(account, email) {
    assert.match(account, /^thqa-[a-f0-9]{32}$/)
    assert.equal(email, account + '@example.test')
    const users = await sql(`SELECT id FROM user_account WHERE account=${literal(account)} AND email=${literal(email)}`)
    if (!users.length) return undefined
    assert.equal(users.length, 1)
    const uid = users[0][0], code = 'personal-' + uid.toLowerCase()
    const organizations = await sql(`SELECT id FROM organization WHERE code=${literal(code)}`)
    assert.equal(organizations.length, 1, 'Only a fresh personal organization can be cleaned')
    const oid = organizations[0][0]
    const projects = await sql(`SELECT id FROM project WHERE organization_id=${literal(oid)} AND created_by=${literal(uid)}`)
    assert.equal(projects.length, 1, 'Acceptance must own exactly one project')
    assert.deepEqual(await sql(`SELECT COUNT(*) FROM membership WHERE organization_id=${literal(oid)} AND user_id<>${literal(uid)}`), [['0']])
    assert.deepEqual(await sql(`SELECT COUNT(*) FROM membership WHERE user_id=${literal(uid)} AND organization_id<>${literal(oid)}`), [['0']])
    return {uid, oid, pid:projects[0][0]}
  }
  async function cleanup(account, email) {
    const identity = await owned(account, email)
    if (!identity) return {removed:false, absent:true}
    const {uid, oid, pid} = identity
    function scope(table, visited = new Set()) {
      if (visited.has(table)) return undefined
      const next = new Set([...visited, table]), col = fields(table), clauses = []
      if (table === 'user_account') return `id=${literal(uid)}`
      if (table === 'organization') return `id=${literal(oid)}`
      for (const [column, value] of [['organization_id',oid], ['project_id',pid], ['user_id',uid]]) if (col.includes(column)) clauses.push(`${identifier(column)}=${literal(value)}`)
      if (!clauses.length) for (const [child, column, parent, parentColumn] of foreign) {
        if (child !== table || parent === table) continue
        const parentScope = scope(parent, next)
        if (parentScope) clauses.push(`${identifier(column)} IN (SELECT ${identifier(parentColumn)} FROM ${identifier(parent)} WHERE ${parentScope})`)
      }
      return clauses.length ? '(' + clauses.join(' OR ') + ')' : undefined
    }
    const scopes = new Map(tables.map(table => [table, scope(table)]).filter(([, predicate]) => predicate))
    const counts = await sql([...scopes].map(([table,predicate]) => `SELECT ${literal(table)},COUNT(*) FROM ${identifier(table)} WHERE ${predicate}`).join(';'))
    const nonempty = counts.filter(row => Number(row[1]) > 0).map(row => row[0]), order = [], pending = new Set(nonempty)
    while (pending.size) {
      const leaves = [...pending].filter(table => !foreign.some(([child,,parent]) => parent === table && child !== table && pending.has(child)))
      assert.ok(leaves.length, 'Owned data has a foreign-key cycle; manual inspection required')
      for (const table of leaves) { order.push(table); pending.delete(table) }
    }
    const temp = new Map(nonempty.map((table, index) => [table, 'qa_owned_' + index]))
    const guard = `(SELECT COUNT(*) FROM user_account WHERE id=${literal(uid)} AND account=${literal(account)} AND email=${literal(email)})=1 AND (SELECT COUNT(*) FROM organization WHERE id=${literal(oid)} AND code=${literal('personal-'+uid.toLowerCase())})=1 AND (SELECT COUNT(*) FROM project WHERE organization_id=${literal(oid)})=1 AND (SELECT COUNT(*) FROM membership WHERE organization_id=${literal(oid)} AND user_id<>${literal(uid)})=0 AND (SELECT COUNT(*) FROM membership WHERE user_id=${literal(uid)} AND organization_id<>${literal(oid)})=0`
    const statements = ['START TRANSACTION', 'CREATE TEMPORARY TABLE qa_owner_guard (ok INT CHECK(ok=1))', `INSERT INTO qa_owner_guard SELECT IF(${guard},1,0)`]
    for (const table of nonempty) statements.push(`CREATE TEMPORARY TABLE ${identifier(temp.get(table))} AS SELECT ${keys(table).map(identifier).join(',')} FROM ${identifier(table)} WHERE ${scopes.get(table)}`)
    for (const table of order) statements.push(`DELETE original FROM ${identifier(table)} original INNER JOIN ${identifier(temp.get(table))} owned ON ${keys(table).map(key => `original.${identifier(key)}=owned.${identifier(key)}`).join(' AND ')}`)
    statements.push('COMMIT')
    await sql(statements.join(';'))
    assert.equal(await owned(account, email), undefined)
    assert.deepEqual(await sql(`SELECT COUNT(*) FROM organization WHERE id=${literal(oid)}`), [['0']])
    for (const [table, predicate] of scopes) assert.deepEqual(await sql(`SELECT COUNT(*) FROM ${identifier(table)} WHERE ${predicate}`), [['0']], 'Owned rows remain: ' + table)
    return {removed:true, absent:true, tables:nonempty.length, rows:counts.reduce((sum,row) => sum + Number(row[1]),0)}
  }
  return {fingerprint, owned, cleanup}
}

export async function objectInventory(container, bucket) {
  assert.match(container, /^[a-zA-Z0-9_-]+$/); assert.match(bucket, /^[a-zA-Z0-9_-]+$/)
  const {stdout} = await run('docker', ['exec', container, 'find', '/data/' + bucket, '-type', 'f'], {maxBuffer:16*1024*1024})
  const paths = stdout.split('\n').filter(Boolean).sort()
  return {files:paths.length, digest:crypto.createHash('sha256').update(JSON.stringify(paths)).digest('hex')}
}
