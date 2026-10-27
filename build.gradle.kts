import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	kotlin("jvm") version "2.3.21"
	`java-library`
	`maven-publish`
}

// JitPack serves it as com.github.tiagoaalves:my-edenred-pt:<tag>; the same coordinates let a composite build substitute it.
group = "com.github.tiagoaalves"
version = "0.1.0"

repositories {
	mavenCentral()
}

dependencies {
	api("com.fasterxml.jackson.core:jackson-databind:2.22.4")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Built with any recent JDK, runs on Java 17+.
java {
	withSourcesJar()
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach { options.release = 17 }

kotlin {
	explicitApi()
	compilerOptions {
		jvmTarget = JvmTarget.JVM_17
		freeCompilerArgs.add("-Xjdk-release=17")
	}
}

tasks.test {
	useJUnitPlatform()
}

publishing {
	publications {
		create<MavenPublication>("maven") {
			from(components["java"])
			pom {
				name = "my-edenred-pt"
				description = "Unofficial Kotlin/Java client for MyEdenred Portugal (meal card balance and movements). Not affiliated with Edenred."
				url = "https://github.com/tiagoaalves/my-edenred-pt"
				licenses {
					license {
						name = "MIT"
						url = "https://opensource.org/licenses/MIT"
					}
				}
			}
		}
	}
}
