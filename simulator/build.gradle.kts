plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
dependencies {
    implementation(project(":domain"))
    implementation(project(":pipeline"))
    implementation(project(":crypto"))
    testImplementation("junit:junit:4.13.2")
}
application { mainClass.set("org.cascade.simulator.MainKt") }

java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
