const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/fmhy_theme.js'),'utf8');
for(const host of ['fmhy.net','www.fmhy.net','flixer.gd','coreflix.test','notfmhy.net']) for(const dark of [true,false]) {
  const keys={},classes=new Set(['light']);
  const c={location:{hostname:host},window:{Buccaneer:{isDarkMode:()=>dark}},localStorage:{setItem:(k,v)=>keys[k]=v},document:{documentElement:{classList:{toggle:(k,on)=>on?classes.add(k):classes.delete(k)}}}};
  vm.createContext(c);vm.runInContext(source,c);
  if(host==='fmhy.net'||host==='www.fmhy.net'){
    assert.equal(keys['vitepress-display-mode'],dark?'dark':'light');
    assert.equal(keys['vitepress-theme-appearance'],dark?'dark':'light');
    assert.equal(classes.has('dark'),dark);assert.equal(classes.has('light'),!dark);
  }else{assert.equal(Object.keys(keys).length,0);assert(classes.has('light'));}
}
console.log('PASS: FMHY theme keys/classes for dark and light; all unrelated origins untouched.');
