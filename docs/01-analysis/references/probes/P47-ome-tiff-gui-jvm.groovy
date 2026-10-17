// P47 bootstrap (no ij.* references before LegacyInjector.preinit(); see P42).
// Run: fiji-windows-x64.exe --java-home=<bundled JDK> --allow-multiple --main-class=groovy.ui.GroovyMain <this file>
net.imagej.patcher.LegacyInjector.preinit()
def body = new File(new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile, "P47-ome-tiff-gui-jvm-body.groovy")
new GroovyShell(this.class.classLoader).evaluate(body)
