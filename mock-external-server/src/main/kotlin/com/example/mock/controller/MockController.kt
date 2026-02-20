package com.example.mock.controller

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.LocalDateTime

// ============================================================
// 학습 포인트: Mono.delay()를 사용한 논블로킹 지연 구현
// Thread.sleep()은 현재 스레드를 블로킹하지만,
// Mono.delay()는 스케줄러가 지연 후 신호를 보내므로 논블로킹입니다.
// ============================================================

@RestController
class MockController {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 사용자 정보 조회 API
     * 1초 논블로킹 지연 후 응답 반환
     */
    @GetMapping("/user")
    fun getUser(): Mono<Map<String, Any>> {
        log.info("[사용자 API] 요청 수신 | 스레드: ${Thread.currentThread().name}")

        // Mono.delay: 논블로킹 지연 - 스케줄러가 1초 후 신호 발생
        return Mono.delay(Duration.ofSeconds(1))
            .map {
                log.info("[사용자 API] 응답 준비 완료 | 스레드: ${Thread.currentThread().name}")
                mapOf(
                    "id" to 1,
                    "name" to "홍길동",
                    "email" to "hong@example.com",
                    "message" to "사용자 정보 조회 성공 (1초 지연)",
                    "timestamp" to LocalDateTime.now().toString()
                )
            }
    }

    /**
     * 주문 정보 조회 API
     * 2초 논블로킹 지연 후 응답 반환
     */
    @GetMapping("/order")
    fun getOrder(): Mono<Map<String, Any>> {
        log.info("[주문 API] 요청 수신 | 스레드: ${Thread.currentThread().name}")

        return Mono.delay(Duration.ofSeconds(2))
            .map {
                log.info("[주문 API] 응답 준비 완료 | 스레드: ${Thread.currentThread().name}")
                mapOf(
                    "orderId" to "ORDER-2024-001",
                    "items" to listOf("상품A", "상품B", "상품C"),
                    "totalAmount" to 150000,
                    "message" to "주문 정보 조회 성공 (2초 지연)",
                    "timestamp" to LocalDateTime.now().toString()
                )
            }
    }

    /**
     * 결제 정보 조회 API
     * - fail=true: 즉시 HTTP 500 오류 반환 (서킷브레이커 테스트용)
     * - fail=false: 3초 지연 후 응답 (타임아웃 테스트용 - 집계 서버의 1초 타임아웃 초과)
     */
    @GetMapping("/payment")
    fun getPayment(
        @RequestParam(defaultValue = "false") fail: Boolean
    ): Mono<Map<String, Any>> {
        log.info("[결제 API] 요청 수신 | fail=$fail | 스레드: ${Thread.currentThread().name}")

        // 학습 시나리오 3: fail=true → 500 오류 반환 → 서킷브레이커 실패 카운트 증가
        if (fail) {
            log.error("[결제 API] 강제 실패 시나리오 실행 | 스레드: ${Thread.currentThread().name}")
            return Mono.error(PaymentException("결제 서버 내부 오류: 의도적인 실패 시나리오 (서킷브레이커 학습용)"))
        }

        // 학습 시나리오 2: 3초 지연 → 집계 서버의 1초 타임아웃 초과 → 타임아웃 발생
        return Mono.delay(Duration.ofSeconds(3))
            .map {
                log.info("[결제 API] 응답 준비 완료 | 스레드: ${Thread.currentThread().name}")
                mapOf(
                    "paymentId" to "PAY-2024-001",
                    "status" to "완료",
                    "amount" to 150000,
                    "method" to "신용카드",
                    "message" to "결제 정보 조회 성공 (3초 지연 - 집계서버에서는 1초 타임아웃으로 폴백 발생)",
                    "timestamp" to LocalDateTime.now().toString()
                )
            }
    }
}

// 결제 처리 예외 클래스
class PaymentException(message: String) : RuntimeException(message)

// 전역 예외 처리기 - WebFlux에서 Mono.error()로 발생한 예외 처리
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(PaymentException::class)
    fun handlePaymentException(e: PaymentException): Mono<ResponseEntity<Map<String, String>>> {
        log.error("[오류 처리] 결제 예외 발생: ${e.message}")
        return Mono.just(
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                mapOf(
                    "error" to "결제 처리 실패",
                    "message" to (e.message ?: "알 수 없는 오류"),
                    "status" to "500",
                    "timestamp" to LocalDateTime.now().toString()
                )
            )
        )
    }
}
