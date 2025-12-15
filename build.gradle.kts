plugins {
    id("java")
    id("maven-publish")
}

group = "com.navapbc.piqi"
version = "0.2.2"

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation("com.navapbc.piqi:piqi-model:0.2.0")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-base:8.4.0")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:8.4.0")
    implementation("javax.validation:validation-api:2.0.1.Final")
    implementation("jakarta.validation:jakarta.validation-api:3.0.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("joda-time:joda-time:2.2")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "$group"
            artifactId = "piqi-map"
            version = "$version"

            from(components["java"])
            artifact(tasks.named("sourcesJar").get())
        }
    }
}
