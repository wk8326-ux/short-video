"""Exercise an installed, minified APK through accessibility and verify rendered test-pattern frames.

Requires the tvbox_cms.py fixture, adb reverse and Pillow. Use only a disposable emulator.
Example: python android/device-tests/release_ui.py --kind jar --output .deploy/staging/type3/release-jar
"""
import argparse
import io
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image, ImageChops, ImageStat

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='C:/platform-tools/adb.exe')
parser.add_argument('--kind', choices=['jar', 'js', 'js-proxy', 'jar-proxy', 'js-sniff', 'global-js', 'drpy'], required=True)
parser.add_argument('--base', default='http://127.0.0.1:28765')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)

def adb(*command, binary=False):
    value = subprocess.check_output([args.adb, *command], timeout=40)
    return value if binary else value.decode('utf-8', errors='replace')

def tree():
    for attempt in range(3):
        try:
            adb('shell', 'uiautomator', 'dump', '/sdcard/tvbox-release-ui.xml')
            return ET.fromstring(adb('shell', 'cat', '/sdcard/tvbox-release-ui.xml'))
        except (subprocess.SubprocessError, ET.ParseError):
            if attempt == 2:
                raise
            time.sleep(2)

def wait_node(predicate, timeout=35):
    deadline = time.monotonic()+timeout
    while time.monotonic()<deadline:
        root=tree()
        match=next((node for node in root.iter('node') if predicate(node)), None)
        if match is not None: return match
        time.sleep(.25)
    (args.output/'last-ui.xml').write_bytes(ET.tostring(root, encoding='utf-8'))
    raise AssertionError('UI target missing; see last-ui.xml')

def click(node):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

def text(value):
    return wait_node(lambda node: node.get('text')==value or node.get('content-desc')==value)

def screenshot(name):
    image=Image.open(io.BytesIO(adb('exec-out','screencap','-p',binary=True))).convert('RGB')
    image.save(args.output/name)
    return image

adb('shell','am','force-stop','you.deepfuck.shortvideo')
adb('shell','am','start','-n','you.deepfuck.shortvideo/.TvBoxActivity')
click(text('片源'))
edit=wait_node(lambda node: node.get('class')=='android.widget.EditText')
click(edit)
adb('shell','input','keycombination','KEYCODE_CTRL_LEFT','KEYCODE_A')
adb('shell','input','keyevent','KEYCODE_DEL')
config_url=args.base+'/'+args.kind+'-config.json'
adb('shell','input','text',config_url)
adb('shell','input','keyevent','KEYCODE_BACK')
click(text('添加片源'))
source='JAR test' if args.kind.startswith('jar') else 'drpy2 test' if args.kind=='drpy' else 'JS test'
wait_node(lambda node: node.get('class')!='android.widget.EditText' and config_url in node.get('text',''),timeout=40)
click(text('电影'))
# Open the current source selector by its accessibility label.
root=tree()
(args.output/'wall-ui.xml').write_bytes(ET.tostring(root,encoding='utf-8'))
selector=next((node for node in root.iter('node') if node.get('content-desc')=='切换媒体源'), None)
if selector is None:
    selector=next((node for node in root.iter('node') if node.get('text') in ['155api','森林','番号资源','辣椒','奶香',source]), None)
if selector is None: raise AssertionError('Source selector missing')
click(selector)
click(text(source))
title='JAR plugin playback test' if args.kind.startswith('jar') else 'drpy test film' if args.kind=='drpy' else 'Global script' if args.kind=='global-js' else 'JavaScript Promise playback test'
movie=wait_node(lambda node: title in node.get('text','') or title in node.get('content-desc',''))
click(movie)
click(text('Sample'))
wait_node(lambda node: node.get('content-desc')=='暂停',timeout=40)
time.sleep(2)
first=screenshot('playing-1.png')
time.sleep(2)
# Show player controls if they automatically hid.
width,height=first.size
adb('shell','input','tap',str(width//2),str(height//2))
second=screenshot('playing-2.png')
region=(0,int(height*.27),width,int(height*.65))
a,b=first.crop(region),second.crop(region)
color=ImageStat.Stat(b).stddev
movement=sum(ImageStat.Stat(ImageChops.difference(a,b)).mean)/3
assert min(color)>25, f'No colored video frame: {color}'
assert movement>1, f'Video frame did not change: {movement}'
root=tree()
assert not any('播放失败' in node.get('text','') for node in root.iter('node'))
result={'kind':args.kind,'renderedColoredFrames':True,'changedFrames':True,'frameDifference':movement,'channelStddev':color}
(args.output/'result.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result))
