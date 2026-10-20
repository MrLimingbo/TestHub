'use strict'
const id=v=>typeof v==='string'&&/^[0-9A-HJKMNP-TV-Z]{26}$/.test(v)
const field=k=>['descriptorid','grpcdescriptorid'].includes(k.replace(/[_-]/g,'').toLowerCase())
function map(value,change){if(Array.isArray(value))return value.map(v=>map(v,change));if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).map(([key,v])=>[key,field(key)?change(v):map(v,change)]));return value}
function containsUnmapped(value){if(Array.isArray(value))return value.some(containsUnmapped);if(!value||typeof value!=='object')return false;return Object.entries(value).some(([k,v])=>field(k)?v!==null&&v!==''&&!(typeof v==='string'&&/^testhub-grpc:[0-9A-HJKMNP-TV-Z]{26}$/.test(v)):containsUnmapped(v))}
class GrpcDescriptorLinks{
 constructor(binding,context){this.byKey=new Map();this.byId={local:new Map(),remote:new Map()};if(!context)return;if(context.sourceProject!==binding.source.id||context.targetProject!==binding.target.id||!Array.isArray(context.links)||context.links.length>20)throw new Error('描述符配对范围无效。');for(const l of context.links){if(!id(l.key)||!id(l.localId)||!id(l.remoteId)||this.byKey.has(l.key)||this.byId.local.has(l.localId)||this.byId.remote.has(l.remoteId))throw new Error('描述符配对重复或标识无效。');this.byKey.set(l.key,l);this.byId.local.set(l.localId,l);this.byId.remote.set(l.remoteId,l)}}
 logical(item,side){if(item.kind!=='CASE')return item;const result=globalThis.structuredClone(item);result.definition.requestSnapshot=map(result.definition.requestSnapshot,v=>{if(v===null||v==='')return v;const l=id(v)&&this.byId[side].get(v);return l?'testhub-grpc:'+l.key:'unconfirmed-grpc-reference'});return result}
 physical(item,side){if(item.kind!=='CASE')return item;const result=globalThis.structuredClone(item);result.definition.requestSnapshot=map(result.definition.requestSnapshot,v=>{if(v===null||v==='')return v;const key=typeof v==='string'&&/^testhub-grpc:([0-9A-HJKMNP-TV-Z]{26})$/.exec(v)?.[1],l=key&&this.byKey.get(key);if(!l)throw new Error('用例引用的描述符未配对、已删除或同步已暂停；该资料暂停同步。');return l[side+'Id']});return result}
}
module.exports={GrpcDescriptorLinks,containsUnmapped}
