plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

application {
    // §6.2: "Clone the repo and produce the eval card from the corpus — ONE
    // documented command, under 10 minutes."  That command is:
    //   ./gradlew :eval:run --args="--corpus corpus/ --out eval-card.md"
    mainClass.set("com.onetake.eval.MainKt")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":engine-fixtures"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test { useJUnit() }
