from pathlib import Path
import hashlib, json, re, subprocess
ROOT=Path('/home/a/kotlin/reverb')
BASELINE='3e16f8ddb78dca15df4b436d34d330801ce83ffd'
REL='app/src/main/java/app/smallthingz/reverb/RangeExportEditor.kt'
OUT=Path(__file__).resolve().parent
source=subprocess.check_output(['git','show',f'{BASELINE}:{REL}'],cwd=ROOT,text=True)
header=source[:source.index('internal enum class RangeEditTarget')]
constants=source[source.index('private const val RANGE_TIMELINE_MARKER_EXTRA_HEIGHT_FRACTION'):source.index('internal data class RangeEditValues')]
start=source.index('@Composable\ninternal fun RangeExportHomeContent(')
marker=source.index('@Composable\ninternal fun RangeTimelineMarkerVisual(',start)
bar=source.index('@Composable\nprivate fun RangeTimelineBar(',marker)
fine=source.index('@Composable\ninternal fun SpringFineSeekControl(',bar)
controls=source.index('@Composable\nprivate fun RangeExportControls(',fine)
parts=[source[start:marker], source[bar:fine], source[controls:]]
body='\n'.join(parts)
names=['RangeExportHomeContent','RangeSelectionDurationWheel','RangeExportTimeline','bubbleOffset','RangeTimelineBar','TimelineTimeLabel','SpringFineAdjust','RangeExportControls']
for name in names:
 body=re.sub(r'\b'+name+r'\b','ReleasedRangeFixture'+name,body)
assert body.count('    snapshot: ReverbService.TimelineSnapshot?,')==1
body=body.replace('    snapshot: ReverbService.TimelineSnapshot?,','    snapshot: ReverbService.TimelineSnapshot?,\n    fixtureStateObserver: (RangeExportEditorState) -> Unit,',1)
needle='    val colors = MaterialTheme.colorScheme\n    var transitionStarted'
assert body.count(needle)==1
body=body.replace(needle,'    androidx.compose.runtime.SideEffect { fixtureStateObserver(state) }\n'+needle,1)
for name in re.findall(r'private const val (\w+)',constants):
 constants=re.sub(r'\b'+name+r'\b','FIXTURE_'+name,constants)
 body=re.sub(r'\b'+name+r'\b','FIXTURE_'+name,body)
generated=header+'// Generated released oracle; see generate_oracle.py. No production source is edited.\n'+constants+body
target=OUT/'ReleasedRangeFixtureOracle.kt'
target.write_text(generated)
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
pins={}
for rel in [REL,'app/src/main/java/app/smallthingz/reverb/ProgressiveWaveform.kt','app/src/main/java/app/smallthingz/reverb/RangeDurationWheelView.kt','app/src/main/java/app/smallthingz/reverb/ReverbTheme.kt']:
 try:data=subprocess.check_output(['git','show',f'{BASELINE}:{rel}'],cwd=ROOT)
 except subprocess.CalledProcessError:continue
 pins[rel]=hashlib.sha256(data).hexdigest()
receipt={'baseline':BASELINE,'source':REL,'source_sha256':sha(source),'selected_released_spans_sha256':[sha(p) for p in parts],'generated_oracle_sha256':sha(generated),'transformations':['Copy released imports and nine marker/time-label constants; prefix constants to avoid source-set identifier collisions','Copy complete RangeExportHomeContent and its private wheel/timeline/bar/time-label/fine-adjust/controls leaves; prefix only those top-level function identifiers','Keep actual released internal RangeTimelineMarkerVisual and SpringFineSeekControl calls; no clone of their renderer','Add ONE reliability-only read-only fixtureStateObserver parameter and SideEffect(state) callback after state creation; no mutation, layout, source condition, effect key or animation change'],'baseline_file_pins':pins,'production_files_modified':False}
(OUT/'ORACLE.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt,indent=2))
