#!/usr/bin/env python3
"""Download pinned JVM test dependencies into the directory passed as argument."""
import concurrent.futures
from pathlib import Path
import sys
import urllib.request
import zipfile

ARTIFACTS = {
    'compiler.jar': 'org/jetbrains/kotlin/kotlin-compiler-embeddable/2.2.0/kotlin-compiler-embeddable-2.2.0.jar',
    'stdlib.jar': 'org/jetbrains/kotlin/kotlin-stdlib/2.2.0/kotlin-stdlib-2.2.0.jar',
    'script.jar': 'org/jetbrains/kotlin/kotlin-script-runtime/2.2.0/kotlin-script-runtime-2.2.0.jar',
    'reflect.jar': 'org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar',
    'coroutines.jar': 'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar',
    'annotations.jar': 'org/jetbrains/annotations/13.0/annotations-13.0.jar',
    'android-all.jar': 'org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar',
}

def main():
    if len(sys.argv) != 2:
        raise SystemExit('Usage: python3 tests/host/setup.py /path/to/dependency-cache')
    dest = Path(sys.argv[1]).resolve()
    dest.mkdir(parents=True, exist_ok=True)

    def download(item):
        name, path = item
        target = dest / name
        if not target.exists():
            partial = dest / (name + '.partial')
            urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/' + path, partial)
            partial.replace(target)
        return name

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for name in pool.map(download, ARTIFACTS.items()):
            print(name)
    # Use Android's JSON implementation, without loading the Android framework.
    with zipfile.ZipFile(dest / 'android-all.jar') as source:
        with zipfile.ZipFile(dest / 'json.jar', 'w', zipfile.ZIP_DEFLATED) as output:
            for name in source.namelist():
                if name.startswith('org/json/') and name.endswith('.class'):
                    output.writestr(name, source.read(name))

if __name__ == '__main__':
    main()
