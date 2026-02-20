// api-aggregator 빌드 스크립트 - 외부 API 집계 서버
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    // Spring WebFlux - 리액티브 웹 프레임워크
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // Resilience4j - 서킷브레이커 라이브러리
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    // Resilience4j Reactor 통합 - transformDeferred(CircuitBreakerOperator) 사용을 위해 필요
    implementation("io.github.resilience4j:resilience4j-reactor:2.2.0")
    // AOP - Resilience4j 어노테이션 기반 사용 시 필요
    implementation("org.springframework.boot:spring-boot-starter-aop")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    // MockWebServer - WebClient 단위 테스트용 목 HTTP 서버
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
