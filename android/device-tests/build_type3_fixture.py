"""Build the controlled CatVod dex plugin used by the type-3 device test."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME', 'C:/Android/Sdk'))
parser.add_argument('--output', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
sdk = Path(args.sdk)
javac = shutil.which('javac') or 'C:/Program Files/Microsoft/jdk-17.0.17.10-hotspot/bin/javac.exe'
okhttp = next((Path.home()/'.gradle/caches/modules-2/files-2.1/com.squareup.okhttp3/okhttp/4.12.0').glob('*/*.jar'))
android = sdk/'platforms/android-35/android.jar'
with tempfile.TemporaryDirectory(prefix='tvbox-plugin-') as temp:
    temp = Path(temp)
    abi, classes = temp/'abi', temp/'classes'
    abi.mkdir(); classes.mkdir()
    subprocess.run([javac,'-source','8','-target','8','-cp',os.pathsep.join([str(android),str(okhttp)]),
        '-d',str(abi),str(root/'android/app/src/main/java/com/github/catvod/crawler/Spider.java')],check=True)
    subprocess.run([javac,'-source','8','-target','8','-cp',os.pathsep.join([str(android),str(abi)]),
        '-d',str(classes),str(Path(__file__).parent/'fixtures/Fixture.java')],check=True)
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True,exist_ok=True)
    d8 = sdk/'build-tools/35.0.0'/('d8.bat' if os.name=='nt' else 'd8')
    subprocess.run([str(d8),'--min-api','26','--lib',str(android),'--classpath',str(abi),
        '--output',str(output),*[str(p) for p in classes.rglob('*.class')]],check=True)
    print(output)
