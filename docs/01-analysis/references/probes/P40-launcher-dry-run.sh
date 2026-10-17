#!/usr/bin/env bash
# P40 - Jaunch launcher dry runs: shows the exact JVM command line for the GUI, headless and
# custom-main-class launch modes of the pinned Fiji. --dry-run starts no JVM.
cd "C:/Users/arnav/Documents/dev/Fiji"
JH="C:\Users\arnav\Documents\dev\Fiji\java\win64\zulu21.42.19-ca-jdk21.0.7-win_x64"
for MODE in "" "--headless" "--main-class=groovy.ui.GroovyMain"; do
  echo "### fiji-windows-x64.exe --allow-multiple $MODE --dry-run [--run probe.groovy]  (classpath elided)"
  ./fiji-windows-x64.exe --java-home="$JH" --allow-multiple $MODE --dry-run --run C:/probe.groovy 2>&1 | tr ' ' '\n' | sed -E 's/^(.{0,40}).*\.jar;.*$/\1...<classpath elided>/'
  echo
done
echo "### --help"; ./fiji-windows-x64.exe --help 2>&1
