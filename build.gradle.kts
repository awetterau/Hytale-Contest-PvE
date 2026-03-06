/**
 * NOTE: This is entirely optional and basics can be done in `settings.gradle.kts`
 */

repositories {
    // Any external repositories besides: MavenLocal, MavenCentral, HytaleMaven, and CurseMaven
}

dependencies {
    // Any external dependency you also want to include
}

val devModsRoot = layout.buildDirectory.dir("devmods")
val devModPackDir = layout.buildDirectory.dir("devmods/ExamplePlugin")

val prepareDevModLayout by tasks.registering(Copy::class) {
    into(devModPackDir)
    from("src/main/resources/Server") {
        into("Server")
    }
    if (file("src/main/resources/Common").exists()) {
        from("src/main/resources/Common") {
            into("Common")
        }
    }
    if (file("src/main/resources/manifest.json").exists()) {
        from("src/main/resources/manifest.json")
    }
}

tasks.named<JavaExec>("runServer") {
    dependsOn(prepareDevModLayout)
    doFirst {
        val modsPath = devModsRoot.get().asFile.absolutePath
        val current = args?.toMutableList() ?: mutableListOf()
        var replaced = false
        for (i in current.indices) {
            if (current[i].startsWith("--mods=")) {
                current[i] = "--mods=$modsPath"
                replaced = true
            }
        }
        if (!replaced) {
            current.add("--mods=$modsPath")
        }
        setArgs(current)
        println("runServer effective mods path: $modsPath")
    }
}
