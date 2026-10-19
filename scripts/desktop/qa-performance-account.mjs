// Acceptance-only account backend and genuine bundled JMeter/Playwright Runners, using owned loopback data.
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import { setTimeout as delay } from 'node:timers/promises'
const require = createRequire(import.meta.url)
const {childEnvironment}=require('../../apps/desktop/app/src/local-runtime.cjs')
const {waitForRunnerStatus}=require('../../apps/desktop/app/src/runner-readiness.cjs')
export async function performanceAccount({bundle,directory,origin,backend=bundle.backend,databasePassword=crypto.randomBytes(32).toString('hex'),encryptionKey=crypto.randomBytes(32).toString('base64')}) {
  if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(origin))throw new Error('QA target must be an owned loopback origin')
  const sessionKey=crypto.randomBytes(32).toString('hex'),brokerKey=crypto.randomBytes(32).toString('hex'),heartbeatKey=crypto.randomBytes(32).toString('hex'),children=[]
  await fs.mkdir(directory,{recursive:true})
  const ready=path.join(directory,'ready.json');await fs.rm(ready,{force:true})
  async function child(role,executable,args,env){
    const log=await fs.open(path.join(directory,role+'-private.log'),'w',0o600),process=spawn(executable,args,{env,windowsHide:true,stdio:['pipe',log.fd,log.fd]})
    const done=new Promise((resolve,reject)=>{process.once('error',reject);process.once('close',resolve)})
    const record={process,done,log};children.push(record);return record
  }
  async function stop(){for(const entry of [...children].reverse()){if(entry.process.exitCode===null){entry.process.stdin.end();entry.process.kill('SIGTERM');await entry.done}await entry.log.close()}children.length=0}
  try {
    const serverProcess=await child('backend',bundle.java,['-Xmx768m','-jar',backend,'--spring.profiles.active=desktop','--server.port=0'],{...childEnvironment(),TESTHUB_DESKTOP_DATA_DIRECTORY:directory,TESTHUB_LOCAL_DB_PASSWORD:databasePassword,TESTHUB_ENCRYPTION_KEY:encryptionKey,TESTHUB_DESKTOP_SESSION_KEY:sessionKey,TESTHUB_DESKTOP_BROKER_KEY:brokerKey,TESTHUB_PERFORMANCE_RUNNER_HEARTBEAT_KEY:heartbeatKey,TESTHUB_UI_AUTOMATION_RUNNER_HEARTBEAT_KEY:heartbeatKey,TESTHUB_DESKTOP_ALLOWED_ORIGINS:origin,TESTHUB_REGISTRATION_ENABLED:'true',TESTHUB_DESKTOP_READY_FILE:ready,TESTHUB_DESKTOP_FRONTEND_DIRECTORY:bundle.frontend,TESTHUB_DESKTOP_INSTANCE:crypto.randomUUID(),TESTHUB_DESKTOP_WATCH_PARENT:'true'})
    let server
    for(let n=0;n<900;n++){if(serverProcess.process.exitCode!==null)throw new Error('Owned account backend exited');try{server='http://127.0.0.1:'+JSON.parse(await fs.readFile(ready,'utf8')).port;break}catch{await delay(100)}}
    if(!server)throw new Error('Owned account backend startup timed out')
    async function startJmeter(home){
      const runnerId='desktop-jmeter',since=new Date().toISOString(),work=path.join(directory,'jmeter-work');await fs.mkdir(work,{recursive:true})
      const runner=await child('jmeter',bundle.node,[bundle.jmeterRunner],{...childEnvironment(),TESTHUB_DESKTOP_BROKER_URL:server,TESTHUB_DESKTOP_BROKER_TOKEN:crypto.createHmac('sha256',brokerKey).update('testhub.desktop-broker.v1:jmeter:'+runnerId).digest('hex'),TESTHUB_DESKTOP_PARENT_WATCHDOG:'1',RUNNER_ID:runnerId,PERFORMANCE_RUNNER_HEARTBEAT_KEY:heartbeatKey,TESTHUB_CONTROL_PLANE_URL:server,RUNNER_JMETER_EXECUTION_ENABLED:'true',RUNNER_ALLOWED_TARGET_ORIGINS:origin,JMETER_JAVA_BIN:bundle.java,JMETER_HOME:home,JMETER_WATCHDOG_JAR:bundle.jmeterWatchdog,RUNNER_WORK_DIRECTORY:work,RUNNER_HEARTBEAT_INTERVAL_SECONDS:'2',RUNNER_HEARTBEAT_TTL_SECONDS:'6'})
      const ready=await waitForRunnerStatus({origin:server,sessionKey,since,statusKey:'jmeterRunnerReady',label:'QA JMeter',checkRunning:()=>{if(runner.process.exitCode!==null)throw new Error('Owned JMeter Runner exited')}})
      if(!ready)throw new Error('Owned JMeter Runner readiness timed out')
    }
    async function startPlaywright(cache){
      const runnerId='desktop-playwright',since=new Date().toISOString(),work=path.join(directory,'playwright-work');await fs.mkdir(work,{recursive:true})
      const runner=await child('playwright',bundle.node,[bundle.playwrightRunner],{...childEnvironment(),TESTHUB_DESKTOP_BROKER_URL:server,TESTHUB_DESKTOP_BROKER_TOKEN:crypto.createHmac('sha256',brokerKey).update('testhub.desktop-broker.v1:playwright:'+runnerId).digest('hex'),TESTHUB_DESKTOP_PARENT_WATCHDOG:'1',RUNNER_ID:runnerId,UI_AUTOMATION_RUNNER_HEARTBEAT_KEY:heartbeatKey,TESTHUB_CONTROL_PLANE_URL:server,RUNNER_PLAYWRIGHT_EXECUTION_ENABLED:'true',RUNNER_ALLOWED_TARGET_ORIGINS:origin,PLAYWRIGHT_BROWSERS_PATH:cache,RUNNER_WORK_DIRECTORY:work,RUNNER_HEARTBEAT_INTERVAL_SECONDS:'2',RUNNER_HEARTBEAT_TTL_SECONDS:'6'})
      const ready=await waitForRunnerStatus({origin:server,sessionKey,since,statusKey:'playwrightRunnerReady',label:'QA Playwright',checkRunning:()=>{if(runner.process.exitCode!==null)throw new Error('Owned Playwright Runner exited')}})
      if(!ready)throw new Error('Owned Playwright Runner readiness timed out')
    }
    return {server,startJmeter,startPlaywright,stop}
  }catch(error){await stop();throw error}
}
