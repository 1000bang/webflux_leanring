package com.example.mock

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// 외부 API 모의 서버 - 학습용 지연 응답 및 실패 시나리오 제공
@SpringBootApplication
class MockServerApplication

fun main(args: Array<String>) {
    runApplication<MockServerApplication>(*args)
}
