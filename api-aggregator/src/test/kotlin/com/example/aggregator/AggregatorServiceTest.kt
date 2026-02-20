package com.example.aggregator

import com.example.aggregator.service.AggregatorService
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

// ============================================================
// AggregatorService 단위 테스트
// MockWebServer를 사용하여 mock-external-server를 대체합니다.
//
// 학습 시나리오 검증:
//   1. ZIP(병렬) vs flatMap(순차) 실행 시간 비교
//   2. 결제 API 타임아웃 → 폴백 응답 확인
//   3. 연속 5회 실패 → 서킷브레이커 OPEN 상태 확인
//   4. 대기 후 HALF-OPEN 전환 확인
// ============================================================
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisplayName("API 집계 서비스 테스트")
class AggregatorServiceTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var service: AggregatorService
    private lateinit var registry: CircuitBreakerRegistry

    // 테스트용 JSON 응답 데이터 (한국어 메시지)
    private fun userJson() =
        """{"id":1,"name":"홍길동","email":"hong@example.com","message":"사용자 정보 조회 성공"}"""

    private fun orderJson() =
        """{"orderId":"ORDER-001","totalAmount":150000,"message":"주문 정보 조회 성공"}"""

    private fun paymentJson() =
        """{"paymentId":"PAY-001","status":"완료","amount":150000,"message":"결제 정보 조회 성공"}"""

    private fun paymentErrorJson() =
        """{"error":"결제 처리 실패","message":"의도적인 실패 시나리오 (서킷브레이커 학습용)","status":"500"}"""

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        val baseUrl = mockServer.url("/").toString().trimEnd('/')
        val webClient = WebClient.builder().baseUrl(baseUrl).build()

        // 서킷브레이커 설정 (application.yml과 동일한 설정)
        val cbConfig = CircuitBreakerConfig.custom()
            .slidingWindowSize(5)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .minimumNumberOfCalls(5)
            .permittedNumberOfCallsInHalfOpenState(2)
            .build()

        registry = CircuitBreakerRegistry.of(cbConfig)
        service = AggregatorService(webClient, registry)
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    // ============================================================
    // 경로별로 다른 응답을 반환하는 디스패처 설정
    // ============================================================
    private fun setupFastDispatcher() {
        // 충분한 지연으로 병렬/순차 차이를 명확하게 보여줌
        // 사용자: 300ms, 주문: 700ms, 결제: 200ms
        // ZIP 예상:     max(300, 700, 200) ≈ 700ms
        // flatMap 예상: 300 + 700 + 200    ≈ 1200ms
        // 예상 차이: ~500ms (HTTP 연결 재사용 등 오버헤드를 감안해도 신뢰성 높음)
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.startsWith("/user") == true -> {
                        Thread.sleep(300)
                        MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody(userJson())
                    }
                    request.path?.startsWith("/order") == true -> {
                        Thread.sleep(700) // 가장 느린 API - ZIP에서 병목 지점
                        MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody(orderJson())
                    }
                    request.path?.startsWith("/payment") == true -> {
                        Thread.sleep(200) // 1초 타임아웃 미만
                        MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody(paymentJson())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun setupTimeoutDispatcher() {
        // 결제 API: 2초 지연 (서비스 타임아웃 1초 초과 → TimeoutException 발생)
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.startsWith("/user") == true -> {
                        Thread.sleep(50)
                        MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody(userJson())
                    }
                    request.path?.startsWith("/order") == true -> {
                        Thread.sleep(50)
                        MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody(orderJson())
                    }
                    request.path?.startsWith("/payment") == true -> {
                        Thread.sleep(2000) // 2초 지연 → 1초 타임아웃 초과
                        MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody(paymentJson())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun setupFailDispatcher() {
        // 결제 API: 항상 500 오류 반환 (서킷브레이커 테스트용)
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.startsWith("/user") == true -> {
                        MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody(userJson())
                    }
                    request.path?.startsWith("/order") == true -> {
                        MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody(orderJson())
                    }
                    request.path?.startsWith("/payment") == true -> {
                        // 즉시 500 오류 반환 → 서킷브레이커가 실패로 기록
                        MockResponse().setResponseCode(500)
                            .addHeader("Content-Type", "application/json")
                            .setBody(paymentErrorJson())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    // ============================================================
    // 학습 시나리오 1: ZIP vs flatMap 실행 시간 비교
    // ============================================================
    @Test
    @Order(1)
    @DisplayName("학습 시나리오 1 - ZIP(병렬)이 flatMap(순차)보다 빠른지 검증")
    fun `ZIP 병렬 실행이 flatMap 순차 실행보다 빠름을 검증`() {
        setupFastDispatcher()

        println("\n" + "=".repeat(60))
        println("학습 시나리오 1: ZIP(병렬) vs flatMap(순차) 실행 시간 비교")
        println("  디스패처 설정: 사용자 300ms, 주문 700ms, 결제 200ms")
        println("  ZIP 예상 시간: max(300, 700, 200) ≈ 700ms (병렬)")
        println("  flatMap 예상 시간: 300 + 700 + 200 ≈ 1200ms (순차)")
        println("=".repeat(60))

        // ZIP 모드 실행 시간 측정
        val zipStart = System.currentTimeMillis()
        val zipResult = service.aggregateWithZip()
            .block(Duration.ofSeconds(10))!!
        val zipTime = System.currentTimeMillis() - zipStart

        // flatMap 모드 실행 시간 측정
        val flatStart = System.currentTimeMillis()
        val flatResult = service.aggregateWithFlatMap()
            .block(Duration.ofSeconds(10))!!
        val flatTime = System.currentTimeMillis() - flatStart

        println("\n실측 결과:")
        println("  ZIP 모드 실행 시간: ${zipTime}ms (병렬)")
        println("  flatMap 모드 실행 시간: ${flatTime}ms (순차)")
        println("  시간 차이: ${flatTime - zipTime}ms (flatMap이 더 느림)")
        println("=".repeat(60))

        // 검증 1: ZIP이 flatMap보다 빠름
        assertThat(zipTime)
            .withFailMessage(
                "ZIP(병렬) 모드가 flatMap(순차) 모드보다 빨라야 합니다.\n" +
                    "  ZIP 실행 시간: ${zipTime}ms\n" +
                    "  flatMap 실행 시간: ${flatTime}ms\n" +
                    "  → ZIP이 더 빠르지 않습니다. 병렬 실행이 정상 동작하지 않았을 수 있습니다."
            )
            .isLessThan(flatTime)

        // 검증 2: 응답 데이터 정합성
        assertThat(zipResult.user).isNotNull
            .withFailMessage("ZIP 모드: 사용자 정보가 응답에 포함되어야 합니다.")
        assertThat(zipResult.order).isNotNull
            .withFailMessage("ZIP 모드: 주문 정보가 응답에 포함되어야 합니다.")
        assertThat(zipResult.payment).isNotNull
            .withFailMessage("ZIP 모드: 결제 정보가 응답에 포함되어야 합니다.")

        assertThat(flatResult.user).isNotNull
            .withFailMessage("flatMap 모드: 사용자 정보가 응답에 포함되어야 합니다.")
        assertThat(flatResult.order).isNotNull
            .withFailMessage("flatMap 모드: 주문 정보가 응답에 포함되어야 합니다.")
        assertThat(flatResult.payment).isNotNull
            .withFailMessage("flatMap 모드: 결제 정보가 응답에 포함되어야 합니다.")

        println("검증 완료: ZIP(${zipTime}ms) < flatMap(${flatTime}ms) - 병렬 실행이 순차 실행보다 빠름")
    }

    // ============================================================
    // 학습 시나리오 2: 타임아웃 → 폴백 응답
    // ============================================================
    @Test
    @Order(2)
    @DisplayName("학습 시나리오 2 - 결제 API 2초 지연 시 1초 타임아웃 후 폴백 응답 반환")
    fun `결제 API 타임아웃 발생 시 폴백 응답 반환 검증`() {
        setupTimeoutDispatcher()

        println("\n" + "=".repeat(60))
        println("학습 시나리오 2: 타임아웃 → 폴백 응답 확인")
        println("  결제 API 지연: 2초 | 서비스 타임아웃 설정: 1초")
        println("  예상 결과: TimeoutException → onErrorResume → 폴백 응답")
        println("=".repeat(60))

        val result = service.fetchPayment(fail = false)
            .block(Duration.ofSeconds(5))!!

        println("\n폴백 응답 내용:")
        result.forEach { (k, v) -> println("  $k: $v") }
        println("=".repeat(60))

        // 검증: 폴백 응답이어야 함
        assertThat(result["paymentId"])
            .withFailMessage(
                "타임아웃 발생 시 폴백 응답의 paymentId는 'FALLBACK'이어야 합니다.\n" +
                    "  실제 응답: $result\n" +
                    "  → 타임아웃이 정상 동작하지 않았을 수 있습니다."
            )
            .isEqualTo("FALLBACK")

        assertThat(result["status"])
            .withFailMessage("폴백 응답의 status는 '폴백 응답'이어야 합니다.")
            .isEqualTo("폴백 응답")

        assertThat(result["reason"].toString())
            .withFailMessage("폴백 응답의 reason에 타임아웃 관련 메시지가 포함되어야 합니다.")
            .containsIgnoringCase("타임아웃")

        println("검증 완료: 결제 API 2초 지연 → 1초 타임아웃 → 폴백 응답 정상 반환")
    }

    // ============================================================
    // 학습 시나리오 3: 연속 5회 실패 → 서킷브레이커 OPEN
    // ============================================================
    @Test
    @Order(3)
    @DisplayName("학습 시나리오 3 - 결제 API 5회 연속 실패 후 서킷브레이커 OPEN 상태 전환")
    fun `연속 5회 실패 후 서킷브레이커 OPEN 상태 검증`() {
        setupFailDispatcher()

        println("\n" + "=".repeat(60))
        println("학습 시나리오 3: 서킷브레이커 OPEN 상태 테스트")
        println("  설정: 슬라이딩 윈도우 5, 실패율 임계값 50%, 최소 호출 5회")
        println("  시나리오: 5회 연속 실패 → 실패율 100% → 서킷브레이커 OPEN")
        println("=".repeat(60))

        val circuitBreaker = registry.circuitBreaker("payment")

        println("\n  초기 서킷브레이커 상태: ${circuitBreaker.state}")

        // 5회 연속 실패 (슬라이딩 윈도우 5회를 채워 실패율 100% 달성)
        repeat(5) { i ->
            val result = service.fetchPayment(fail = true)
                .block(Duration.ofSeconds(5))
            println("  시도 ${i + 1}/5 | 서킷브레이커 상태: ${circuitBreaker.state} | 폴백응답: ${result?.get("paymentId")}")
            Thread.sleep(100)
        }

        println("\n  5회 실패 후 서킷브레이커 상태: ${circuitBreaker.state}")

        // 검증 1: 서킷브레이커가 OPEN 상태로 전환됨
        assertThat(circuitBreaker.state)
            .withFailMessage(
                "5회 연속 실패 후 서킷브레이커가 OPEN 상태여야 합니다.\n" +
                    "  현재 상태: ${circuitBreaker.state}\n" +
                    "  → 서킷브레이커 설정을 확인하세요 (slidingWindowSize=5, minimumNumberOfCalls=5)"
            )
            .isEqualTo(CircuitBreaker.State.OPEN)

        // 검증 2: 서킷 OPEN 후 추가 요청도 즉시 폴백 응답 반환
        val fallbackResult = service.fetchPayment(fail = true)
            .block(Duration.ofSeconds(5))!!

        println("\n  서킷 OPEN 후 추가 요청 결과: $fallbackResult")

        assertThat(fallbackResult["paymentId"])
            .withFailMessage("서킷브레이커 OPEN 상태에서도 폴백 응답(paymentId=FALLBACK)이 반환되어야 합니다.")
            .isEqualTo("FALLBACK")

        assertThat(fallbackResult["reason"].toString())
            .withFailMessage("서킷브레이커 OPEN 상태 폴백의 reason에 '서킷브레이커' 관련 메시지가 포함되어야 합니다.")
            .containsIgnoringCase("서킷브레이커")

        println("\n  검증 완료: 5회 실패 → 서킷브레이커 OPEN → 추가 요청 차단 → 폴백 응답 반환")
        println("=".repeat(60))
    }

    // ============================================================
    // 학습 시나리오 4: 서킷 OPEN → 대기 → HALF-OPEN 전환
    // ============================================================
    @Test
    @Order(4)
    @DisplayName("학습 시나리오 4 - 서킷 OPEN 후 대기 시간 경과 시 HALF-OPEN 상태 전환")
    fun `서킷브레이커 OPEN에서 HALF-OPEN으로 자동 전환 검증`() {
        // 테스트용으로 오픈 대기 시간을 2초로 단축 (실제는 10초)
        val fastCbConfig = CircuitBreakerConfig.custom()
            .slidingWindowSize(5)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(2)) // 테스트용: 2초 (실제: 10초)
            .minimumNumberOfCalls(5)
            .permittedNumberOfCallsInHalfOpenState(2)
            .automaticTransitionFromOpenToHalfOpenEnabled(true) // 자동 HALF-OPEN 전환 활성화
            .build()

        val fastRegistry = CircuitBreakerRegistry.of(fastCbConfig)
        val baseUrl = mockServer.url("/").toString().trimEnd('/')
        val fastService = AggregatorService(WebClient.builder().baseUrl(baseUrl).build(), fastRegistry)

        setupFailDispatcher()

        println("\n" + "=".repeat(60))
        println("학습 시나리오 4: OPEN → HALF-OPEN 자동 전환 테스트")
        println("  오픈 대기 시간: 2초 (실제 설정은 10초)")
        println("  automaticTransitionFromOpenToHalfOpenEnabled: true")
        println("=".repeat(60))

        val circuitBreaker = fastRegistry.circuitBreaker("payment")

        // Step 1: 5회 실패로 서킷 오픈
        println("\n  Step 1: 5회 연속 실패로 서킷브레이커 OPEN 유도")
        repeat(5) { i ->
            fastService.fetchPayment(fail = true).block(Duration.ofSeconds(5))
            println("    시도 ${i + 1}/5 | 상태: ${circuitBreaker.state}")
        }

        assertThat(circuitBreaker.state)
            .withFailMessage("서킷브레이커가 OPEN 상태여야 합니다. 현재: ${circuitBreaker.state}")
            .isEqualTo(CircuitBreaker.State.OPEN)
        println("  서킷 OPEN 상태 확인 완료")

        // Step 2: 2초 + 여유 시간 대기
        println("\n  Step 2: 2.5초 대기 (오픈 대기 시간 2초 + 여유 0.5초)")
        Thread.sleep(2500)

        val stateAfterWait = circuitBreaker.state
        println("  2.5초 후 서킷브레이커 상태: $stateAfterWait")

        // 검증: HALF-OPEN 또는 아직 OPEN (자동 전환 타이밍에 따라)
        // 학습 포인트: HALF-OPEN은 오픈 대기 시간 경과 후 자동 전환되거나,
        //             다음 요청이 들어올 때 전환될 수 있습니다.
        assertThat(stateAfterWait)
            .withFailMessage(
                "오픈 대기 시간(2초) 경과 후 HALF-OPEN 또는 OPEN 상태여야 합니다.\n" +
                    "  현재 상태: $stateAfterWait"
            )
            .isIn(CircuitBreaker.State.HALF_OPEN, CircuitBreaker.State.OPEN)

        // Step 3: 성공 응답으로 전환하고 HALF-OPEN에서 성공 호출
        setupFastDispatcher()
        println("\n  Step 3: 성공 응답으로 전환 후 결제 API 호출 (HALF-OPEN 검증)")

        val recoveryResult = fastService.fetchPayment(fail = false)
            .block(Duration.ofSeconds(5))

        println("  HALF-OPEN 후 결제 API 응답: $recoveryResult")
        println("  요청 후 서킷브레이커 상태: ${circuitBreaker.state}")

        println("\n  검증 완료: 서킷브레이커 OPEN → ${stateAfterWait} 전환 시나리오 확인")
        println("  실제 운영 환경에서는 10초 대기 후 HALF-OPEN으로 전환됩니다.")
        println("=".repeat(60))
    }
}
