#!/usr/bin/env python3
"""From an extracted Profile SDK, create a standalone, single-APK Android project."""
import argparse
import re
import shutil
import tempfile
import unicodedata
from pathlib import Path
from xml.etree import ElementTree as ET

ANDROID = 'http://schemas.android.com/apk/res/android'
KEY = '{' + ANDROID + '}name'
ET.register_namespace('android', ANDROID)
AAR = 'headswap-desktop-profile-sdk-v1-dev.aar'
PACKAGE = 'com.example.headswap.panel'
JAVA_KEYWORDS = set(('abstract assert boolean break byte case catch char class const continue default do double else enum '
    'extends final finally float for goto if implements import instanceof int interface long native new package private '
    'protected public return short static strictfp super switch synchronized this throw throws transient try void volatile '
    'while true false null _').split())


def run():
    a = argparse.ArgumentParser(description=__doc__)
    a.add_argument('directory', type=Path, help='new directory (must not exist)')
    a.add_argument('--package', required=True, help='your unique reverse-DNS application ID')
    a.add_argument('--mode', required=True, help='stable public HeadSwap profile ID')
    a.add_argument('--name', required=True, help='display name shown to the user')
    o = a.parse_args()
    if not re.fullmatch(r'[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+', o.package) or any(x in JAVA_KEYWORDS for x in o.package.split('.')):
        a.error('use a reverse-DNS package such as org.acme.cockpit (lower-case Java identifiers)')
    if not re.fullmatch(r'[a-z0-9][a-z0-9._-]{0,63}', o.mode): a.error('invalid mode ID')
    if not o.name.strip() or len(o.name) > 80 or any(unicodedata.category(c).startswith('C') for c in o.name): a.error('invalid display name')
    kit = Path(__file__).resolve().parents[1]
    common = kit/'app/src/main'; starter = kit/'app/src/starter'
    if not (kit/'libs'/AAR).is_file() or not starter.is_dir(): a.error('run the copy of this tool inside the unpacked SDK ZIP')
    dst = o.directory.absolute(); parent = dst.parent
    if dst.exists() or dst.is_symlink() or not parent.is_dir(): a.error('the parent must exist and the destination must be new')
    with tempfile.TemporaryDirectory(prefix='.profile-', dir=parent) as tmp:
        d = Path(tmp)/'new'; d.mkdir()
        for f in ('gradlew','gradlew.bat','gradle/wrapper/gradle-wrapper.jar','gradle/wrapper/gradle-wrapper.properties','LICENSE'):
            to = d/f; to.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(kit/f,to)
        (d/'gradlew').chmod(0o755)
        (d/'settings.gradle.kts').write_text((kit/'settings.gradle.kts').read_text(encoding='utf-8').split('rootProject.name')[0] + 'rootProject.name = "desktop-profile"\ninclude(":app")\n',encoding='utf-8')
        libs=d/'app/libs';libs.mkdir(parents=True);shutil.copy2(kit/'libs'/AAR,libs/AAR)
        src=d/'app/src/main';src.mkdir(parents=True);shutil.copytree(common/'res',src/'res');shutil.copytree(starter/'res',src/'res',dirs_exist_ok=True)
        tree=ET.parse(common/'AndroidManifest.xml'); activity=tree.find('application/activity');overlay=ET.parse(starter/'AndroidManifest.xml').find('application/activity'); assert activity is not None and overlay is not None and activity.get(KEY)==overlay.get(KEY)
        activity.attrib.update(overlay.attrib)
        for child in overlay: activity.append(child)
        for meta in activity.findall('meta-data'):
            if meta.get(KEY)=='com.headswap.desk.component.MODE_ID': meta.set('{'+ANDROID+'}value',o.mode)
        tree.write(src/'AndroidManifest.xml', encoding='utf-8',xml_declaration=True)
        sfile=src/'res/values/strings.xml';strings=ET.parse(sfile);name=strings.find("string[@name='app_name']");assert name is not None
        name.set('formatted','false');name.text='"'+o.name.strip().replace('\\','\\\\').replace('"','\\"')+'"';strings.write(sfile,encoding='utf-8',xml_declaration=True)
        files=list((starter/'java').rglob('*.java'));assert files
        for f in files:
            relative=f.relative_to(starter/'java').as_posix().replace(PACKAGE.replace('.','/'),o.package.replace('.','/'),1);out=src/'java'/relative;out.parent.mkdir(parents=True,exist_ok=True);out.write_text(f.read_text(encoding='utf-8').replace(PACKAGE,o.package),encoding='utf-8')
        (d/'app/build.gradle.kts').write_text('''plugins { id("com.android.application") }
android {
    namespace = "%s"
    compileSdk = 37
    defaultConfig { applicationId = "%s"; minSdk = 33; targetSdk = 37; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation(files("libs/%s")) }
''' % (o.package,o.package,AAR),encoding='utf-8')
        (d/'.gitignore').write_text('.gradle/\n**/build/\nlocal.properties\n*.keystore\n*.jks\n',encoding='utf-8')
        shutil.copy2(kit/'DEVELOPER-GUIDE.md',d/'DEVELOPER-GUIDE.md')
        if dst.exists() or dst.is_symlink(): a.error('destination appeared during generation')
        d.rename(dst)
    print('Created:', dst, '\nBuild: ./gradlew :app:assembleDebug  (Windows: gradlew.bat :app:assembleDebug)')

if __name__ == '__main__': run()
