package com.example.aggregator.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class WebClientConfig {

    // 외부 서버 기본 URL - application.yml 또는 테스트에서 오버라이드 가능
    @Value("\${external-server.base-url:http://localhost:8081}")
    lateinit var baseUrl: String

    // 외부 API 호출용 WebClient 빈
    // 학습 포인트: WebClient는 논블로킹/비동기 HTTP 클라이언트
    //             RestTemplate(블로킹)과 달리 요청 중 스레드를 점유하지 않습니다.
    @Bean
    fun webClient(): WebClient {
        return WebClient.builder()
            .baseUrl(baseUrl)
            .build()
    }
}
