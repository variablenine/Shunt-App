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
