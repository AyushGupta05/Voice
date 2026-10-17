// P49 bootstrap (no ij.* references before LegacyInjector.preinit(); see P42).
// Run: fiji-windows-x64.exe --java-home=<bundled JDK> --allow-multiple --main-class=groovy.ui.GroovyMain <this file>
net.imagej.patcher.LegacyInjector.preinit()
def body = new File(new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile, "P49-alternative-ui-registration-body.groovy")
new GroovyShell(this.class.classLoader).evaluate(body)
