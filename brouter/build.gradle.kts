// Vendored BRouter routing core (btools.*), MIT-licensed — see brouter/LICENSE
// and brouter/README.md. Pure Java, zero external dependencies, so it embeds
// cleanly on Android (the official BRouter app uses this same RoutingEngine API).
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The routing profile and tag dictionary have exactly one home:
// `app/src/main/assets/brouter/`, because that is where Android's AssetManager
// reads them from on a phone. This republishes those same bytes on the JVM
// classpath under `brouter-data/`, which is where `BrouterAssets` looks in
// tests and where `RealWorldPlanningBenchmark` gets its profile.
//
// It used to be a second checked-in copy, which is a trap: the two can drift,
// and when they do the benchmark measures — and the tests vouch for — a profile
// no user is running. Copying makes that impossible rather than merely
// detectable.
val bundledBrouterData by tasks.registering(Copy::class) {
    from(rootProject.file("app/src/main/assets/brouter"))
    into(layout.buildDirectory.dir("brouter-data/brouter-data"))
}

sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("brouter-data"))

tasks.named("processResources") { dependsOn(bundledBrouterData) }
