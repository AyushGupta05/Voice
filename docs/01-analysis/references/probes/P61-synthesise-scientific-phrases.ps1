# P61 (part 1): synthesise scientific-vocabulary voice clips with Windows System.Speech.
# Usage: powershell -NoProfile -File P61-synthesise-scientific-phrases.ps1 <outdir>
# Synthetic TTS speech only (same method as fixtures/invert-command.wav); NOT representative of human speech.
param([Parameter(Mandatory=$true)][string]$OutDir)
Add-Type -AssemblyName System.Speech
New-Item -ItemType Directory -Force $OutDir | Out-Null
$phrases = [ordered]@{
  "s01" = "Apply a Gaussian blur with sigma two point five."
  "s02" = "Threshold the image using Otsu, then convert to mask."
  "s03" = "Measure the mean intensity inside the ROI."
  "s04" = "Run a median filter with radius three on channel two."
  "s05" = "Subtract background with a rolling ball radius of fifty pixels."
  "s06" = "Make a maximum intensity Z projection of the stack."
  "s07" = "Set the scale to zero point one five microns per pixel."
  "s08" = "Analyze particles with size one hundred to infinity and exclude on edges."
}
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
foreach ($k in $phrases.Keys) {
  $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
  $s.SetOutputToWaveFile((Join-Path $OutDir "$k.wav"), $fmt)
  $s.Speak($phrases[$k])
  $s.Dispose()
  "$k`t$($phrases[$k])"
}
$v = (New-Object System.Speech.Synthesis.SpeechSynthesizer).Voice
"voice`t$($v.Name)`t$($v.Culture)"
