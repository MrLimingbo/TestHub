// Acceptance fixture only. Exercises the authenticated protocol; never starts Appium or a device.
// Keep credentials in the caller's isolated process. Only sanitized results leave this helper.
export function mobileProtocolFixture({origin,brokerKey,heartbeatKey,crypto,runnerId='qa-mobile-protocol'}) {
  if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(origin)||!runnerId.startsWith('qa-mobile-'))throw new Error('Owned mobile protocol fixture required')
  const startedAt=new Date().toISOString(),sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex')
  const hmac=text=>crypto.createHmac('sha256',heartbeatKey).update(text).digest('hex')
  const token=crypto.createHmac('sha256',brokerKey).update('testhub.desktop-broker.v1:appium:'+runnerId).digest('hex')
  async function request(route,body,headers={}) {
    const response=await globalThis.fetch(origin+route,{method:'POST',headers,body})
    const result=await response.json();if(!response.ok)throw new Error('Owned mobile fixture HTTP '+response.status+': '+(result.message||'protocol rejected'))
    return result.data??result.result
  }
  const command=body=>request('/internal/desktop-broker/v1/command',JSON.stringify({engine:'appium',runnerId,...body}),{'content-type':'application/json','X-TestHub-Desktop-Token':token})
  async function heartbeat() {
    const heartbeatAt=new Date().toISOString(),payload={runnerId,runnerType:'appium',protocolVersion:'1',runnerVersion:'qa-protocol-fixture',executionEnabled:true,startedAt,heartbeatAt,maxConcurrentTasks:1,activeTaskCount:0,trustVersion:'1'}
    payload.signature=hmac([runnerId,'appium','1',payload.runnerVersion,true,startedAt,heartbeatAt,1,0].join('\n'))
    await command({operation:'heartbeat',body:JSON.stringify(payload),ttlSeconds:60})
  }
  function headers(route,bindings) {
    const timestamp=new Date().toISOString(),nonce=crypto.randomBytes(24).toString('base64url')
    return {'X-TestHub-Runner-Id':runnerId,'X-TestHub-Runner-Timestamp':timestamp,'X-TestHub-Runner-Nonce':nonce,'X-TestHub-Runner-Signature':hmac(['testhub.mobile-automation-runner.internal-auth.v1','POST',route,runnerId,timestamp,nonce,...bindings].join('\n'))}
  }
  async function claim({taskId,executionId}) {
    await heartbeat();const route='/internal/mobile-automation-runner/v1/runtime-configuration'
    const runtime=await request(route,JSON.stringify({taskId,executionId}),{...headers(route,[taskId,executionId]),'content-type':'application/json'})
    if(runtime.contract!=='testhub.appium-runner.runtime-configuration'||runtime.executionId!==executionId)throw new Error('Mobile runtime fixture mismatch')
    return {executionId,scenarioName:runtime.manifest.scenario.name}
  }
  async function step({taskId,executionId,stepId='launch',stepSequence=1,stepType='LAUNCH',stepName='协议夹具步骤',outcome='PASSED',durationMillis=12,errorMessage='',occurredAt=new Date().toISOString()}) {
    await command({operation:'publish',stream:'testhub:runner:appium:step-results:v1',fields:{contract:'testhub.appium-runner.step-result',protocolVersion:'1',taskId,executionId,runnerId,stepId,stepSequence:String(stepSequence),stepType,stepName,outcome,durationMillis:String(durationMillis),errorMessage,occurredAt}})
    return {occurredAt}
  }
  async function finish({taskId,executionId,status='SUCCEEDED'}) {
    await command({operation:'publish',stream:'testhub:runner:appium:events:v1',fields:{contract:'testhub.appium-runner.event',protocolVersion:'1',taskId,executionId,runnerId,type:status,code:'QA_PROTOCOL_'+status,message:'Owned protocol fixture; no device execution',occurredAt:new Date().toISOString()}})
  }
  async function screenshot({taskId,executionId,content}) {
    await heartbeat();const bytes=Uint8Array.from(globalThis.atob(content),v=>v.charCodeAt(0)),checksum=sha(bytes),route='/internal/mobile-automation-runner/v1/artifacts'
    return request(route,bytes,{...headers(route,[taskId,executionId,'SCREENSHOT',String(bytes.length),checksum]),'content-type':'image/png','content-length':String(bytes.length),'X-TestHub-Task-Id':taskId,'X-TestHub-Execution-Id':executionId,'X-TestHub-Artifact-Type':'SCREENSHOT','X-TestHub-Artifact-SHA256':checksum,'X-TestHub-Artifact-Size':String(bytes.length)})
  }
  return {claim,step,screenshot,finish}
}
