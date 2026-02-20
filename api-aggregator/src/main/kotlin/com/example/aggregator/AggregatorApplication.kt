package com.example.aggregator

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// API 집계 서버 - WebClient로 외부 API를 호출하고 결과를 집계합니다
// 학습 포인트:
//   - Mono.zip: 병렬 실행 (여러 Mono를 동시에 구독)
//   - flatMap 체인: 순차 실행 (이전 결과가 완료된 후 다음 실행)
//   - timeout: 지정 시간 초과 시 TimeoutException 발생
//   - onErrorResume: 오류 발생 시 폴백 응답 반환
//   - CircuitBreaker: 연속 실패 시 서킷 오픈 → 빠른 실패(Fast Fail)
@SpringBootApplication
class AggregatorApplication

fun main(args: Array<String>) {
    runApplication<AggregatorApplication>(*args)
}
