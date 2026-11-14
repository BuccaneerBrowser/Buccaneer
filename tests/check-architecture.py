"""Deterministic, source-only ownership/DAG/ABI gates (comments and strings are not edges)."""
from pathlib import Path
import json,re,subprocess,hashlib
from js_extractions import restore_extractions
ROOT=Path(__file__).resolve().parent.parent
SRC=ROOT/'app/src/main/java/com/buccaneer/browser'
def code(text):
    literals=r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/'
    return re.sub(literals,lambda m:'\n'*m[0].count('\n')+' ',text)
def graph(sources):
    clean={k:code(v) for k,v in sources.items()};symbols={}
    for name,text in clean.items():
        for match in re.finditer(r'^(?:(?:public|internal|private|sealed|data|enum|open|abstract|value|annotation)\s+)*(?:class|interface|object|fun)\s+(\w+)(?![\w.])',text,re.M):
            symbol=match[1]
            assert symbol not in symbols or symbols[symbol]==name,(symbol,'ambiguous symbol')
            symbols[symbol]=name
    return {name:sorted({symbols[t] for t in re.findall(r'\b\w+\b',text) if t in symbols and symbols[t]!=name}) for name,text in clean.items()}
def cycles(g):
    result=set()
    def visit(node,stack):
        if node in stack:
            ring=stack[stack.index(node):];result.add(tuple(sorted(ring)));return
        for child in g[node]:visit(child,stack+[node])
    for node in g:visit(node,[])
    return sorted(result)
def methods(text,override=False):
    prefix=r'@JavascriptInterface\s+(?:override\s+)?' if override else ''
    found=[]
    for name,args,ret in re.findall(prefix+r'fun\s+(\w+)\(([^)]*)\)(?:\s*:\s*([\w?]+))?',text):
        params=[x.strip().split(':',1)[1].strip() for x in args.split(',') if x.strip()]
        found.append({'name':name,'args':params,'returns':ret or 'Unit'})
    return sorted(found,key=lambda m:m['name'])
def verify(root=ROOT):
    src=root/'app/src/main/java/com/buccaneer/browser'
    sources={p.stem:p.read_text() for p in src.glob('*.kt')}
    g=graph(sources);rings=cycles(g)
    assert not rings,('Production Kotlin dependency cycle',rings)
    clean={k:code(v) for k,v in sources.items()}
    for file,forbidden in [('EditorController',{'MediaController','MediaHost','MediaOwnership','videoActive','mediaOwner'}),('MediaController',{'EditorController','EditorHost','inputFrame','textMode','keypad'}),('MediaOwnership',{'EditorController','EditorHost','inputFrame','textMode'})]:
        identifiers=set(re.findall(r'\b\w+\b',clean[file]));assert not identifiers&forbidden,(file,identifiers&forbidden)
    for field,owner in [('foreground','ForegroundState'),('inputFrame','EditorController'),('textMode','EditorController'),('videoActive','MediaOwnership'),('owner','MediaOwnership')]:
        declarations=[f for f,t in clean.items() if re.search(r'\bvar\s+'+field+r'\b',t)]
        assert declarations==[owner],(field,'unexpected writer',declarations)
        assert re.search(r'\bvar\s+'+field+r'\b[^\n]*private set',sources[owner]),(field,'setter must be private')
    for f in ['EditorController','MediaController']:
        assert 'val interactionSerial' not in clean[f],f+' must use its own cancellation stamp'
        assert 'private val document: DocumentIdentity' in clean[f]
    assert 'BookmarkProvider' not in clean['BookmarkStore']
    assert 'BuccaneerApplication' not in clean['BookmarkStore']
    assert 'MainActivity' not in clean['Watchdog']
    expected=json.loads((root/'tests/bridge-contract.json').read_text())
    assert methods(sources['BrowserBridgeContract'])==expected,'Bridge contract drift'
    assert methods(sources['MainActivity'],True)==expected,'Annotated bridge signature/return-type drift'
    assert len(expected)==21
    assert sources['MainActivity'].count('@JavascriptInterface')==21
    assert not any(re.search(r'\bvar\s+(?:pageEpoch|documentToken)\b',t) for t in clean.values())
    for field in ['inputFrame','textMode','editorSession']:
        assert 'editor.'+field not in clean['MainActivity'],'Read editor state through EditorStateView'
    for field in ['owner','videoActive']:
        assert 'mediaOwnership.'+field not in clean['MainActivity'],'Read media state through MediaOwnershipView'
    assert not re.search(r'(?:editor|mediaOwnership)\.(?:inputFrame|textMode|owner|videoActive)\s*=(?!=)',clean['MainActivity']),'External state write'
    assert '__tvUi.edit(' not in sources['MainActivity'],'Edit protocol belongs to EditorController'
    assert '__fb.unmute()' not in sources['MainActivity'],'Mute policy belongs to MediaController'
    assert sum(t.count('__fb.unmute()') for t in sources.values())==1,'One mute rule set'
    preservation=json.loads((root/'tests/preservation237.json').read_text())
    for file,digest in preservation['assets'].items():
        restored=restore_extractions(root,file,(root/file).read_text())
        assert hashlib.sha256(restored.encode()).hexdigest()==digest,('Baseline behavior drift',file)
    version=(root/'app/build.gradle.kts').read_text()
    assert 'versionCode = 30' in version and 'versionName = "2.3.7"' in version
    print('PASS: all baseline JS rules/budgets preserved; version 30 / 2.3.7')
    print('PASS: whole-production Kotlin DAG, private single writers, input/media separation, 21-method annotated ABI')
    return g
if __name__=='__main__':
    g=verify();out=ROOT/'untangle-v237';out.mkdir(exist_ok=True)
    (out/'dependency-after.json').write_text(json.dumps(g,indent=2)+'\n')
    base={}
    for name in g:
        p=f'app/src/main/java/com/buccaneer/browser/{name}.kt'
        result=subprocess.run(['git','show',f'2c8a7d7:{p}'],cwd=ROOT,capture_output=True,text=True)
        if result.returncode==0:base[name]=result.stdout
    before=graph(base)
    (out/'dependency-before.json').write_text(json.dumps({'graph':before,'cycles':cycles(before)},indent=2)+'\n')
