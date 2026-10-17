// P01: minimal headless macro used to time Fiji JVM start-up + macro + exit.
// Writes a marker file (path = macro argument) because headless print() output is not forwarded to stdout.
File.saveString("P01 ok IJ " + getVersion() + " java " + getInfo("java.version") + " headless " + getInfo("java.awt.headless"), getArgument());
eval("script", "System.exit(0);");
