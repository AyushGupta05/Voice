#@ String (value="fromParam") name
// P36: is a '#@' script-parameter line accepted when this .ijm runs through the IJ1 -macro path, and through --run (SciJava)?
// Run A: fiji-windows-x64.exe --java-home=<jdk> --allow-multiple --headless -macro <this file> ARG
// Run B: fiji-windows-x64.exe --java-home=<jdk> --allow-multiple --headless --run <this file> "name='viaRun'"
// Step 1 writes a marker without touching 'name'; step 2 references 'name'.
out = "C:/Users/arnav/Documents/dev/click-to-batch/docs/01-analysis/references/probes/P36-script-params-macro.out.txt";
prev = "";
if (File.exists(out)) prev = File.openAsString(out);
prev = prev + "step1 ran; argument=[" + getArgument() + "]\n";
File.saveString(prev, out);
File.saveString(prev + "step2 name=[" + name + "]\n", out);
eval("script", "System.exit(0);");
