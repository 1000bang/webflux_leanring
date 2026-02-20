rootProject.name = "webflux-learning"

// 멀티 모듈 프로젝트 구성
include("api-aggregator")       // 외부 API 집계 서버
include("mock-external-server") // 외부 API 모의 서버
