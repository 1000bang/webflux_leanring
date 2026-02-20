package com.example.aggregator.model

// 집계 응답 데이터 모델
data class AggregateResponse(
    // 실행 모드: "zip (병렬 실행)" 또는 "flatMap (순차 실행)"
    val mode: String,
    // 사용자 정보 (mock-external-server /user 응답)
    val user: Map<String, Any>?,
    // 주문 정보 (mock-external-server /order 응답)
    val order: Map<String, Any>?,
    // 결제 정보 (mock-external-server /payment 응답 또는 폴백)
    val payment: Map<String, Any>?,
    // 전체 실행 시간 (밀리초) - zip과 flat 비교에 활용
    val executionTimeMs: Long,
    // 응답 요약 메시지
    val message: String
)
