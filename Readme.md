# 프로젝트 구조

```
webflux-test/
├── gradle.properties         
├── settings.gradle.kts
├── build.gradle.kts
├── api-aggregator/            (포트 8080)
│   ├── build.gradle.kts
│   └── src/main/kotlin/.../
│       ├── AggregatorApplication.kt
│       ├── config/WebClientConfig.kt
│       ├── model/AggregateResponse.kt
│       ├── service/AggregatorService.kt    ← 핵심 학습 코드
│       ├── controller/AggregatorController.kt
│       └── resources/application.yml      ← 서킷브레이커 설정
└── mock-external-server/      (포트 8081)
    ├── build.gradle.kts
    └── src/main/kotlin/.../
        ├── MockServerApplication.kt
        ├── controller/MockController.kt
        └── resources/application.yml
```
# 실행 방법
### 1. mock-external-server 먼저 실행:
```bash
gradle :mock-external-server:bootRun
```


### 2. api-aggregator 실행 (다른 터미널):
```bash
gradle :api-aggregator:bootRun
```


# 4가지 학습 시나리오
| 시나리오	 | 명령어 | 
| --- | --- |
| 1. ZIP vs flatMap 실행시간 비교	| curl "localhost:8080/aggregate?mode=zip" vs curl "localhost:8080/aggregate?mode=flat" |
|2. 타임아웃 → 폴백	| curl "localhost:8080/aggregate?mode=zip" (결제 3초 지연 → 1초 타임아웃 자동 발생) |
|3. 서킷브레이커 OPEN |	curl "localhost:8080/aggregate?fail=true" × 5회 |
|4. HALF-OPEN 전환	10초 대기 후 | curl "localhost:8080/aggregate" |


### 테스트 결과 (모두 통과) 
- ZIP 902ms < flatMap 1255ms 
→ 병렬이 순차보다 353ms 빠름 확인

- 타임아웃 → paymentId: FALLBACK 폴백 응답 확인
- 5회 실패 → 서킷브레이커 OPEN 상태 확인
- 2.5초 대기 → HALF_OPEN 전환 확인# webflux_leanring
# webflux_leanring
# webflux_leanring
