// P42 bootstrap - must not reference any ij.* class (see ij1-patcher README: patching has to
// happen before ImageJ 1.x classes are loaded). First attempt without this bootstrap failed with
// "Failed to patch ImageJ, because an ImageJ class was already loaded" (recorded in P42 README row).
// Run: fiji-windows-x64.exe --java-home=<bundled JDK> --allow-multiple --main-class=groovy.ui.GroovyMain <this file>
net.imagej.patcher.LegacyInjector.preinit()
def body = new File(new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile, "P42-gui-jvm-hidden-windows-body.groovy")
new GroovyShell(this.class.classLoader).evaluate(body)
