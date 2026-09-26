plugins {
    id("inmc.paper-plugin")
}

group = "com.inmc.numbergame"
version = "1.0.0"

inmc {
    paper = "26.1.2"
    pluginName = "inmc-numbergame"
}

dependencies {
    compileOnly(libs.placeholderapi) { isTransitive = false }
    compileOnly(libs.vault.api) { isTransitive = false }
    // MMOItems / MythicLib / ItemsAdder 등은 100% 리플렉션.
}
