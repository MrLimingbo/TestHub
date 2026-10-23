// Remove only sessions whose serialized principal and authenticated HTTP profile match the exact QA account.
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import {Buffer} from 'node:buffer'
import {execFile} from 'node:child_process'
import {promisify} from 'node:util'

const run = promisify(execFile)
export function ownedAccountSessions({container,password,origin}) {
  assert.match(container,/^[a-zA-Z0-9_-]+$/); assert.ok(password)
  assert.equal(new URL(origin).hostname,'127.0.0.1')
  const prefix='testhub:session:sessions:'
  async function redis(...args) {
    const {stdout}=await run('docker',['exec','-e','REDISCLI_AUTH',container,'redis-cli','--raw',...args],{env:{...process.env,REDISCLI_AUTH:password},encoding:'buffer',maxBuffer:16*1024*1024})
    return stdout
  }
  async function keys() {
    const values=(await redis('--scan','--pattern',prefix+'*')).toString().trim().split('\n').filter(Boolean).sort()
    assert.ok(values.every(key=>/^testhub:session:sessions:[a-f0-9-]{36}$/.test(key)))
    return values
  }
  async function fingerprint() {
    const hash=crypto.createHash('sha256')
    for(const key of await keys()) {
      hash.update(key)
      const fields=(await redis('HKEYS',key)).toString().trim().split('\n').filter(Boolean).sort()
      // The user's open Web tabs legitimately refresh this timestamp while background QA runs.
      for(const field of fields.filter(field=>field!=='lastAccessedTime')) { hash.update(field);hash.update(await redis('HGET',key,field)) }
    }
    return hash.digest('hex')
  }
  async function cleanup(account) {
    assert.match(account,/^thqa-[a-f0-9]{32}$/)
    let removed=0
    for(const key of await keys()) {
      const context=await redis('HGET',key,'sessionAttr:SPRING_SECURITY_CONTEXT')
      if(!context.includes(Buffer.from(account+'@example.test')))continue
      assert.ok(context.includes(Buffer.from('后台联调验收')),'Unexpected QA session owner')
      const sessionId=key.slice(prefix.length), cookie='TESTHUB_SESSION='+Buffer.from(sessionId).toString('base64')
      const profile=await globalThis.fetch(origin+'/api/v1/auth/me',{headers:{cookie},redirect:'error'})
      assert.equal(profile.status,200,'Cannot authenticate the exact owned session')
      assert.equal((await profile.json()).data.account,account)
      const response=await globalThis.fetch(origin+'/api/v1/auth/csrf',{headers:{cookie},redirect:'error'})
      assert.equal(response.status,200)
      const csrf=(await response.json()).data, cookies=response.headers.getSetCookie().map(value=>value.split(';')[0])
      const logout=await globalThis.fetch(origin+'/api/v1/auth/logout',{method:'POST',headers:{cookie:[cookie,...cookies].join('; '),[csrf.headerName]:csrf.token},redirect:'error'})
      assert.equal(logout.status,204)
      assert.equal((await redis('EXISTS',key)).toString().trim(),'0','Owned session remains in Redis')
      removed++
    }
    for(const key of await keys()) assert.ok(!(await redis('HGET',key,'sessionAttr:SPRING_SECURITY_CONTEXT')).includes(Buffer.from(account+'@example.test')),'Owned QA principal remains')
    return {removed,absent:true}
  }
  return {cleanup,fingerprint}
}
