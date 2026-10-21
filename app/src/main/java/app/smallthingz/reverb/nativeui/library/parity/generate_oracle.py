from pathlib import Path
import subprocess,re,hashlib,json
BASE='3e16f8ddb78dca15df4b436d34d330801ce83ffd'
ROOT=Path('/home/a/kotlin/reverb/.worktree/native-hosts-20261021')
OUT=Path(__file__).resolve().parent
relative='app/src/main/java/app/smallthingz/reverb/FilesScreen.kt'
source=subprocess.check_output(['git','show',BASE+':'+relative],cwd=ROOT).decode()
# Rename top-level declarations and their identifier references only. Kotlin strings, chars and
# comments are emitted byte-for-byte; no layout, callback, effect or state initializer is changed.
names=re.findall(r'^(?:(?:private|internal|public)\s+)?(?:(?:sealed|data|enum)\s+)?(?:class|object|(?:suspend\s+)?fun)\s+([A-Za-z_]\w*)',source,re.M)
rename={name:'ReleasedLibrary'+name for name in names}
pattern=re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_]\w*')
def replace(match):
 value=match.group(0)
 return rename.get(value,value)
result=pattern.sub(replace,source)
(OUT/'reference/FilesScreen.kt').write_text(source)
(OUT/'fixture/ReleasedLibraryFilesScreen.kt').write_text('// Generated from released '+BASE+'; identifier renames only.\n'+result)
record={'baseline':BASE,'source':relative,'source_sha256':hashlib.sha256(source.encode()).hexdigest(),'renames':rename,'generated_sha256':hashlib.sha256((OUT/'fixture/ReleasedLibraryFilesScreen.kt').read_bytes()).hexdigest(),'transformation':'Identifier renames only; Kotlin strings/comments and function bodies retained. No state or timing hook.'}
(OUT/'ORACLE.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps(record,indent=2))
