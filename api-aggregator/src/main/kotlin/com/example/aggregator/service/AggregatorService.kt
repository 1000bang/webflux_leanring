package com.example.aggregator.service

import com.example.aggregator.model.AggregateResponse
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeoutException

@Service
class AggregatorService(
    private val webClient: WebClient,
    private val circuitBreakerRegistry: CircuitBreakerRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ============================================================
    // 개별 API 호출 메서드
    // ============================================================

    /**
     * 사용자 API 호출 (mock-external-server: GET /user)
     * mock 서버에서 1초 지연 후 응답
     */
    fun fetchUser(): Mono<Map<String, Any>> {
        val startTime = LocalDateTime.now()
        log.info("[사용자 API 시작] 시각: $startTime | 스레드: ${Thread.currentThread().name}")

        return webClient.get()
            .uri("/user")
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .doOnSuccess {
                log.info("[사용자 API 완료] 시각: ${LocalDateTime.now()} | 스레드: ${Thread.currentThread().name}")
            }
            .doOnError { e ->
                log.error("[사용자 API 실패] 오류: ${e.message} | 스레드: ${Thread.currentThread().name}")
            }
    }

    /**
     * 주문 API 호출 (mock-external-server: GET /order)
     * mock 서버에서 2초 지연 후 응답
     */
    fun fetchOrder(): Mono<Map<String, Any>> {
        val startTime = LocalDateTime.now()
        log.info("[주문 API 시작] 시각: $startTime | 스레드: ${Thread.currentThread().name}")

        return webClient.get()
            .uri("/order")
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .doOnSuccess {
                log.info("[주문 API 완료] 시각: ${LocalDateTime.now()} | 스레드: ${Thread.currentThread().name}")
            }
            .doOnError { e ->
                log.error("[주문 API 실패] 오류: ${e.message} | 스레드: ${Thread.currentThread().name}")
            }
    }

    /**
     * 결제 API 호출 (mock-external-server: GET /payment)
     *
     * 학습 포인트:
     * 1. timeout(1초): 결제 API가 3초 걸리므로 항상 타임아웃 발생
     * 2. transformDeferred(CircuitBreakerOperator): 서킷브레이커가 실패를 기록
     * 3. onErrorResume: 서킷브레이커가 실패를 기록한 후 폴백 응답 반환
     *
     * 연산자 순서가 중요합니다:
     *   timeout → CircuitBreaker(실패 기록) → onErrorResume(폴백)
     */
    fun fetchPayment(fail: Boolean = false): Mono<Map<String, Any>> {
        val circuitBreaker = circuitBreakerRegistry.circuitBreaker("payment")
        val startTime = LocalDateTime.now()

        log.info(
            "[결제 API 시작] 시각: $startTime | 스레드: ${Thread.currentThread().name} | " +
                "서킷브레이커 상태: ${circuitBreaker.state}"
        )

        return webClient.get()
            .uri("/payment?fail=$fail")
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            // 학습 포인트: 1초 타임아웃 - 3초 걸리는 결제 API에서 TimeoutException 발생
            .timeout(Duration.ofSeconds(1))
            // 학습 포인트: 서킷브레이커 적용
            //   - CLOSED: 정상 호출, 실패 시 카운트 증가
            //   - OPEN: 즉시 CallNotPermittedException 발생 (HTTP 요청 자체를 차단)
            //   - HALF-OPEN: 제한된 수의 호출만 허용하여 복구 여부 확인
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .doOnSuccess {
                log.info(
                    "[결제 API 완료] 시각: ${LocalDateTime.now()} | 스레드: ${Thread.currentThread().name} | " +
                        "서킷브레이커 상태: ${circuitBreaker.state}"
                )
            }
            .doOnError { e ->
                log.warn(
                    "[결제 API 실패] 시각: ${LocalDateTime.now()} | 오류 유형: ${e.javaClass.simpleName} | " +
                        "메시지: ${e.message} | 스레드: ${Thread.currentThread().name} | " +
                        "서킷브레이커 상태: ${circuitBreaker.state}"
                )
            }
            // 학습 포인트: 서킷브레이커가 실패를 기록한 후 폴백 응답 반환
            //   - TimeoutException: 1초 타임아웃 초과
            //   - CallNotPermittedException: 서킷브레이커 오픈으로 요청 차단
            //   - 기타 오류: 서버 오류 (500 등)
            .onErrorResume { e ->
                val state = circuitBreaker.state.toString()
                val reason = when {
                    e is CallNotPermittedException ->
                        "서킷브레이커 오픈 상태 - 요청이 차단되었습니다 (현재 상태: $state)"
                    e is TimeoutException || e.cause is TimeoutException ->
                        "요청 타임아웃 (1초 초과) - 결제 API 응답 지연"
                    else ->
                        "서비스 오류: ${e.javaClass.simpleName} - ${e.message}"
                }
                log.warn("[결제 폴백 응답] 원인: $reason | 서킷브레이커 상태: $state")

                Mono.just(
                    mapOf(
                        "paymentId" to "FALLBACK",
                        "status" to "폴백 응답",
                        "reason" to reason,
                        "circuitBreakerState" to state,
                        "message" to "결제 서비스 일시 불가 - 폴백 응답을 반환합니다"
                    )
                )
            }
    }

    // ============================================================
    // ZIP 모드: 병렬 실행
    // ============================================================

    /**
     * Mono.zip을 사용한 병렬 API 집계
     *
     * 학습 포인트:
     * Mono.zip은 여러 Mono를 동시에 구독(subscribe)합니다.
     * 즉, 사용자/주문/결제 API 요청이 거의 동시에 시작됩니다.
     * 총 소요시간 ≈ max(각 API 소요시간) → 가장 느린 API에 맞춰집니다.
     *
     * 실제 mock-external-server 기준:
     *   - 사용자: 1초, 주문: 2초, 결제: 1초(타임아웃 폴백)
     *   - 총 소요시간 ≈ 2초 (주문 API 기준)
     */
    fun aggregateWithZip(fail: Boolean = false): Mono<AggregateResponse> {
        val startTime = System.currentTimeMillis()

        log.info("")
        log.info("=".repeat(60))
        log.info("[ZIP 모드 시작] 병렬 실행 - 3개의 API를 동시에 호출합니다")
        log.info("  시작 시각: ${LocalDateTime.now()}")
        log.info("  스레드: ${Thread.currentThread().name}")
        log.info("  예상 소요시간: max(사용자1초, 주문2초, 결제1초폴백) ≈ 2초")
        log.info("=".repeat(60))

        // Mono.zip: 세 Mono를 동시에 구독 → 병렬 실행
        return Mono.zip(
            fetchUser(),
            fetchOrder(),
            fetchPayment(fail)
        ).map { tuple ->
            val elapsed = System.currentTimeMillis() - startTime

            log.info("")
            log.info("=".repeat(60))
            log.info("[ZIP 모드 완료] 병렬 실행 결과")
            log.info("  종료 시각: ${LocalDateTime.now()}")
            log.info("  총 실행 시간: ${elapsed}ms")
            log.info("  스레드: ${Thread.currentThread().name}")
            log.info("  → 3개 API가 동시에 실행되어 가장 느린 API(주문 2초) 수준")
            log.info("=".repeat(60))

            AggregateResponse(
                mode = "zip (병렬 실행)",
                user = tuple.t1,
                order = tuple.t2,
                payment = tuple.t3,
                executionTimeMs = elapsed,
                message = "Mono.zip으로 병렬 실행 완료 - 총 ${elapsed}ms 소요 (3개 API 동시 호출)"
            )
        }
    }

    // ============================================================
    // FLATMAP 모드: 순차 실행
    // ============================================================

    /**
     * flatMap 체인을 사용한 순차 API 집계
     *
     * 학습 포인트:
     * flatMap은 이전 Mono가 완료된 후 다음 Mono를 구독(subscribe)합니다.
     * 즉, 사용자 → 주문 → 결제 순서로 순차적으로 실행됩니다.
     * 총 소요시간 ≈ sum(각 API 소요시간) → 모든 API 시간의 합
     *
     * 실제 mock-external-server 기준:
     *   - 사용자: 1초, 주문: 2초, 결제: 1초(타임아웃 폴백)
     *   - 총 소요시간 ≈ 4초 (ZIP의 2배!)
     */
    fun aggregateWithFlatMap(fail: Boolean = false): Mono<AggregateResponse> {
        val startTime = System.currentTimeMillis()

        log.info("")
        log.info("=".repeat(60))
        log.info("[FLATMAP 모드 시작] 순차 실행 - API를 하나씩 순서대로 호출합니다")
        log.info("  시작 시각: ${LocalDateTime.now()}")
        log.info("  스레드: ${Thread.currentThread().name}")
        log.info("  예상 소요시간: 사용자(1초) + 주문(2초) + 결제(1초폴백) ≈ 4초")
        log.info("  실행 순서: 사용자 API → (완료 후) 주문 API → (완료 후) 결제 API")
        log.info("=".repeat(60))

        // flatMap 체인: 순차 실행 (이전 결과를 받아 다음 호출)
        return fetchUser()
            .flatMap { user ->
                log.info("  [FLATMAP] 사용자 API 완료 → 주문 API 호출 시작 | 스레드: ${Thread.currentThread().name}")
                fetchOrder().map { order -> Pair(user, order) }
            }
            .flatMap { (user, order) ->
                log.info("  [FLATMAP] 주문 API 완료 → 결제 API 호출 시작 | 스레드: ${Thread.currentThread().name}")
                fetchPayment(fail).map { payment -> Triple(user, order, payment) }
            }
            .map { (user, order, payment) ->
                val elapsed = System.currentTimeMillis() - startTime

                log.info("")
                log.info("=".repeat(60))
                log.info("[FLATMAP 모드 완료] 순차 실행 결과")
                log.info("  종료 시각: ${LocalDateTime.now()}")
                log.info("  총 실행 시간: ${elapsed}ms")
                log.info("  스레드: ${Thread.currentThread().name}")
                log.info("  → 3개 API가 순서대로 실행되어 각 API 시간의 합과 유사")
                log.info("  → ZIP 모드보다 느림! (병렬 vs 순차의 차이)")
                log.info("=".repeat(60))

                AggregateResponse(
                    mode = "flatMap (순차 실행)",
                    user = user,
                    order = order,
                    payment = payment,
                    executionTimeMs = elapsed,
                    message = "flatMap으로 순차 실행 완료 - 총 ${elapsed}ms 소요 (3개 API 순차 호출, ZIP보다 느림)"
                )
            }
    }
}
