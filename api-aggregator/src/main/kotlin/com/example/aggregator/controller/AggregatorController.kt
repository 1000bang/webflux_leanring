package com.example.aggregator.controller

import com.example.aggregator.model.AggregateResponse
import com.example.aggregator.service.AggregatorService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

// ============================================================
// 집계 API 컨트롤러
//
// 사용법:
//   GET /aggregate?mode=zip        → 병렬 실행 (학습 시나리오 1)
//   GET /aggregate?mode=flat       → 순차 실행 (학습 시나리오 1)
//   GET /aggregate?mode=zip&fail=true → 결제 강제 실패 (서킷브레이커 학습)
//
// 학습 시나리오:
//   1. mode=zip  vs mode=flat  → 실행 시간 비교 (응답 JSON의 executionTimeMs 확인)
//   2. mode=zip  (기본값)       → 결제 3초 지연 → 1초 타임아웃 → 폴백 응답
//   3. fail=true 5회 호출       → 서킷브레이커 오픈
//   4. 10초 대기 후 재호출      → HALF-OPEN → 복구 확인
// ============================================================
@RestController
class AggregatorController(
    private val aggregatorService: AggregatorService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 집계 API 엔드포인트
     *
     * @param mode "zip" (병렬) 또는 "flat" (순차)
     * @param fail 결제 API 강제 실패 여부 (서킷브레이커 테스트용)
     */
    @GetMapping("/aggregate")
    fun aggregate(
        @RequestParam(defaultValue = "zip") mode: String,
        @RequestParam(defaultValue = "false") fail: Boolean
    ): Mono<ResponseEntity<AggregateResponse>> {
        log.info("[집계 API 요청] 모드: $mode | 강제실패: $fail | 스레드: ${Thread.currentThread().name}")

        val result = when (mode.lowercase()) {
            "zip" -> aggregatorService.aggregateWithZip(fail)
            "flat" -> aggregatorService.aggregateWithFlatMap(fail)
            else -> return Mono.just(
                ResponseEntity.badRequest().build()
            )
        }

        return result.map { ResponseEntity.ok(it) }
    }
}
