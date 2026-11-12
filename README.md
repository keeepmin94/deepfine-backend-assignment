# DeepFine Backend Assignment — Inventory API

재고 관리 MVP 과제입니다. 프로젝트 기반과 입고·출고·재고 조회 API를 구현했습니다. 요청 추적과 잠금 시간 초과 처리는 후속 단계입니다.

## 구현 범위

- 상품 코드 기준 현재 재고 조회
- 신규 상품 등록을 포함한 입고
- 음수 재고를 허용하지 않는 출고
- 재고 변경과 성공한 입출고 이력의 원자적 저장
- 동시 신규 등록과 동시 입출고의 정합성 검증

## 기술 및 구조

Java 21, Spring Boot 3.3.0, JPA, PostgreSQL 16.14, Gradle 8.8 Wrapper(Groovy DSL)를 사용합니다. Flyway 10.10.0과 Testcontainers 1.19.8을 포함한 의존성 버전은 Spring Boot BOM으로 관리합니다.

- inventory-api: HTTP 요청·응답, 검증, 예외 처리, 애플리케이션 실행
- inventory-core: 상품·재고 규칙, 입출고 처리, 영속성
- product: 상품 기본 정보
- inventory: 상품당 하나의 현재 재고
- stock_movement: 성공한 입출고 이력

상품 정보와 재고 상태의 책임을 구분하기 위해 테이블을 분리합니다. 현재는 1:1 구조이고 창고·옵션별 재고는 제외합니다. 향후 재고 기능이 확대되면 상품 정보에 미치는 영향을 줄이고 재고 영역 중심으로 확장할 수 있습니다. 분리에 따른 JOIN과 동시 생성 비용은 있으며, 분리 자체가 동시성이나 성능을 보장하는 것은 아닙니다.

## API와 구현 상태

| HTTP 메서드 | 경로 | 기능 | 상태 |
| --- | --- | --- | --- |
| POST | /api/v1/stock/inbounds | 신규 또는 기존 상품 입고 | 구현 |
| POST | /api/v1/stock/outbounds | 기존 상품 출고 | 구현 |
| GET | /api/v1/products/{productCode}/stock | 상품 정보와 현재 재고 조회 | 구현 |

입출고 성공은 201, 조회 성공은 200입니다.

## 실행·DDL·검증

> 현재 Compose에는 DB만 포함됩니다. 애플리케이션은 Java 21로 로컬 실행합니다. 애플리케이션 이미지·전체 Compose 실행·CI는 후속 단계에서 구성합니다.

### 1. 준비 사항

- Git
- Docker와 Docker Compose v2: Docker 엔진이 실행 중이어야 합니다.
- 로컬에서 Gradle 테스트를 실행할 경우 JDK 21
- 최초 실행 시 이미지와 의존성을 내려받을 수 있는 네트워크 환경

현재는 로컬 JDK 21이 필요하고 PostgreSQL의 로컬 설치는 필요하지 않습니다. Docker 엔진은 실행 중이어야 합니다.

### 2. 저장소 내려받기

```bash
git clone https://github.com/keeepmin94/deepfine-backend-assignment.git
cd deepfine-backend-assignment
```

이후 명령은 저장소 최상위 디렉터리에서 실행합니다. 공개 저장소이므로 SSH 키 설정이 필요 없는 HTTPS 주소를 사용합니다.

### 3. 환경 설정 및 실행

Docker 실행 상태와 Compose 설치를 확인합니다.

```bash
docker version
docker compose version
```

환경 설정 예시를 복사하고 로컬 개발용 비밀번호를 변경합니다. `.env`는 Git에 올리지 않습니다. Compose는 `.env`를 읽지만 Java 애플리케이션은 자동으로 읽지 않으므로 별도로 환경변수를 전달해야 합니다.

```bash
cp .env.example .env
docker compose up -d --wait
docker compose ps
docker compose logs -f db
```

DB는 `postgres:16.14` 이미지를 사용하며 `pg_isready` healthcheck가 성공할 때까지 기다립니다. 로그 확인은 `Ctrl+C`로 종료할 수 있으며 DB는 계속 실행됩니다. `pg_isready`는 DB 접속 준비 확인이며 애플리케이션 스키마 검증은 아닙니다.

| 환경변수 | 기본값·의미 |
| --- | --- |
| POSTGRES_USER | inventory |
| POSTGRES_PASSWORD | 필수. 예제 값은 로컬 개발용이며 실제 비밀정보가 아닙니다. |
| DB_PORT | 5432. DB 호스트 포트이며 127.0.0.1에만 바인딩합니다. |
| DB_URL | jdbc:postgresql://localhost:5432/inventory |
| SERVER_PORT | 8080 |

DB 이름은 `inventory`입니다. `DB_PORT` 변경 시 `DB_URL`의 포트도 맞춰야 합니다. DB 초기화 환경변수는 새 볼륨 생성 시 적용되므로 기존 볼륨의 사용자·비밀번호를 파일 수정만으로 바꿀 수는 없습니다.

직접 작성하고 신뢰하는 `.env`를 셸로 읽어 로컬 애플리케이션을 시작합니다. 또는 IDE 실행 설정에 같은 환경변수를 등록합니다.

```bash
set -a
source .env
set +a
./gradlew :inventory-api:bootRun
```

Java 17이 기본으로 선택된 환경에서는 먼저 `JAVA_HOME`과 `PATH`를 설치된 JDK 21로 설정합니다. Java toolchain도 21로 고정했으며 JDK 자동 다운로드 플러그인은 사용하지 않습니다.

### 4. API 호출 확인

아래 예시는 새 상품 코드를 사용해 순서대로 한 번씩 실행하는 흐름입니다. 실제 HTTP E2E에서 같은 흐름을 검증합니다.

신규 상품을 10개 입고합니다. 예상 HTTP 상태는 `201`입니다.

```bash
curl -i -X POST http://localhost:8080/api/v1/stock/inbounds \
  -H 'Content-Type: application/json' \
  -d '{"productCode":"DEMO-001","name":"테스트 상품","quantity":10}'
```

응답 형식은 다음과 같습니다. ID와 시각은 실행마다 달라집니다.

```json
{"movementId":1,"productCode":"DEMO-001","movementType":"INBOUND","quantity":10,"quantityAfter":10,"createdAt":"2026-11-12T03:00:00Z"}
```

`quantityAfter`는 해당 변경 직후의 수량으로 응답 도착 시점의 최신 수량을 뜻하지 않습니다. 생성 시각은 UTC `Instant`이며 DB와 같은 마이크로초 정밀도로 반환합니다.

입고 입력 규칙:

- `productCode`: ASCII 대문자·숫자·하이픈·밑줄 1~64자. 대소문자·공백을 자동 변환하지 않습니다.
- `name`: Java `String.strip()`으로 앞뒤 공백을 제거한 뒤 코드 포인트 1~200자. 보조 평면 문자도 한 코드 포인트로 계산하며 NUL과 짝이 맞지 않는 서로게이트는 거부합니다. 별도의 유니코드 정규화는 하지 않습니다.
- `quantity`: JSON 정수 1~Long.MAX_VALUE. 누락·null·0·음수·소수(`1.0` 포함)·범위 초과·숫자 문자열은 거부합니다. 상품 코드와 이름도 JSON 문자열이어야 합니다.
- 이미 등록된 코드의 정규화된 상품명이 다르면 변경 없이 409입니다. 서로 다른 코드는 같은 이름을 사용할 수 있습니다.

현재 오류 본문은 `code`, `message`, `path`이며 요청 추적용 `requestId`는 후속 단계에서 추가합니다.

| 업무 오류 | HTTP | code |
| --- | --- | --- |
| 입력·JSON 오류 | 400 | INVALID_REQUEST |
| 상품 없음 (출고·조회) | 404 | PRODUCT_NOT_FOUND |
| 등록된 상품명과 불일치 | 409 | PRODUCT_NAME_MISMATCH |
| 출고 가능한 재고 부족 | 409 | INSUFFICIENT_STOCK |
| 합산 재고 오버플로 | 409 | STOCK_LIMIT_EXCEEDED |
| 재고 행 누락·예상하지 못한 서버/DB 오류 | 500 | INTERNAL_ERROR |

지원하지 않는 메서드와 미디어 타입은 405·415를 유지합니다. 잠금 시간 초과·데드락의 503 세부 매핑은 아직 구현하지 않았으며 현재 일반 DB 오류는 500입니다. SQL·예외 이름·스택 트레이스를 오류 본문에 넣지 않습니다.

같은 상품을 3개 출고합니다. 예상 HTTP 상태는 `201`입니다.

```bash
curl -i -X POST http://localhost:8080/api/v1/stock/outbounds \
  -H 'Content-Type: application/json' \
  -d '{"productCode":"DEMO-001","quantity":3}'
```

응답은 입고와 동일한 형식이며 `movementType`은 `OUTBOUND`, `quantity`는 양수 3, `quantityAfter`는 7입니다. 출고는 상품명이 필요하지 않고 입력 수량 규칙은 입고와 같습니다. 전량 출고는 수량 0으로 성공하고, 초과 출고는 수량·수정 시각을 바꾸거나 이력을 남기지 않고 409를 반환합니다.

현재 재고를 조회합니다. 예상 HTTP 상태는 `200`이며, 신규 상품에 위 요청을 각각 한 번만 실행했다면 재고는 `7`입니다.

```bash
curl -i http://localhost:8080/api/v1/products/DEMO-001/stock
```

```json
{"productId":1,"productCode":"DEMO-001","name":"테스트 상품","quantity":7,"updatedAt":"2026-11-12T03:01:00Z"}
```

`updatedAt`은 상품이 아닌 재고 변경 시각입니다. 경로의 상품 코드에도 본문과 동일한 형식 검증을 적용합니다. 유효한 코드의 미등록 상품은 404이고, 기존 상품의 재고 행 누락은 500입니다. 누락을 재고 0으로 숨기거나 자동 복구하지 않습니다.

같은 입출고 요청을 다시 보내면 재고에 다시 반영됩니다. 재실행 시에는 새 상품 코드를 사용하거나 기존 수량을 고려해야 합니다.

### 5. 테스트 실행

테스트 소스를 `src/test`, `src/integrationTest`, `src/e2eTest`로 분리합니다. 로컬 JDK 21과 Docker 엔진을 준비한 뒤 실행합니다. 입고 규칙과 입력 검증의 단위 테스트는 Spring·DB 없이 실행합니다.

```bash
java -version

# 단위 테스트: 재고 규칙, 입력값 및 경계값 등
./gradlew test

# 실제 PostgreSQL 통합 테스트: 동시성, 롤백, 제약 조건 등
./gradlew integrationTest

# 실제 HTTP API E2E 테스트: 요청부터 DB 반영과 응답까지
./gradlew e2eTest

# 위 세 종류의 테스트를 포함한 전체 검증
./gradlew check
```

통합·E2E 테스트는 Testcontainers로 별도 `postgres:16.14` 컨테이너를 생성합니다. 실행 중인 개발 DB는 사용하지 않으며 Compose를 먼저 켤 필요가 없습니다. Docker를 사용할 수 없으면 테스트를 건너뛰지 않고 실패합니다.

통합 테스트는 기반 스키마 검증 외에 입출고, 신규 등록 경쟁, 이름 충돌, 동시 입고·출고, 이력 실패 시 롤백, 단일 SELECT 조회와 행 잠금 분리를 검증합니다. E2E는 실제 HTTP 입고→출고→조회, 입력 경계, 400·404·409·500과 커밋 실패 응답을 DB 상태와 비교합니다. 혼합 입출고·동시 HTTP 변경·잠금 시간 초과는 아직 검증하지 않았습니다.

`check`는 세 종류의 테스트 작업에 의존합니다. Core 통합 테스트는 API 모듈의 `db/migration/**`만 테스트 리소스로 복사합니다. DDL은 하나만 유지하고 Core 운영 코드가 API에 의존하지 않도록 합니다.

`@Testcontainers`는 테스트 DB의 생명주기를 관리하고 `@DynamicPropertySource`는 해당 컨테이너 접속 정보를 애플리케이션에 전달합니다. `@SpringBootTest`의 `NONE`은 서버 없는 기동 검증, `RANDOM_PORT`는 임의 포트의 실제 HTTP 서버 검증에 사용합니다.

Gradle Wrapper에 실행 권한이 없어 `Permission denied`가 발생할 경우 `chmod +x gradlew` 후 다시 실행합니다. 제출 전에는 저장소에 실행 권한이 올바르게 반영되어 있는지도 확인합니다.

### 6. 종료 및 재실행

```bash
# 컨테이너만 정지
docker compose stop

# 정지한 컨테이너 다시 실행
docker compose start

# 컨테이너와 네트워크 제거
docker compose down
```

DB 데이터는 Compose 프로젝트의 `postgres-data` named volume에 저장합니다. `down` 후에도 데이터가 유지되며, `docker compose up -d --wait`로 다시 실행할 수 있습니다.

**개발 DB 데이터를 모두 삭제하고 초기화할 때만** 아래 명령을 사용합니다.

```bash
docker compose down -v
docker compose up -d --wait
```

### 7. DDL 및 제출 전 확인

- DDL 기준은 `inventory-api/src/main/resources/db/migration/V1__create_inventory_schema.sql`입니다. 애플리케이션 기동 시 Flyway가 적용하며 Core 통합 테스트도 같은 파일을 사용합니다. DB Compose 단독 실행으로는 적용되지 않습니다.
- JPA의 `ddl-auto=validate`는 엔티티와 스키마가 맞지 않으면 기동을 실패시킵니다. Product·Inventory·StockMovement 매핑을 실제 마이그레이션에 대조합니다. `open-in-view=false`로 응답 처리 중 DB 접근을 제한합니다.
- DB 생성 시각은 `TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP`를 사용해 네이티브 상품·재고 INSERT에도 채웁니다. 이 기본값이 없으면 시각을 생략한 INSERT는 NOT NULL 제약으로 실패합니다. 대안은 모든 INSERT에서 시각을 명시하는 방식입니다. `CURRENT_TIMESTAMP`는 트랜잭션 시작 시각이며 커밋 순서를 뜻하지 않습니다. 수정 시각 자동 갱신 트리거는 없으며 재고 엔티티의 입고 메서드가 `updatedAt`을 갱신합니다. 이력의 `createdAt`은 JPA 저장 전 애플리케이션에서 채웁니다. JDBC의 시각 변환 기준은 UTC입니다.
- FK는 참조 무결성만 보장합니다. 입고 서비스가 신규 상품·재고·이력을 한 트랜잭션으로 생성합니다. 기존 상품의 재고 누락은 500으로 처리하며 자동 복구하지 않습니다.
- 새로 clone한 환경에서 환경 파일 복사 → Compose 빌드·실행 → API 호출 → 테스트 실행을 검증합니다.
- 재시작 후 DB 데이터 유지 여부와 종료 절차를 확인합니다.
- 검증한 Java·Docker·PostgreSQL 버전, 실행 명령, 테스트 결과와 미검증 사항을 기록합니다.
- 후속 단계에서 업무 기능·애플리케이션 이미지·CI 검증 결과를 갱신합니다.

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

미검증 범위는 요청 추적, 잠금 시간 초과·데드락 분류와 응답, 혼합 입출고·동시 HTTP 변경, 애플리케이션 이미지·CI와 새 clone의 전체 실행입니다. 새 라이브러리·트랜잭션 전파 옵션·스키마 변경은 없습니다.

## 입고 처리와 트랜잭션

1. HTTP DTO를 Core의 `InboundCommand`로 변환하며 입력 검증·상품명 정규화를 끝냅니다. `@Valid`는 누락·null·양수 조건을 HTTP 경계에서 검사하고, Core도 직접 호출에 대비해 업무 입력을 검사합니다. Jackson은 소수·숫자 문자열을 정수로, 숫자·boolean을 문자열로 자동 변환하지 않도록 명시합니다. 이 설정이 없으면 잘못된 JSON 타입이 변환 후 유효한 값처럼 보일 수 있습니다.
2. Controller가 Spring 프록시를 통해 공개 `StockService.inbound()`를 호출합니다. `@Transactional`의 기본 REQUIRED로 상품·재고·이력을 하나의 트랜잭션에 묶고, Hikari의 READ COMMITTED 설정을 사용합니다. 내부 직접 호출에 트랜잭션 생성을 기대하지 않으며 REQUIRES_NEW·MANDATORY·자동 재시도는 사용하지 않습니다.
3. 상품을 조회하고 없을 때만 `INSERT ... ON CONFLICT(product_code) DO NOTHING`을 실행한 다음 **별도 SELECT**로 읽습니다. READ COMMITTED에서 충돌 INSERT가 기다린 트랜잭션의 행을 최초 명령 스냅샷으로 읽을 수 있다고 가정하지 않습니다. [PostgreSQL 격리 수준 설명](https://www.postgresql.org/docs/16/transaction-iso.html)을 기준으로 실제 대기 테스트를 수행합니다.
4. 정규화한 이름이 일치하면 신규 등록 경로에서만 재고 0 행을 `ON CONFLICT(product_id) DO NOTHING`으로 확보합니다. 기존 상품의 재고 누락을 자동 복구하면 데이터 불일치를 숨길 수 있으므로 오류로 처리합니다. PostgreSQL 전용 충돌 구문은 Repository에 두고 값을 바인딩합니다. JPQL이 이 충돌 구문을 제공하지 않아 이 두 INSERT만 네이티브 SQL입니다.
5. `@Lock(PESSIMISTIC_WRITE)` 조회로 재고 행의 쓰기를 조율합니다. 재고를 일반 조회한 뒤 다시 잠그는 흐름이 없어 영속성 컨텍스트의 오래된 수량을 사용하는 것을 피합니다. `Inventory.inbound()`는 양수·오버플로를 검사한 후에만 수량과 수정 시각을 바꿉니다. `Long.MAX_VALUE - 현재 수량`과 비교하므로 덧셈 자체가 먼저 오버플로하지 않습니다.
6. 변경 후 수량을 이력에 기록하고 JPA 변경 감지를 통해 함께 커밋합니다. IDENTITY ID를 받았다는 것만으로 성공 응답을 보내지 않으며 Service 프록시의 커밋까지 성공한 후 201을 반환합니다. 실패 시 이력뿐 아니라 신규 상품·재고와 기존 재고 변경도 롤백됩니다.

JPA 엔티티는 `@Entity`·`@Table`·`@Column`으로 DDL과 매핑하고 IDENTITY를 DB 자동 생성 ID에 대응시킵니다. 이력 유형은 `@Enumerated(STRING)`으로 저장해 enum 순서에 의존하지 않습니다. 참조 ID만 보유하고 불필요한 양방향 관계·이력 컬렉션은 만들지 않았습니다. API는 엔티티 대신 별도의 record 응답을 반환합니다.

유일성 오류를 잡고 같은 트랜잭션을 계속 사용하는 대안은 PostgreSQL의 실패한 트랜잭션 상태를 해결하지 못합니다. 별도 트랜잭션 재시도나 낙관적 잠금도 대안이지만 충돌 처리·재시도 정책이 필요합니다. 현재는 UNIQUE와 충돌 무시 INSERT, 재고 행 잠금으로 조율합니다. 상품·재고를 합치면 생성과 JOIN 비용은 줄지만 현재 합의한 책임 분리를 유지합니다.

현재 잠금 대기 시간 제한은 애플리케이션에서 설정하지 않습니다. 3초 후보값의 트랜잭션 범위 `lock_timeout`과 503 응답 검증은 후속 단계에서 추가합니다. 커밋 이후 응답 유실은 롤백되지 않으며 요청 재전송도 별도 입고입니다.

## 출고와 조회 설계

출고는 별도의 공개 `StockService.outbound()`에 기본 `@Transactional`을 적용합니다. 상품이 없으면 404에 해당하는 업무 예외를 내고, 상품은 있지만 재고가 없으면 내부 불일치로 처리합니다. 입고와 동일한 `PESSIMISTIC_WRITE` 조회로 재고를 처음 읽고, `Inventory.outbound()`가 양수·잔여 수량을 검사한 뒤에만 차감합니다. 이력 수량은 양수이고 유형이 OUTBOUND이며 재고·수정 시각·이력은 함께 커밋하거나 롤백합니다.

쓰기 잠금 없이 수량을 읽어 검사한 다음 차감하면 여러 요청이 같은 수량을 통과할 수 있습니다. 현재 구현은 같은 재고 행의 쓰기를 조율합니다. 대안은 조건부 UPDATE의 영향 행 수를 검사하는 방식이나 낙관적 잠금이며, 후자는 충돌 응답·재시도 정책이 필요합니다. 새로운 잠금 전략이나 재시도는 추가하지 않았습니다.

조회는 `InventoryQueryService`가 상품 코드 검증 후 한 번의 LEFT JOIN SELECT로 record 프로젝션을 반환합니다. INNER JOIN은 재고가 누락된 상품을 미등록 상품과 구분하기 어렵기 때문에 사용하지 않았습니다. 조회용 `StockSnapshot.quantity`는 조인 누락을 감지할 수 있도록 Long이며, 서비스에서 null을 검사한 후 성공 응답의 long 수량으로 변환합니다.

엔티티 관계를 추가하지 않고 [Hibernate의 명시적 엔티티 조인](https://docs.hibernate.org/orm/6.5/querylanguage/html_single/#explicit-root-joins)을 사용하므로 이 구문은 현재 Hibernate 지원에 의존합니다. 대안은 JPA 연관관계 매핑 또는 SQL 프로젝션입니다. 상품·재고를 두 개의 별도 SELECT로 조회하면 READ COMMITTED에서 서로 다른 시점의 데이터를 조합할 수 있으므로 단일 SELECT로 유지합니다.

조회에 쓰기 잠금이나 별도 트랜잭션 옵션은 없습니다. 일반 SELECT는 커밋된 스냅샷을 읽으며 [PostgreSQL의 행 잠금](https://www.postgresql.org/docs/16/explicit-locking.html#LOCKING-ROWS)이 보유된 동안에도 실행할 수 있습니다. DDL 등 더 강한 테이블 잠금까지 어떤 상황에서도 기다리지 않는다는 뜻은 아닙니다. 조회의 추가 트랜잭션이 없어도 단일 SELECT의 스냅샷 일관성을 이용할 수 있습니다.

## 범위와 한계

- 단일 고객사·창고 구분 없는 상품별 총재고를 관리합니다.
- 상품 수정·삭제, 입출고 이력 수정·삭제, 관리자 화면은 제외합니다.
- 요청 재전송을 막는 멱등성과 자동 재시도는 제외합니다. 같은 입출고 요청을 두 번 보내면 별개의 작업입니다.
- 커밋 이후 응답이 유실되면 재요청으로 중복 반영될 수 있습니다. 향후 멱등성 키 기반 처리를 검토할 수 있습니다.
- requestId는 요청 추적용이며 중복 처리 방지 키가 아닙니다.
- 창고·옵션·멀티테넌트는 향후 확장 검토 사항이며 현재 지원 기능이 아닙니다.
