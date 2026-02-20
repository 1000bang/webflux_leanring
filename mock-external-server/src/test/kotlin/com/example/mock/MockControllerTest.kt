package com.example.mock

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import java.time.Duration

// ============================================================
// MockController 통합 테스트
// 실제 Spring WebFlux 서버를 시작하여 엔드포인트를 검증합니다.
//
// 학습 포인트: Mono.delay()가 실제로 논블로킹임을 응답 시간으로 확인
// ============================================================
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisplayName("Mock 외부 서버 API 테스트")
class MockControllerTest(@Autowired val webTestClient: WebTestClient) {

    @Test
    @Order(1)
    @DisplayName("사용자 API: 1초 지연 후 정상 응답 (200 OK) 반환 검증")
    fun `사용자 API 정상 응답 테스트`() {
        println("\n" + "=".repeat(60))
        println("사용자 API 테스트: GET /user")
        println("  예상: 1초 지연 후 200 OK 응답")
        println("=".repeat(60))

        val startTime = System.currentTimeMillis()

        webTestClient
            .mutate()
            .responseTimeout(Duration.ofSeconds(10)) // 긴 타임아웃 설정
            .build()
            .get()
            .uri("/user")
            .exchange()
            .expectStatus().isOk
            .expectBody<Map<String, Any>>()
            .consumeWith { result ->
                val elapsed = System.currentTimeMillis() - startTime
                val body = result.responseBody!!

                println("  응답 시간: ${elapsed}ms")
                println("  응답 데이터: $body")

                // 응답 데이터 검증
                assertThat(body["name"])
                    .withFailMessage("사용자 이름이 '홍길동'이어야 합니다. 실제: ${body["name"]}")
                    .isEqualTo("홍길동")

                assertThat(body["message"].toString())
                    .withFailMessage("응답 메시지에 '사용자 정보 조회 성공'이 포함되어야 합니다.")
                    .contains("사용자 정보 조회 성공")

                // 지연 시간 검증 (논블로킹 Mono.delay 확인)
                assertThat(elapsed)
                    .withFailMessage(
                        "사용자 API는 최소 1초의 지연이 있어야 합니다. (Mono.delay 논블로킹 지연)\n" +
                            "  실제 응답 시간: ${elapsed}ms"
                    )
                    .isGreaterThanOrEqualTo(1000L)

                println("  검증 완료: 응답 시간 ${elapsed}ms ≥ 1000ms (Mono.delay 논블로킹 지연 확인)")
            }
    }

    @Test
    @Order(2)
    @DisplayName("결제 API fail=true: 즉시 HTTP 500 오류 반환 검증")
    fun `결제 API 강제 실패 시 500 오류 반환 테스트`() {
        println("\n" + "=".repeat(60))
        println("결제 API 실패 테스트: GET /payment?fail=true")
        println("  예상: 즉시 HTTP 500 Internal Server Error")
        println("  목적: 서킷브레이커 학습 - 실패 카운트 증가용")
        println("=".repeat(60))

        val startTime = System.currentTimeMillis()

        webTestClient
            .get()
            .uri("/payment?fail=true")
            .exchange()
            .expectStatus().is5xxServerError
            .expectBody<Map<String, String>>()
            .consumeWith { result ->
                val elapsed = System.currentTimeMillis() - startTime
                val body = result.responseBody!!

                println("  응답 시간: ${elapsed}ms (즉시 응답)")
                println("  응답 데이터: $body")

                // 오류 응답 검증
                assertThat(body["error"])
                    .withFailMessage("오류 응답에 '결제 처리 실패' 메시지가 포함되어야 합니다.")
                    .isEqualTo("결제 처리 실패")

                assertThat(body["status"])
                    .withFailMessage("오류 상태 코드가 '500'이어야 합니다.")
                    .isEqualTo("500")

                // 빠른 응답 검증 (강제 실패는 지연 없이 즉시 응답)
                assertThat(elapsed)
                    .withFailMessage(
                        "fail=true 응답은 즉시(500ms 미만) 반환되어야 합니다.\n" +
                            "  실제 응답 시간: ${elapsed}ms"
                    )
                    .isLessThan(500L)

                println("  검증 완료: HTTP 500 즉시 반환 (${elapsed}ms)")
            }
    }

    @Test
    @Order(3)
    @DisplayName("주문 API: 2초 지연 후 정상 응답 (200 OK) 반환 검증")
    fun `주문 API 정상 응답 테스트`() {
        println("\n" + "=".repeat(60))
        println("주문 API 테스트: GET /order")
        println("  예상: 2초 지연 후 200 OK 응답")
        println("=".repeat(60))

        val startTime = System.currentTimeMillis()

        webTestClient
            .mutate()
            .responseTimeout(Duration.ofSeconds(10))
            .build()
            .get()
            .uri("/order")
            .exchange()
            .expectStatus().isOk
            .expectBody<Map<String, Any>>()
            .consumeWith { result ->
                val elapsed = System.currentTimeMillis() - startTime
                val body = result.responseBody!!

                println("  응답 시간: ${elapsed}ms")
                println("  응답 데이터: $body")

                assertThat(body["orderId"])
                    .withFailMessage("주문 ID가 응답에 포함되어야 합니다.")
                    .isEqualTo("ORDER-2024-001")

                assertThat(body["message"].toString())
                    .withFailMessage("응답 메시지에 '주문 정보 조회 성공'이 포함되어야 합니다.")
                    .contains("주문 정보 조회 성공")

                assertThat(elapsed)
                    .withFailMessage(
                        "주문 API는 최소 2초의 지연이 있어야 합니다. (Mono.delay 논블로킹 지연)\n" +
                            "  실제 응답 시간: ${elapsed}ms"
                    )
                    .isGreaterThanOrEqualTo(2000L)

                println("  검증 완료: 응답 시간 ${elapsed}ms ≥ 2000ms (Mono.delay 논블로킹 지연 확인)")
            }
    }
}
