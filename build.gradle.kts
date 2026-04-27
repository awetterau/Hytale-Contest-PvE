plugins {
    id("java")
}

group = "dev.hytalemodding"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(files("libs/HytaleServer.jar"))
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    destinationDirectory.set(file("D:\\Program Files\\Hytale\\UserData\\Mods"))

    from("src/main/resources")
}