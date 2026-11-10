#!/usr/bin/env python3
"""Rebuild the explicitly authorized DriveDeck companion with embedded app surfaces from the exact supplied APK.
All outputs stay below --work. No device/ADB operations are performed.
"""
import argparse, pathlib, hashlib, subprocess, shutil, zipfile, json, sys
from profiles import select, generate_symbols
p=argparse.ArgumentParser()
for name in ('original', 'work', 'jdk', 'sdk', 'keystore', 'apktool'):
    p.add_argument('--' + name, type=pathlib.Path, required=True)
a=p.parse_args()
for name in ('original', 'work', 'jdk', 'sdk', 'keystore', 'apktool'):
    setattr(a, name, getattr(a, name).expanduser().resolve())
base=pathlib.Path(__file__).resolve().parent
work=a.work
if work == base:
    p.error('--work must be a separate build-output directory')
work.mkdir(exist_ok=True, parents=True)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
profile = select(sha(a.original))
tool=a.apktool
assert sha(tool)=='dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423','Unexpected Apktool'
java=a.jdk/'bin/java';javac=a.jdk/'bin/javac';bt=a.sdk/'build-tools/36.0.0';android=a.sdk/'platforms/android-37.0/android.jar'
def run(*args):
    subprocess.run([str(x) for x in args],check=True,cwd=work)
decoded=work/'decoded';framework=work/'framework'
run(java,'-jar',tool,'d',a.original,'-f','-o',decoded,'-p',framework)
run(sys.executable,'-m','unittest','discover','-s',base/'tests','-p','test_*.py')
run(sys.executable,base/profile.patch,decoded)
classes=work/'helper-classes';dex=work/'helper-dex'
for folder in (classes,dex):
    if folder.exists():shutil.rmtree(folder)
    folder.mkdir()
generated=work/'generated-aidl'
if generated.exists():shutil.rmtree(generated)
generated.mkdir()
generate_symbols(profile, decoded, generated/'ru/drivedeck/headswap/CompanionSymbols.java')
run(bt/'aidl','-I'+str(base/'aidl'),'-p'+str(android.parent/'framework.aidl'),'-o'+str(generated),base/'aidl/com/headswap/desk/embed/IEmbeddedDisplayGateway.aidl')
profile_tests=work/'profile-tests'
profile_tests.mkdir(exist_ok=True)
shared_sources=sorted((base/'shared').rglob('*.java'))
run(javac,'--release','8','-d',profile_tests,*shared_sources,base/'EmbeddedRenderProfile.java',base/'EmbeddedRetryGate.java',base/'EmbeddedSurfaceRelease.java',base/'EmbeddedTouchPacket.java',base/'EmbeddedResumeGate.java',base/'EmbeddedTextureHandoff.java',*sorted((base/'tests').glob('*.java')))
for test in ('EmbeddedRenderProfileTest','EmbeddedRetryGateTest','EmbeddedSurfaceReleaseTest','EmbeddedTouchPacketTest','EmbeddedResumeGateTest','EmbeddedTextureHandoffTest','MotionDispatchBufferTest'):
    run(java,'-cp',profile_tests,'ru.drivedeck.headswap.'+test)
run(javac,'--release','8','-classpath',android,'-d',classes,*shared_sources,*sorted(base.glob('*.java')),*sorted(generated.rglob('*.java')))
run(java,'-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--lib',android,'--min-api','26','--output',dex,*sorted(classes.rglob('*.class')))
unsigned=work/f'DriveDeck-HeadSwap-{profile.version}-unsigned.apk'
run(java,'-jar',tool,'b',decoded,'-p',framework,'-o',unsigned)
with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as z:
    assert 'classes2.dex' not in z.namelist()
    z.write(dex/'classes.dex','classes2.dex')
aligned=work/f'DriveDeck-HeadSwap-{profile.version}-aligned.apk'
run(bt/'zipalign','-f','-p','4',unsigned,aligned)
final=work/f'DriveDeck-HeadSwap-{profile.version}-code{profile.code}.apk'
run(java,'-jar',bt/'lib/apksigner.jar','sign','--ks',a.keystore,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',final,aligned)
run(java,'-jar',bt/'lib/apksigner.jar','verify','--verbose','--print-certs',final)
run(bt/'zipalign','-c','-p','4',final)
badging=subprocess.check_output([str(bt/'aapt'),'dump','badging',str(final)],text=True)
(work/'adapted-badging.txt').write_text(badging)
assert "package: name='ru.drivedeck.headswap'" in badging
assert f"versionCode='{profile.code}'" in badging
manifest=subprocess.check_output([str(bt/'aapt'),'dump','xmltree',str(final),'AndroidManifest.xml'],text=True)
(work/'adapted-manifest.txt').write_text(manifest)
for forbidden in ['sharedUserId','android.intent.category.HOME','android.intent.action.BOOT_COMPLETED','VoiceBootReceiver','DiagnosticUploadJob','LauncherAccessibilityService']:
    assert forbidden not in manifest,forbidden
assert 'ru.drivedeck.launcher.MainActivity' in manifest
import xml.etree.ElementTree as ET
manifest_root = ET.parse(decoded/'AndroidManifest.xml').getroot()
android_name = '{http://schemas.android.com/apk/res/android}name'
host_package = 'com.headswap.desk'
assert host_package + '.permission.EMBED_DISPLAY' in {x.get(android_name) for x in manifest_root.findall('uses-permission')}
assert host_package in {x.get(android_name) for x in manifest_root.findall('queries/package')}
with zipfile.ZipFile(final) as checked_apk:
    helper_dex = checked_apk.read('classes2.dex')
    assert b'appRenderConfig\0' in helper_dex, 'Missing per-package render configuration API'
    for endpoint in (host_package, 'com.headswap.desk.embed.EmbeddedDisplayService',
                     'com.headswap.desk.embed.IEmbeddedDisplayGateway'):
        # These short ASCII DEX strings each have a one-byte length and a NUL terminator.
        # Check the complete literal: the application ID is also a prefix of the service class.
        literal = bytes([len(endpoint)]) + endpoint.encode('ascii') + b'\0'
        assert literal in helper_dex, 'Missing compiled HeadSwap endpoint: ' + endpoint
for activity in manifest_root.find('application').findall('activity'):
    assert not activity.findall('intent-filter')
report=json.loads((work/'patch-report.json').read_text());report.update({'artifact':str(final),'adapted_sha256':sha(final),'apktool_sha256':sha(tool),'build':'PASS','apk_signature_verification':'PASS','zipalign':'PASS','manifest_assertions':'PASS','runtime':'NOT RUN by APK adaptation agent','app_update_policy_tests':'PASS: stock check/download isolation, weather/shared helper preservation, settings boundary validation','original_unchanged':sha(a.original)==profile.original_sha256})
report.update({'headswap_compatibility':'Capability bit 5 supplies the decision for the selected package. Bit 4 without bit 5 retains legacy Yandex-only behavior. Older gateways retain code301 geometry.',
               'map_render_profile':{'packages':'host-selected (capability bit 5)', 'legacy_packages':['ru.yandex.yandexnavi','ru.yandex.yandexmaps'],'reference_scale':2,'reference_parent_dpi':160,'scale_formula':'Exact session DPI by default. Optional compact mode uses shared DesktopRenderPolicy.','max_width':3840,'max_height':2160,'max_density':1280,
                                           'parent_video':'selected bounded native geometry, 640..1920 x 480..1080, DPI 96..640; independent of child supersampling','touch':'existing v7 float render/view transform, no second scale'},
               'geometry_policy_tests':'PASS: 160DPI reference, proportional map sizing at 128/200DPI, unchanged Android dp scale, buffer limits, legacy capabilities and explicit rejection','external_gateway_version':1,'capabilities_bit_0':'bounded high-resolution child displays'})
report.update({'lifecycle_and_touch_tests':'PASS: stale resume/resize generations, retired Texture handoff after exact producer ACK, retry coalescing/backoff, explicit Surface ACK, copied two-pointer packets',
               'capabilities_bit_1':'two-pointer child touch; older gateways retain primary-touch routing',
               'capabilities_bit_2':'empty producer lifetime Binder; its death is producer-release proof',
               'capabilities_bit_3':'FullHD parent layout, DPI 96..640; child buffers at most 3840x2160, DPI up to 1280',
               'capabilities_bit_4':'desktopRenderConfig(parentDisplayId) provides authoritative logical DPI and compact map selection',
               'capabilities_bit_5':'appRenderConfig(parentDisplayId, packageName) returns the selected package correction and desktop DPI',
               'resize_retry_delays_ms':[250,1000,3000], 'release_retry_delays_ms':[500,1500,5000],
               'surface_cleanup':'Exact original gateway release ACK or empty producer-lifetime Binder death; gateway death/timeout alone never releases a possibly active consumer'})
report['source_sha256']={str(source.relative_to(base)):sha(source) for source in sorted(base.rglob('*'))
                         if source.is_file() and '__pycache__' not in source.parts and source.suffix in ('.java','.py','.aidl','.md','.sh')}
report['embedded_host'] = {'package':host_package, 'component':'com.headswap.desk.embed.EmbeddedDisplayService',
                           'binder_descriptor':'com.headswap.desk.embed.IEmbeddedDisplayGateway',
                           'permission':host_package+'.permission.EMBED_DISPLAY', 'compiled_endpoints':'PASS'}
(work/'patch-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print('ARTIFACT',final)
print('SHA256',sha(final))
