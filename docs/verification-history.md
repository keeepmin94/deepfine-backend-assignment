# 단계별 검증 기록

각 단계 완료 당시의 환경·명령·결과를 보존한 기록입니다. 현재 실행 안내와 최신 요약은 [README](../README.md)를 기준으로 합니다. 과거 단계의 미구현·미검증 문구는 당시 상태이며, 원격 CI 실행이나 사용자의 이해까지 완료했다는 뜻이 아닙니다.

### 8. 1단계 검증 기록 (2026-11-12)

- 환경: macOS ARM64, Homebrew OpenJDK 21.0.12.1, Gradle 8.8, Docker Desktop 4.20.1 / Engine 24.0.2, Compose 2.18.1.
- DB: PostgreSQL 16.14, Linux ARM64. 개발 Compose와 Testcontainers 모두 `postgres:16.14` 사용.
- 검증 이미지 digest: `sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b`.
- Gradle 배포본의 공식 SHA-256을 확인하고 Wrapper의 `distributionSha256Sum`에 고정했습니다.

실제 빌드·테스트 명령:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
GRADLE_USER_HOME=/private/tmp/deepfine-gradle-home \
./gradlew --no-daemon clean check :inventory-api:bootJar
```

위 경로는 검증한 Homebrew 환경의 값입니다. 다른 환경에서는 설치된 JDK 21 경로를 사용하며 임시 Gradle 캐시는 필수가 아닙니다.

| 검증 | 결과 |
| --- | --- |
| 단위 테스트 | 아직 없음. `test` 작업은 NO-SOURCE이며 단위 테스트 통과로 집계하지 않음 |
| Core PostgreSQL 통합 테스트 | 13건 성공 |
| API 기동 통합 테스트 | 1건 성공 |
| 실제 HTTP 기동 E2E | 1건 성공. 미등록 경로 404 확인 |
| 위 테스트의 실패·오류·건너뜀 | 모두 0건 |
| 실행 JAR | 생성 후 검증 전용 Compose DB에 연결하여 V1 적용·서버 기동·HTTP 404 확인 |
| DB Compose | 별도 검증 프로젝트와 새 볼륨으로 healthcheck 성공, 재시작·컨테이너 재생성 후 SQL로 넣은 데이터 보존 확인 |

이 결과는 1단계 완료 당시의 기반 검증입니다. 당시에는 엔티티·업무 API·동시성·롤백을 검증하지 않았습니다. 이후 입고 검증은 아래 2단계 기록에 구분합니다.

### 9. 2단계 검증 기록 (2026-11-12)

환경과 PostgreSQL 이미지는 1단계와 동일합니다. 실행 명령은 다음과 같습니다.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
GRADLE_USER_HOME=/private/tmp/deepfine-gradle-home \
./gradlew --no-daemon check :inventory-api:bootJar
```

결과는 BUILD SUCCESSFUL입니다. 최종 `check`는 변경 없는 작업의 UP-TO-DATE 결과를 재사용했으며 변경된 Core 통합·HTTP 테스트는 다시 실행했습니다. 아래 집계는 1단계 테스트를 포함합니다.

| 종류 | 결과 |
| --- | --- |
| 단위 | 28건 성공 (입력 23, 입고 규칙 5) |
| PostgreSQL 통합 | 32건 성공 (스키마 13, 입고 18, API 기동 1) |
| 실제 HTTP E2E | 33건 성공 (입고 32, 기동 1) |
| 합계 | 93건, 실패·오류·건너뜀 0건 |
| 실행 JAR | 생성 성공. 2단계 업무 흐름은 Testcontainers와 실제 HTTP로 검증했으며 Compose 업무 호출 검증은 후속 단계 |

입고 동시성 테스트는 작업자 8개·DB 풀 최대 8개로 시작 래치를 트랜잭션 진입 전에 해제합니다. 같은 코드 신규 등록, 서로 다른 이름의 신규 등록, 기존 상품 입고를 각각 3회 반복합니다. 모든 결과·예외를 수집하며 시작 대기 5초·결과 수집 전체 20초·실행기 종료 5초로 제한합니다.

별도 테스트에서는 독립 JDBC 트랜잭션이 미커밋 상품·재고를 보유하게 하고, 경쟁 입고가 `pg_stat_activity`에서 실제 UNIQUE 충돌 잠금 대기 중인지 확인한 뒤 커밋합니다. 같은 이름은 성공하고 다른 이름은 409에 해당하는 업무 예외로 종료되는지 검사합니다. 단순히 요청 시작 시각이 비슷했다는 이유만으로 충돌 경로 검증을 대신하지 않습니다.

롤백은 테스트 DB에만 지연 제약 트리거를 설치해 커밋 때 이력 저장을 거부하는 방식으로 검증합니다. Core 테스트의 트리거는 변경된 재고가 DB에 반영됐는지도 검사한 뒤 실패시킵니다. 기존 재고의 수량·수정 시각·이력 복원과 신규 상품·재고·이력 전체 취소를 확인합니다. HTTP 테스트도 커밋 실패가 201이 아닌 500이며 DB에 부분 저장이 없는지 확인합니다. 운영 코드에 오류 주입 경로나 트리거는 없습니다.

2단계 완료 당시에는 출고·조회 API, 혼합 입출고·동시 HTTP 변경, 요청 추적, 잠금 시간 초과·데드락 응답, 애플리케이션 이미지·CI와 새 clone 전체 실행을 검증하지 않았습니다. 이후 출고·조회 검증은 3단계 기록에 구분합니다. 테스트 통과는 운영 처리량이나 모든 실행 순서의 증명이 아닙니다.

### 10. 3단계 검증 기록 (2026-11-12)

환경은 macOS ARM64, Java 21.0.12.1, Gradle 8.8, Boot 3.3.0, PostgreSQL 16.14로 이전 단계와 동일합니다. 관련 작업인 `:inventory-core:test :inventory-core:integrationTest :inventory-api:compileJava`와 `:inventory-api:e2eTest`를 실행한 뒤 아래 최종 명령을 실행했습니다.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
GRADLE_USER_HOME=/private/tmp/deepfine-gradle-home \
./gradlew --no-daemon check :inventory-api:bootJar
```

전체 작업은 성공했습니다. 최종 `check`는 이미 실행한 변경 없는 테스트의 UP-TO-DATE 결과를 재사용했고, API 기동 통합 테스트와 실행 JAR 생성을 수행했습니다. 집계는 이전 단계 테스트를 포함합니다.

| 종류 | 결과 |
| --- | --- |
| 단위 | 49건 성공 (입력 23+14, 입출고 규칙 12) |
| PostgreSQL 통합 | 47건 성공 (스키마 13, 입고 18, 출고·조회·잠금 15, API 기동 1) |
| 실제 HTTP E2E | 64건 성공 (입고 32, 출고·조회 31, 기동 1) |
| 합계 | 160건, 실패·오류·건너뜀 0건 |
| 실행 JAR | 생성 성공. 업무 API는 실제 HTTP E2E로 검증했으며 전체 Compose 검증은 후속 단계 |

- 출고 경쟁은 작업자 8개·DB 풀 최대 8개로 충분한 재고와 초과 출고를 각각 3회 반복합니다. 초기 재고 24에서 각 3개 출고는 8건 모두 성공·최종 0, 초기 재고 10에서는 3건 성공·5건 재고 부족·최종 1을 확인합니다. 이력 수와 입고 합계−성공한 출고 합계도 비교합니다. 래치·결과 전체 수집·제한된 대기와 종료를 사용합니다.
- 독립 JDBC 트랜잭션이 PRODUCT-A의 재고 행 잠금을 유지하는 동안, 해당 상품 출고가 실제 잠금 대기 중인지 `pg_stat_activity`에서 확인합니다. 관측한 Hibernate SQL에 쓰기 잠금 구문이 있는지도 검사합니다. A 조회와 PRODUCT-B 출고는 각각 3초 이내 완료되고 A 출고는 여전히 대기하다가 잠금 해제 후 성공하는지 확인합니다. 이 시간 제한은 테스트의 실패 탐지 기준이며 운영 성능 보장이 아닙니다.
- 테스트에만 Hibernate 통계를 켜 조회 한 번의 prepared statement 수가 1이고 엔티티 로딩 수가 0인지 확인합니다. 일반 조회에 쓰기 잠금이 없다는 동작은 위 행 잠금 시나리오로 별도 검증합니다.
- DB 지연 트리거로 재고 UPDATE 이후 출고 이력의 커밋을 실패시켜 수량·수정 시각·이력 롤백을 확인합니다. 실제 HTTP에서도 커밋 실패가 201이 아닌 500이며 부분 저장이 없는지 확인합니다.
- HTTP 입고→출고→조회, 전량 출고, Long.MAX_VALUE 전량 출고, 재고 부족 409, 상품 없음 404, 재고 누락 500, JSON 타입·수량·경로 코드 검증과 UTC 시각을 검사합니다.

3단계 완료 당시의 미검증 범위는 요청 추적, 잠금 시간 초과·데드락 분류와 응답, 혼합 입출고·동시 HTTP 변경, 애플리케이션 이미지·CI와 새 clone의 전체 실행입니다. 3단계에는 새 라이브러리·트랜잭션 전파 옵션·스키마 변경을 추가하지 않았습니다. 요청 추적과 잠금 오류의 후속 검증은 4단계 기록에 구분합니다.

### 11. 4단계 검증 기록 (2026-11-12)

환경과 DB 마이그레이션은 이전 단계와 동일합니다. 관련 단위·잠금 통합·HTTP 테스트를 개별 실행한 뒤 필터 없는 전체 검증을 실행했습니다.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
GRADLE_USER_HOME=/private/tmp/deepfine-gradle-home \
./gradlew --no-daemon check :inventory-api:bootJar
```

결과는 BUILD SUCCESSFUL입니다. 최종 검증은 단위 테스트의 변경 없는 결과를 재사용했고, PostgreSQL 통합·실제 HTTP 테스트 전체와 JAR 생성을 수행했습니다. 아래 집계는 이전 단계를 포함합니다.

| 종류 | 결과 |
| --- | --- |
| 단위 | 67건 성공 (Core 49, API 18) |
| PostgreSQL 통합 | 51건 성공 (스키마 13, 입고 18, 출고·조회 15, 잠금 설정·시간 초과 4, API 기동 1) |
| 실제 HTTP E2E | 73건 성공 (기동 1, 입고 32, 출고·조회 31, 요청 추적·시간 초과 6, 데드락 3) |
| 합계 | 191건, 최종 실패·오류·건너뜀 0건 |
| 실행 JAR | 생성 성공. 애플리케이션 이미지·전체 Compose는 후속 단계 |

- 기본 3초 설정은 실제 트랜잭션에서 `SHOW lock_timeout`으로 확인합니다. 업무 풀 크기 1에서 커밋·롤백·실제 시간 초과 후 같은 `pg_backend_pid()` 연결이 재사용되고 설정이 0으로 돌아오는지 검증합니다. 행 잠금과 신규 등록 UNIQUE 충돌의 `55P03`도 확인하며, 명시적인 다음 요청만 성공 이력을 추가합니다.
- 위 테스트는 최초 기동에서 Flyway가 추가 마이그레이션 연결을 얻지 못해 4건 실패했습니다. 테스트에 한해 Flyway를 같은 PostgreSQL·같은 DDL의 별도 연결로 실행하도록 수정했습니다. 업무 풀 크기 1과 동일 물리 연결 확인을 유지했고 테스트를 비활성화하거나 기대값을 낮추지 않았습니다.
- HTTP 시간 초과 검증은 테스트 설정 250ms를 사용합니다. 행 잠금·신규 등록 충돌뿐 아니라 재고 UPDATE 후 커밋 단계의 잠금 시간 초과를 유도하고, 503·수량·수정 시각·이력 롤백과 명시적 재요청을 검증합니다. 운영 기본 3초 검증은 위 Core 테스트와 구분합니다.
- 실제 데드락 HTTP 테스트는 각 실행에서 3회 반복합니다. 테스트 전용 트리거가 두 번째 상품 잠금을 요청하게 하고, advisory 게이트와 `pg_blocking_pids`로 대기를 조율해 실제 순환 대기를 만듭니다. DB가 발생시킨 `40P01`이 HTTP 503·TRANSACTION_RETRY_REQUIRED로 변환되고 전체가 롤백되는지 확인합니다. 테스트의 HTTP 잠금 제한은 10초, 외부 경쟁 연결의 트랜잭션 한정 deadlock_timeout은 1시간으로 설정해 HTTP 측 감지기가 먼저 중단하도록 조율합니다. 실제로 1시간을 기다리는 테스트가 아니며 Future·대기·종료에는 별도 제한이 있습니다. 이 트리거·게이트·감지 설정은 운영 코드에 없습니다.
- 요청 추적 단위 테스트는 정상·예외 종료의 MDC 복원을 검증합니다. HTTP에서는 시작 래치로 조율한 병렬 8개 요청의 헤더·오류 본문·완료 로그 ID가 각각 일치하고 중복되지 않는지 확인합니다. 같은 입고 본문을 두 번 보내면 ID도 새로 생기고 이력도 두 건인 것을 확인합니다.
- 제약 위반의 500 응답과 SQL·내부 정보 비노출을 유지합니다. SQLSTATE 분류 단위 테스트는 다른 DB 오류를 503으로 바꾸지 않는지도 검사합니다.

4단계 완료 당시의 미검증 범위는 혼합 입출고·동시 HTTP 변경의 정합성 보강, 애플리케이션 이미지·CI와 새 clone의 전체 실행입니다. 혼합 동시 요청의 후속 검증은 5단계 기록에 구분합니다. 운영 성능이나 정상 단일 상품 경로의 데드락 발생 빈도를 측정한 것은 아닙니다. 4단계에 새 라이브러리·자동 재시도·트랜잭션 전파 옵션·운영 스키마 변경은 추가하지 않았습니다.

### 12. 5단계 검증 기록 (2026-11-12)

운영 코드·설정·의존성·DDL은 변경하지 않고 관련 테스트와 문서만 보강했습니다. 환경은 macOS ARM64, Java 21.0.12.1, Gradle 8.8, Docker Engine 24.0.2, PostgreSQL 16.14 Linux ARM64로 이전 단계와 동일합니다. 단위·DB·HTTP 관련 테스트를 먼저 실행한 뒤 이전 빌드 결과를 지우고 전체 검증을 수행했습니다.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
GRADLE_USER_HOME=/private/tmp/deepfine-gradle-home \
./gradlew --no-daemon clean check :inventory-api:bootJar
```

결과는 BUILD SUCCESSFUL이며 actionable task 19개가 모두 실행됐습니다. `clean`은 빌드 결과 정리이며 새 clone·앱 컨테이너 실행 검증과는 구분합니다. 집계는 앞선 테스트를 포함합니다.

| 종류 | 결과 |
| --- | --- |
| 단위 | 68건 성공 (Core 50, API 18) |
| PostgreSQL 통합 | 60건 성공 (스키마 13, 입고 18, 출고·조회·혼합 24, 잠금 설정·시간 초과 4, API 기동 1) |
| 실제 HTTP E2E | 103건 성공 (기동 1, 입고 42, 출고·조회 39, 동시 신규·혼합 12, 요청 추적·시간 초과 6, 데드락 3) |
| 합계 | 231건, 실패·오류·건너뜀 0건 |
| 실행 JAR | 생성 성공 |

혼합 요청은 작업자 8개·DB 풀 최대 8개, 입고 4개·출고 4개로 구성하고 아래 각 시나리오를 DB와 HTTP에서 각각 3회 반복합니다. 시작 래치는 트랜잭션·연결 획득 전에 해제합니다. 모든 결과·예외를 수집하고 시작 5초·전체 결과 수집 20초·종료 5초로 제한합니다. 의도하지 않은 시간 초과·503·DB 예외를 재고 부족으로 세지 않습니다.

| 초기 재고 | 동시 요청 | 사전 기대 결과 |
| --- | --- | --- |
| 100 | 입고 7×4, 출고 11×4 | 8건 성공, 최종 84, 초기 입고 포함 이력 9건 |
| 2 | 입고 1×4, 출고 7×4 | 입고 4건 성공·출고 4건 실패, 최종 6, 이력 5건 |
| 6 | 입고 1×4, 출고 3×4 | 입고 4건 성공, 출고 성공 S는 2 또는 3, 최종 10−3S, 이력 5+S건 |

마지막 경우는 초기 재고로 최소 두 번 출고할 수 있고 총재고 10으로는 최대 세 번만 출고할 수 있습니다. 실패한 출고를 재시도하지 않으므로 실행 순서에 따라 S가 달라질 수 있습니다. 이 사전 범위와 정확한 계산식을 검사하며 실행 결과를 보고 기대 건수를 바꾸지 않습니다.

검증은 최종 수량 외에도 상품·재고 각각 한 행, 성공 응답의 ID·유형·수량·변경 후 수량·시각과 저장된 이력 행의 일치, 실패 요청의 이력 부재를 검사합니다. 테스트 전용 지연 트리거는 각 이력의 quantity_after가 커밋 직전 같은 트랜잭션에서 반영된 재고와 일치하는지 확인합니다. ID나 시각을 커밋 순서로 가정해 혼합 이력을 정렬하지 않습니다.

HTTP에서는 같은 신규 코드에 2개씩 8건 동시 입고하는 시나리오도 3회 반복해 상품·재고 각각 한 행, 최종 16, 이력 8건을 확인합니다. 성공·실패 응답의 요청 ID가 중복되지 않고 오류 본문과 헤더가 일치하는지 검사하며, 완료 후 조회 API의 수량·재고 변경 시각을 DB와 대조합니다.

경계값은 고정 시드 20261112의 BigInteger 모델로 256개 입출고 연산을 검증하는 단위 테스트 1건을 추가했습니다. 이 모델은 long 덧셈과 독립적으로 기대 수량을 계산하고 거부 시 수량·수정 시각 보존도 확인합니다. HTTP에서는 Long 최솟값 미만, 지수 표기 1e0, 배열·객체 수량, boolean·소수 상품 코드/이름, 짝이 맞지 않는 하위 서로게이트 거부를 보강했습니다. 상품 코드 64자와 Long.MAX_VALUE의 조합, 65자 입고·출고 거부도 확인합니다.

5단계 완료 당시 남은 검증은 애플리케이션 이미지·전체 Compose, CI와 새 clone에서의 전체 실행이었습니다. 이미지·Compose 후속 결과는 아래 6단계 기록에 구분합니다. 반복 테스트는 이 환경의 시나리오 검증이며 모든 실행 순서나 운영 성능을 보장하지 않습니다.

### 13. 이미지·CI 구성과 6단계 검증 기록 (2026-11-12)

`Dockerfile`은 Temurin 21.0.13+9의 JDK 빌드 단계와 JRE 실행 단계를 분리합니다. 정확한 태그와 이미지 digest를 고정하고, Gradle Wrapper로 소스에서 `:inventory-api:bootJar`를 실행한 뒤 실행 JAR만 최종 이미지에 복사합니다. 호스트 빌드 결과나 JDK에 의존하지 않기 위한 구성입니다. `.dockerignore`는 빌드 결과·로컬 환경 파일·계획 문서를 제외합니다. 최초 빌드는 이미지·Gradle·의존성 다운로드가 필요하며 네트워크나 컴파일 오류가 있으면 실패합니다. 대안인 호스트 JAR 복사 방식은 빌드 환경을 따로 준비하고 산출물이 최신인지 관리해야 합니다.

Compose는 `depends_on: condition: service_healthy`로 DB 준비를 기다린 다음 API를 시작합니다. API 연결 주소는 호스트의 localhost 대신 Compose 서비스명 `db`입니다. 이 순서만으로 Flyway·JPA 검증·HTTP 준비가 보장되지는 않으므로 검증 스크립트는 실제 조회 경로를 제한된 횟수로 확인합니다. 입출고 요청은 자동 재전송하지 않습니다. 실행 이미지는 JDK 대신 JRE만 사용하며 Java 소스 컴파일은 빌드 단계의 책임입니다.

`.github/workflows/ci.yaml`은 main push·pull request·수동 실행에서 `ubuntu-24.04`, Temurin 21.0.13+9로 `./gradlew --no-daemon clean check`와 `./scripts/verify-compose.sh`를 순서대로 수행합니다. 두 명령 중 하나라도 실패하면 작업이 실패합니다. actions/checkout과 actions/setup-java는 커밋 SHA로 고정하고 저장소 권한은 읽기만 부여합니다. 수동 검증만 하는 대안보다 반복 실행 절차를 저장소에 남길 수 있지만, GitHub runner의 Docker·네트워크 상태에도 의존합니다. 원격 실행 결과와 로컬 검증 결과는 구분합니다.

검증 호스트는 macOS ARM64, Docker Desktop 4.20.1 / Engine 24.0.2, Compose 2.18.1입니다. 앱 빌드·실행과 아래 전체 테스트는 Linux ARM64의 Temurin 21.0.13+9, PostgreSQL 16.14에서 수행했습니다. 앞선 단계의 호스트 Java 21.0.12.1과 패치 버전이 다릅니다. 새 라이브러리·업무 코드·DDL·트랜잭션 옵션은 추가하거나 변경하지 않았습니다.

로컬 설정·계획서·기존 빌드 결과를 제외한 Git tree를 별도 임시 경로에 내보내 검증했습니다. 최초 이미지 빌드에서 이미지 내부 소스 컴파일과 실행 JAR 생성에 성공했고, 최종 소스 사본의 Compose 재검증에서는 동일 소스의 Docker 빌드 캐시를 사용했습니다. 캐시 없는 모든 재다운로드나 원격 clone을 검증했다는 뜻은 아닙니다.

실제 실행한 핵심 명령은 다음과 같습니다. 테스트용 빌드 이미지를 별도로 내보낸 뒤 Docker 소켓을 연결해 Testcontainers가 PostgreSQL을 만들도록 했습니다. `TESTCONTAINERS_HOST_OVERRIDE`는 Docker Desktop의 테스트 컨테이너에서 호스트에 공개된 DB 포트로 접근하기 위한 검증용 값이며 앱 설정에 추가하지 않았습니다.

```bash
docker build --target build --tag deepfine-stage6-test-builder:trhwxf .
docker run --name deepfine-stage6-test-trhwxf \
  --volume /var/run/docker.sock:/var/run/docker.sock \
  --env TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  deepfine-stage6-test-builder:trhwxf \
  ./gradlew --no-daemon clean check

# Git 소스 사본에서 실행
./scripts/verify-compose.sh
```

| 검증 | 결과 |
| --- | --- |
| Linux 전체 Gradle 검증 | BUILD SUCCESSFUL, 3분 37초, actionable task 17개 모두 실행 |
| 단위 / PostgreSQL 통합 / 실제 HTTP E2E | 68 / 60 / 103건 성공, 합계 231건, 실패·오류·건너뜀 0건 |
| 최종 실행 이미지 | JRE 21.0.13+9와 `/app/app.jar` 확인, `/workspace`·javac 부재 확인 |
| Compose 초기 기동 | 새 격리 볼륨, PostgreSQL 16.14, Flyway V1 성공 기록과 실제 HTTP 준비 확인 |
| Compose 업무·추적 | 입고 10 → 출고 3 → 조회 7, 초과 출고 409·INSUFFICIENT_STOCK, 요청 ID 헤더·오류 본문 일치 |
| Compose 저장 상태 | 상품·재고 각 1행, 재고 7, 성공 이력 2행 및 이력 합계 7 |
| Compose 데이터 보존 | DB·API 재시작 후와 `down` → `up` 재생성 후 같은 HTTP·DB 상태 확인 |
| 검증 자원 정리 | 고유 프로젝트의 컨테이너·네트워크·볼륨 정리 성공, 기존 볼륨 3개 유지 확인 |
| 정적 확인 | `bash -n`, workflow YAML 구문 파싱, Compose config 확인 성공. GitHub Actions 실행 검증과는 구분 |

Compose의 초과 출고 검사는 변경 전 거부와 이력 부재를 확인합니다. 저장 실패 시 롤백은 별도의 PostgreSQL 통합·HTTP 테스트가 검증하며 두 시나리오를 혼동하지 않습니다. 테스트 결과 XML을 회수해 위 집계를 확인했고 검증용 테스트 컨테이너는 결과 회수 후 제거했습니다.

원격 push를 하지 않았으므로 GitHub Actions의 Ubuntu 24.04 / AMD64 실행, 원격 저장소의 새 clone 실행은 미검증입니다. 로컬 Linux ARM64 통과를 원격 CI 통과로 주장하지 않습니다. 최종 문서 대조와 사용자의 설계 설명 확인은 다음 단계에 남아 있습니다.

### 14. 7단계 문서 대조·로컬 clone 검증 (2026-11-12)

README를 Controller·DTO·Service·Entity·Repository·설정·마이그레이션·Gradle 작업·CI와 대조했습니다. 현재 실행 안내와 과거 검증 기록을 분리하고, DDL 컬럼·제약, 오류 호출 예시, 책임 분리, 잠금·트랜잭션의 이유·대안·한계, 창고·멱등성의 향후 검토 범위를 정리했습니다. 기존 1~6단계 기록은 문구를 그대로 이동했으며 최초 실패 기록도 유지했습니다. 이번 단계는 문서만 변경합니다.

검증 호스트와 Docker 버전은 6단계와 같습니다. 로컬 커밋 `f276062`를 `git clone --no-hardlinks`로 새 임시 경로에 clone하고 7단계 문서만 복사했습니다. `.env`·계획 문서·기존 빌드 결과가 없는 상태와 Wrapper·검증 스크립트 실행 권한을 확인했습니다. 원격 GitHub clone이 아닌 로컬 저장소 clone입니다.

- 예제 환경 파일을 복사하고 검증용 환경변수·고유 Compose 프로젝트·임의 호스트 포트로 API·DB를 실행했습니다. 동일 소스의 Docker 빌드 캐시를 재사용했습니다.
- 임시 검증 도구가 README Bash 블록에서 curl 예시 6개를 직접 추출했습니다. API 주소만 실제 임의 포트로 치환하고 진단용 제한 시간·상태 수집 옵션을 덧붙였습니다. 업무 요청 자동 재시도는 하지 않았습니다.
- 입고·출고·조회·초과 출고·미등록 조회·숫자 문자열 수량이 순서대로 201·201·200·409·404·400인지 확인했습니다. 성공·오류 응답 필드, 요청 ID의 UUID 형식·중복 부재와 오류 본문 일치, 한글 상품명, 변경 후 수량도 검사했습니다.
- DB에서 Flyway V1, 상품·재고 각 1행, 수량 7, 성공 이력 2행과 이력 합계 7을 확인했습니다. `docker compose stop` → `start`, `down` → `up -d --wait` 후 HTTP 조회와 같은 DB 상태를 확인했습니다.
- 종료 시 검증 프로젝트의 컨테이너·네트워크·볼륨만 제거했습니다. 기존 Docker 볼륨은 보존했습니다.

작업 연결 확인 명령:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
GRADLE_USER_HOME=/private/tmp/deepfine-gradle-home \
./gradlew --no-daemon --offline check --dry-run
```

최초 시도는 샌드박스의 로컬 소켓 제한으로 Gradle 데몬 시작에서 `java.net.SocketException: Operation not permitted`로 실패했습니다. 같은 명령을 소켓 사용이 허용된 환경에서 다시 실행해 BUILD SUCCESSFUL을 확인했습니다. dry-run의 SKIPPED 출력은 작업 실행 계획만 확인했다는 뜻이며 단위·DB·HTTP 테스트 성공 또는 테스트 건너뜀 집계로 사용하지 않습니다. 전체 테스트 231건은 6단계 실제 실행 결과이며 이번 문서 변경으로 다시 집계하지 않았습니다.

README와 이 문서의 코드 펜스·상대 파일 링크, Bash 예시의 `bash -n`, `git diff --check`를 확인했습니다. 정적 문서 확인과 위 실제 HTTP·DB 실행은 구분합니다. 기존 검증 기록 이동 전후의 본문이 동일한지도 확인했습니다.

남은 확인은 원격 push 후 GitHub Actions의 실제 Ubuntu 24.04 / AMD64 실행, 원격 clone과 해당 환경의 재현, 사용자의 설계 이해입니다. push는 승인되지 않아 수행하지 않았으며 변경 승인·실행 성공만으로 이해 확인을 대신하지 않습니다.

### 15. 역할별 패키지 정리 검증 (2026-11-12)

승인한 분류안에 따라 운영 Java 클래스 28개와 단위 테스트 6개를 역할별 패키지로 이동했습니다. API는 controller·dto/request·dto/response·exception·config·filter, Core는 service·repository·entity·dto/command·dto/result·enums·exception·validator로 나눕니다. InventoryApplication의 위치는 유지했습니다.

패키지·import와 JPQL DTO 생성자의 전체 클래스명을 수정했습니다. 통합 테스트의 EntityScan·Repository 탐색 기준은 이동한 클래스 참조를 사용하고, 업무·검증 흐름은 유지했습니다. Service가 다른 패키지의 Entity 생성 메서드를 호출할 수 있도록 StockMovement.inbound·outbound 두 메서드만 package-private에서 public static으로 변경했습니다. JPA 기본 생성자와 내부 공통 생성 메서드의 접근 범위는 유지했습니다. DTO 변환 메서드를 유지해 별도 mapper·라이브러리는 추가하지 않았습니다.

실제 실행 명령:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
GRADLE_USER_HOME=/private/tmp/deepfine-gradle-home \
./gradlew --no-daemon clean check :inventory-api:bootJar
```

macOS ARM64 / Homebrew Java 21.0.12.1 / Gradle 8.8, Docker Engine 24.0.2 / Compose 2.18.1, PostgreSQL 16.14 Linux ARM64에서 BUILD SUCCESSFUL, 1분 40초, 19개 actionable task 모두 실행을 확인했습니다. XML 집계는 단위 68건·PostgreSQL 통합 60건·실제 HTTP E2E 103건으로 총 231건이며 실패·오류·건너뜀 0건입니다. 테스트 본문·기대값은 변경하지 않았고 실행 JAR도 새 패키지로 생성됐습니다.

정적 비교에서는 Java 파일 46개에 대해 package·import·변경된 전체 클래스 참조와 승인된 생성 메서드 두 개의 접근 범위를 제외한 본문이 같은지 확인했습니다. 파일 경로와 package 일치, 이전 패키지의 클래스 참조 부재, README의 파일 링크·Bash 예시도 확인했습니다. 이 정적 비교는 위 실제 테스트 실행을 대신하지 않습니다.

```bash
./scripts/verify-compose.sh
```

Compose 검증도 성공 종료했습니다. 기반 이미지·Wrapper 입력 레이어 캐시는 사용했지만 변경한 API·Core 소스 복사와 이미지 내부 JDK 컴파일은 다시 실행했습니다. `:inventory-api:bootJar`가 BUILD SUCCESSFUL, 6개 actionable task 모두 실행으로 끝났고 Linux ARM64 / Temurin JRE 21.0.13+9에서 API가 기동했습니다. 호스트의 빌드 결과는 Docker 컨텍스트에서 제외했습니다.

새 격리 볼륨에 PostgreSQL 16.14·Flyway V1이 적용됐고, 실제 HTTP 입고 10·출고 3·조회 7·초과 출고 409와 요청 ID를 확인했습니다. 상품·재고 각 1행, 성공 이력 2행 및 이력 합계 7을 검사했습니다. DB·API 재시작과 `down` → `up` 후 같은 HTTP·DB 상태를 확인한 뒤 고유 검증 프로젝트의 컨테이너·네트워크·볼륨만 정리했습니다.

이번 패키지 변경 후 전체 테스트는 macOS Java 21.0.12.1에서, 이미지 빌드·Compose는 Linux ARM64 Temurin 21.0.13+9에서 검증했습니다. Linux 컨테이너에서 전체 테스트를 다시 실행하거나 원격 CI·AMD64·새 원격 clone을 검증한 것은 아닙니다. DB 스키마·HTTP 계약·업무 규칙·트랜잭션 옵션·의존성은 변경하지 않았고 원격 push도 하지 않았습니다.
