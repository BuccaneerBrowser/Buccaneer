'use strict';
const fs=require('fs'),path=require('path'),assert=require('assert'),acorn=require('acorn');
const root=path.resolve(__dirname,'..'),contract=JSON.parse(fs.readFileSync(path.join(__dirname,'bridge-contract.json'),'utf8'));
const signatures=new Map(contract.map(x=>[x.name,x.args.length]));let calls=0,refs=0;
function children(node){return Object.values(node).flatMap(x=>Array.isArray(x)?x:x&&typeof x==='object'?[x]:[]).filter(x=>x&&x.type);}
function property(node){return !node.computed?node.property.name:node.property.type==='Literal'?node.property.value:null;}
function check(text,file){
 const ast=acorn.parse(text,{ecmaVersion:2018}),scopes=new WeakMap(),top={parent:null,bindings:new Map()};
 function build(node,scope){
  if(/Function(?:Declaration|Expression)|ArrowFunctionExpression/.test(node.type)){
   scope={parent:scope,bindings:new Map(),fn:node};for(const p of node.params)if(p.type==='Identifier')scope.bindings.set(p.name,null);
  }
  scopes.set(node,scope);
  if(node.type==='VariableDeclarator'&&node.id.type==='Identifier')scope.bindings.set(node.id.name,node.init);
  children(node).forEach(x=>build(x,scope));
 }
 build(ast,top);
 function bridge(node,scope,seen=new Set()){
  if(!node)return false;
  if(node.type==='MemberExpression')return node.object.type==='Identifier'&&node.object.name==='window'&&property(node)==='Buccaneer';
  if(node.type!=='Identifier')return false;
  if(node.name==='Buccaneer')return true;
  while(scope){if(scope.bindings.has(node.name)){const expr=scope.bindings.get(node.name);if(seen.has(expr))return false;seen.add(expr);return bridge(expr,scope,seen);}scope=scope.parent;}return false;
 }
 function walk(node){
  if(node.type==='MemberExpression'&&bridge(node.object,scopes.get(node))){
   const name=property(node);
   if(name===null){
    const fn=scopes.get(node).fn;
    assert(fn&&fn.id&&fn.id.name==='bridgeCall'&&node.property.name==='name',file+': unmodelled dynamic bridge access');
    assert.deepStrictEqual(fn.params.map(x=>x.name),['name','arg']);
    assert(text.slice(fn.start,fn.end).includes("return arg === undefined ? b[name]() : b[name](arg);"),'dynamic wrapper semantics drift');
   }else assert(signatures.has(name),file+': unknown native bridge member '+name);
   refs++;
  }
  if(node.type==='CallExpression'&&node.callee.type==='MemberExpression'&&bridge(node.callee.object,scopes.get(node))){
   const name=property(node.callee);if(name!==null){assert.strictEqual(node.arguments.length,signatures.get(name),file+': native arity '+name);calls++;}
  }
  if(node.type==='CallExpression'&&node.callee.type==='Identifier'&&node.callee.name==='bridgeCall'){
   const name=node.arguments[0]&&node.arguments[0].value;
   assert(signatures.has(name),file+': unknown/dynamic bridgeCall target '+name);
   assert.strictEqual(node.arguments.length-1,signatures.get(name),file+': bridgeCall arity '+name);calls++;
  }
  children(node).forEach(walk);
 }walk(ast);
}
for(const f of fs.readdirSync(path.join(root,'app/src/main/assets')).filter(x=>x.endsWith('.js')))check(fs.readFileSync(path.join(root,'app/src/main/assets',f),'utf8'),f);
for(const f of fs.readdirSync(path.join(root,'app/src/main/java/com/buccaneer/browser')).filter(x=>x.endsWith('.kt'))){
 const text=fs.readFileSync(path.join(root,'app/src/main/java/com/buccaneer/browser',f),'utf8');
 for(const m of text.matchAll(/Buccaneer\.(\w+)\s*\(/g))assert(signatures.has(m[1]),f+': unknown inline bridge member '+m[1]);
}
assert(calls>0);console.log('PASS: parsed all injected JS; '+calls+' native calls and '+refs+' native member references match the checked 21-method ABI (including lexical aliases)');
