# DeepFine Backend Assignment — Inventory API

재고 관리 MVP 과제입니다. 입고·출고·재고 조회 API, 요청 추적, 잠금 오류 처리를 구현했습니다. Dockerfile·전체 Compose·CI 워크플로를 제공하며 실행 검증 범위는 단계별 기록에 구분합니다.

## 구현 범위

- 상품 코드 기준 현재 재고 조회
- 신규 상품 등록을 포함한 입고
- 음수 재고를 허용하지 않는 출고
- 재고 변경과 성공한 입출고 이력의 원자적 저장
- 동시 신규 등록과 동시 입출고의 정합성 검증

## 기술 및 구조

Java 21, Spring Boot 3.3.0, JPA, PostgreSQL 16.14, Gradle 8.8 Wrapper(Groovy DSL)를 사용합니다. Flyway 10.10.0, Testcontainers 1.19.8, Lombok 1.18.32를 포함한 의존성 버전은 Spring Boot BOM으로 관리합니다.

- inventory-api: HTTP 요청·응답, 검증, 예외 처리, 애플리케이션 실행
- inventory-core: 상품·재고 규칙, 입출고 처리, 영속성
- product: 상품 기본 정보
- inventory: 상품당 하나의 현재 재고
- stock_movement: 성공한 입출고 이력

Gradle 의존성은 `inventory-api → inventory-core`입니다. 모듈을 나누지만 실행 애플리케이션과 DB는 각각 하나입니다. Controller는 HTTP 경계, Service는 트랜잭션 조율, Entity는 재고 규칙, Repository는 DB 접근을 담당합니다. Core는 JPA에 의존하며 HTTP DTO·상태 코드는 API 모듈에 둡니다. 구체적인 필요가 없는 서비스 인터페이스나 도메인·영속성 모델 중복은 만들지 않았습니다.

Java 파일은 역할별 하위 패키지로 분류합니다. 아래 경로는 API의 `com.deepfine.inventory.api`, Core의 `com.deepfine.inventory.core` 아래를 기준으로 하며 파일명에서 `.java`는 생략했습니다.

| 모듈 | 하위 패키지 경로 | 클래스 |
| --- | --- | --- |
| API | controller | StockController, ProductController |
| API | dto/request | InboundRequest, OutboundRequest |
| API | dto/response | MovementResponse, StockResponse, ApiError |
| API | exception | ApiExceptionHandler, DatabaseFailureClassifier |
| API | config | JacksonConfiguration |
| API | filter | RequestIdFilter |
| Core | service | StockService, InventoryQueryService |
| Core | repository | ProductRepository, InventoryRepository, StockMovementRepository, PostgresTransactionRepository |
| Core | entity | Product, Inventory, StockMovement |
| Core | dto/command | InboundCommand, OutboundCommand |
| Core | dto/result | StockMovementResult, StockSnapshot |
| Core | enums | MovementType, StockErrorCode |
| Core | exception | StockException |
| Core | validator | ProductCodeValidator |

`InventoryApplication`은 하위 패키지 탐색의 시작점인 `com.deepfine.inventory`에 둡니다. 단위 테스트는 대상 클래스의 패키지에 맞추고, DB 통합·HTTP E2E는 기존 검증 범위를 유지합니다. DTO의 `toCommand()`·`from()`이 변환을 담당하므로 별도 mapper 클래스는 두지 않습니다.

`StockMovement.inbound()`·`outbound()`는 서로 다른 패키지의 Service가 호출할 수 있도록 `public static`입니다. 같은 패키지에서만 접근하는 방식은 Service와 Entity를 한 패키지에 둘 때 가능하지만 현재 역할별 분류에서는 컴파일할 수 없습니다. 엔티티의 JPA 기본 생성자는 `protected`, 내부 공통 생성 메서드는 `private`로 유지합니다.

상품 정보와 재고 상태의 책임을 구분하기 위해 테이블을 분리합니다. 현재는 1:1 구조이고 창고·옵션별 재고는 제외합니다. 향후 재고 기능이 확대되면 상품 정보에 미치는 영향을 줄이고 재고 영역 중심으로 확장할 수 있습니다. 분리에 따른 JOIN과 동시 생성 비용은 있으며, 분리 자체가 동시성이나 성능을 보장하는 것은 아닙니다.

## API와 구현 상태

| HTTP 메서드 | 경로 | 기능 | 상태 |
| --- | --- | --- | --- |
| POST | /api/v1/stock/inbounds | 신규 또는 기존 상품 입고 | 구현 |
| POST | /api/v1/stock/outbounds | 기존 상품 출고 | 구현 |
| GET | /api/v1/products/{productCode}/stock | 상품 정보와 현재 재고 조회 | 구현 |

입출고 성공은 201, 조회 성공은 200입니다.

## 실행·DDL·검증

Compose로 API 한 개와 PostgreSQL 한 개를 실행합니다. 이미지 내부의 JDK 21에서 소스를 빌드하므로 이 방식에는 호스트 JDK·Gradle·PostgreSQL 설치가 필요하지 않습니다. 로컬 Gradle 테스트·IDE 실행에는 JDK 21이 필요합니다.

### 1. 준비 사항

- Git
- Docker와 Docker Compose v2: Docker 엔진이 실행 중이어야 합니다.
- 검증 스크립트 실행 시 Bash·curl
- 로컬에서 Gradle 테스트를 실행할 경우 JDK 21
- 최초 실행 시 이미지와 의존성을 내려받을 수 있는 네트워크 환경

### 2. 저장소 내려받기

```bash
git clone https://github.com/keeepmin94/deepfine-backend-assignment.git
cd deepfine-backend-assignment
```

이후 명령은 저장소 최상위 디렉터리에서 실행합니다. HTTPS 주소를 사용하며 저장소 접근에 필요한 GitHub 인증은 별도로 준비합니다.

### 3. 환경 설정 및 실행

Docker 실행 상태와 Compose 설치를 확인합니다.

```bash
docker version
docker compose version
```

환경 설정 예시를 복사하고 로컬 개발용 비밀번호를 변경합니다. `.env`는 Git에 올리지 않습니다. Compose는 `.env`를 읽어 필요한 값만 각 컨테이너의 환경변수로 전달합니다.

```bash
cp .env.example .env
```

`.env`의 `POSTGRES_PASSWORD`를 로컬 개발용 값으로 변경한 뒤 실행합니다.

```bash
docker compose up --build -d --wait
docker compose ps
docker compose logs -f api db
```

DB는 `postgres:16.14` 이미지를 사용하며 API는 DB의 `pg_isready` healthcheck 성공 후 시작합니다. 로그 확인은 `Ctrl+C`로 종료할 수 있으며 컨테이너는 계속 실행됩니다. DB healthcheck나 API 컨테이너가 running인 것만으로 API 검증 완료를 판단하지 않고 시작 로그·HTTP 호출 또는 아래 검증 스크립트로 확인합니다. Flyway와 JPA 스키마 검증은 API 기동 시 수행합니다.

| 환경변수 | 기본값·의미 |
| --- | --- |
| POSTGRES_USER | inventory |
| POSTGRES_PASSWORD | 필수. 예제 값은 로컬 개발용이며 실제 비밀정보가 아닙니다. |
| DB_PORT | 5432. DB 호스트 포트이며 127.0.0.1에만 바인딩합니다. |
| DB_URL | 로컬 앱용 jdbc:postgresql://localhost:5432/inventory. Compose API에는 db:5432 주소를 별도로 전달합니다. |
| SERVER_PORT | 호스트 API 포트 8080. Compose 컨테이너 내부는 8080으로 고정합니다. |
| STOCK_LOCK_TIMEOUT_MS | 3000. 입출고 트랜잭션의 잠금 획득 대기 제한(ms). 양의 int 값만 허용합니다. |

DB 이름은 `inventory`입니다. 두 호스트 포트는 127.0.0.1에만 공개합니다. 로컬 앱에서 `DB_PORT`를 변경하면 `DB_URL`의 포트도 맞춰야 합니다. Compose API는 내부 db:5432로 접속하므로 호스트 DB 포트 변경과 무관합니다. DB 초기화 환경변수는 새 볼륨 생성 시 적용되므로 기존 볼륨의 사용자·비밀번호를 파일 수정만으로 바꿀 수는 없습니다.

IDE·로컬 Java로 실행하려면 DB만 Compose로 실행합니다. 전체 Compose가 이미 실행 중이면 `docker compose stop api`로 API 포트 충돌을 피합니다. 직접 작성하고 신뢰하는 `.env`를 셸로 읽거나 IDE 실행 설정에 같은 환경변수를 등록합니다.

```bash
docker compose up -d --wait db
set -a
source .env
set +a
./gradlew :inventory-api:bootRun
```

Java 17이 기본으로 선택된 환경에서는 먼저 `JAVA_HOME`과 `PATH`를 설치된 JDK 21로 설정합니다. Java toolchain도 21로 고정했으며 JDK 자동 다운로드 플러그인은 사용하지 않습니다.

전체 Compose 검증은 다음 명령으로 실행합니다. `.env`나 개발 DB 대신 예제 설정·임의 호스트 포트·고유 프로젝트와 새 볼륨을 사용합니다. 입출고·조회·오류 응답·재시작·컨테이너 재생성 후 데이터 보존을 검사하고, 종료 시 자신이 만든 컨테이너·네트워크·볼륨만 정리합니다.

```bash
./scripts/verify-compose.sh
```

### 4. API 호출 확인

아래 예시는 새 상품 코드를 사용해 순서대로 한 번씩 실행하는 흐름입니다. 먼저 API 주소를 설정합니다. `SERVER_PORT`를 변경했다면 주소의 포트도 맞춥니다. API 시작 로그를 확인하거나 조회가 응답한 뒤 입출고를 실행합니다.

```bash
API_URL=http://localhost:8080
```

신규 상품을 10개 입고합니다. 예상 HTTP 상태는 `201`입니다.

```bash
curl -i -X POST "$API_URL/api/v1/stock/inbounds" \
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

오류 본문은 `code`, `message`, `path`, `requestId`입니다. 서버가 요청마다 UUID를 생성하고 성공·실패 응답의 `X-Request-Id` 헤더와 관련 로그에 넣습니다. 클라이언트가 보낸 같은 이름의 헤더는 재사용하지 않습니다.

```json
{"code":"INSUFFICIENT_STOCK","message":"출고 가능한 재고가 부족합니다.","path":"/api/v1/stock/outbounds","requestId":"서버가 생성한 UUID"}
```

| 업무 오류 | HTTP | code |
| --- | --- | --- |
| 입력·JSON 오류 | 400 | INVALID_REQUEST |
| 상품 없음 (출고·조회) | 404 | PRODUCT_NOT_FOUND |
| 등록된 상품명과 불일치 | 409 | PRODUCT_NAME_MISMATCH |
| 출고 가능한 재고 부족 | 409 | INSUFFICIENT_STOCK |
| 합산 재고 오버플로 | 409 | STOCK_LIMIT_EXCEEDED |
| 잠금 획득 불가 (현재 경로의 잠금 대기 시간 초과) | 503 | STOCK_BUSY |
| DB의 데드락 감지로 트랜잭션 중단 | 503 | TRANSACTION_RETRY_REQUIRED |
| 재고 행 누락·예상하지 못한 서버/DB 오류 | 500 | INTERNAL_ERROR |

지원하지 않는 메서드와 미디어 타입은 405·415를 유지합니다. DB 예외 체인과 JDBC nextException에서 SQLSTATE `55P03`·`40P01`만 위 503으로 분류하고, 제약 위반 등을 모두 재시도 가능한 오류로 취급하지 않습니다. 나머지 예상하지 못한 DB 오류는 500입니다. SQL·예외 이름·스택 트레이스를 오류 본문에 넣지 않습니다. 서버는 자동 재시도하지 않습니다.

같은 상품을 3개 출고합니다. 예상 HTTP 상태는 `201`입니다.

```bash
curl -i -X POST "$API_URL/api/v1/stock/outbounds" \
  -H 'Content-Type: application/json' \
  -d '{"productCode":"DEMO-001","quantity":3}'
```

응답은 입고와 동일한 형식이며 `movementType`은 `OUTBOUND`, `quantity`는 양수 3, `quantityAfter`는 7입니다. 출고는 상품명이 필요하지 않고 입력 수량 규칙은 입고와 같습니다. 전량 출고는 수량 0으로 성공하고, 초과 출고는 수량·수정 시각을 바꾸거나 이력을 남기지 않고 409를 반환합니다.

현재 재고를 조회합니다. 예상 HTTP 상태는 `200`이며, 신규 상품에 위 요청을 각각 한 번만 실행했다면 재고는 `7`입니다.

```bash
curl -i "$API_URL/api/v1/products/DEMO-001/stock"
```

```json
{"productId":1,"productCode":"DEMO-001","name":"테스트 상품","quantity":7,"updatedAt":"2026-11-12T03:01:00Z"}
```

`updatedAt`은 상품이 아닌 재고 변경 시각입니다. 경로의 상품 코드에도 본문과 동일한 형식 검증을 적용합니다. 유효한 코드의 미등록 상품은 404이고, 기존 상품의 재고 행 누락은 500입니다. 누락을 재고 0으로 숨기거나 자동 복구하지 않습니다.

같은 입출고 요청을 다시 보내면 재고에 다시 반영됩니다. 재실행 시에는 새 상품 코드를 사용하거나 기존 수량을 고려해야 합니다.

재고 7에서 8개 출고는 `409 / INSUFFICIENT_STOCK`, 미등록 코드 조회는 `404 / PRODUCT_NOT_FOUND`, 숫자 문자열 수량은 `400 / INVALID_REQUEST`입니다. 아래 세 요청은 재고·이력을 변경하지 않습니다.

```bash
curl -i -X POST "$API_URL/api/v1/stock/outbounds" \
  -H 'Content-Type: application/json' \
  -d '{"productCode":"DEMO-001","quantity":8}'

curl -i "$API_URL/api/v1/products/DEMO-MISSING/stock"

curl -i -X POST "$API_URL/api/v1/stock/inbounds" \
  -H 'Content-Type: application/json' \
  -d '{"productCode":"DEMO-001","name":"테스트 상품","quantity":"1"}'
```

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

통합 테스트는 입출고·신규 등록 경쟁·롤백·조회·행 잠금, 기본 3초 제한과 연결 설정 복원을 검증합니다. 혼합 입출고는 충분한 재고·출고 불가능·순서에 따라 출고 성공 수가 달라지는 재고를 구분합니다. E2E는 실제 HTTP 동시 신규 등록·혼합 입출고의 결과와 DB를 대조하며, 입력·업무 오류·요청 ID·시간 초과·커밋 실패·데드락도 검사합니다.

동시 요청의 안정성은 현재 범위에서 갱신 유실·초과 출고·이력 불일치·부분 저장을 방지하고, 경합으로 거부된 요청을 올바른 오류로 응답하는지 확인합니다. 다음 8개 작업자 시나리오 12종을 각각 3회 반복해 한 번의 전체 검증에서 36회 실행합니다. 각 작업자는 독립 트랜잭션을 사용하고 DB 풀 최대 크기는 8입니다.

| 경합 상황 | 검증 경로 | 정확한 기대 결과 |
| --- | --- | --- |
| 같은 코드에 2개씩 8건 신규 입고 | Core·실제 HTTP | 상품·재고 각 1행, 최종 16, 이력 8건 |
| 같은 코드에 두 상품명으로 각 4건 입고 | Core | 등록된 이름의 4건만 성공, 나머지는 이름 불일치 |
| 기존 재고 10에 2개씩 8건 입고 | Core | 모두 성공, 최종 26, 초기 입고 포함 이력 9건 |
| 재고 24 또는 10에 3개씩 8건 출고 | Core | 각각 성공 8건·최종 0 또는 성공 3건·실패 5건·최종 1 |
| 충분·부족·순서 의존 재고에서 입고 4건과 출고 4건 혼합 | Core·실제 HTTP, 각 3종 | 성공한 입고 합계−출고 합계로 최종 수량·이력 정확히 대조 |

별도로 실제 DB 잠금 대기와 해제를 조율합니다. `pg_stat_activity`·`pg_blocking_pids`로 지정한 선행 트랜잭션이 대기 원인인지 확인한 뒤 커밋·롤백하며, 다음 세 시나리오를 각각 3회 반복합니다. 선행 상태는 테스트 전용 JDBC 트랜잭션으로 만들고 대기자는 실제 Core 서비스 또는 HTTP 경로로 실행합니다. 두 HTTP 요청 모두를 실행한 테스트라고 해석하지 않습니다.

| 선행 트랜잭션 | 대기 요청 | 해제 후 기대 결과 |
| --- | --- | --- |
| 신규 상품명 A·재고 9·입고 이력을 미커밋 상태로 만든 뒤 롤백 | 같은 코드·상품명 B로 5개 입고 | B 이름으로 정상 등록, 최종 5·이력 1건, 선행 데이터 부재 |
| 재고 5에서 4개 출고한 상태를 커밋 | 실제 HTTP로 3개 출고 | 최신 재고 1로 재검사해 409, 수량·수정 시각·이력 추가 변경 없음 |
| 같은 선행 출고 상태를 롤백 | 실제 HTTP로 3개 출고 | 복원된 재고 5에서 성공해 201, 최종 2·이력 2건, 롤백된 이력 부재 |

기존 테스트는 같은 상품의 잠금이 일반 조회·다른 상품 출고를 막지 않는지도 확인합니다. 실제 데드락을 3회 유도해 503·전체 롤백을 검사하고, 행 잠금·신규 등록 UNIQUE 충돌·커밋 단계 잠금 시간 초과도 검증합니다. 잠금 설정의 커밋·롤백 후 복원, 요청별 추적 ID 분리도 확인합니다. 모든 결과·예외를 수집하고 관측·결과 회수·작업자 종료에 제한 시간을 둡니다. 이 결과는 해당 PostgreSQL 환경의 정합성 검증이며 최대 동시 사용자 수·처리량·지연 시간 보장은 아닙니다.

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

### 7. 스키마와 데이터 제약

DDL 기준은 [V1__create_inventory_schema.sql](inventory-api/src/main/resources/db/migration/V1__create_inventory_schema.sql)입니다. 애플리케이션 기동 시 Flyway가 적용하며 Core 통합 테스트도 같은 파일을 사용합니다. DB Compose 단독 실행으로는 적용되지 않습니다. 모든 컬럼은 NOT NULL입니다.

| 테이블 | 컬럼 | 자료형·제약 |
| --- | --- | --- |
| product | id | BIGINT, GENERATED ALWAYS AS IDENTITY, PK |
| product | product_code / name | VARCHAR(64) UNIQUE / VARCHAR(200) |
| product | created_at / updated_at | TIMESTAMPTZ, DEFAULT CURRENT_TIMESTAMP |
| inventory | product_id | BIGINT, PK이자 product.id FK |
| inventory | quantity | BIGINT, DEFAULT 0, CHECK >= 0 |
| inventory | updated_at | TIMESTAMPTZ, DEFAULT CURRENT_TIMESTAMP |
| stock_movement | id | BIGINT, GENERATED ALWAYS AS IDENTITY, PK |
| stock_movement | product_id | BIGINT, product.id FK |
| stock_movement | movement_type | VARCHAR(10), CHECK IN ('INBOUND', 'OUTBOUND') |
| stock_movement | quantity / quantity_after | BIGINT, CHECK > 0 / BIGINT, CHECK >= 0 |
| stock_movement | created_at | TIMESTAMPTZ, DEFAULT CURRENT_TIMESTAMP |

- JPA의 `ddl-auto=validate`는 테이블·컬럼 매핑을 검사하며 스키마를 자동 변경하지 않습니다. UNIQUE·FK·CHECK 등 제약의 실제 동작은 PostgreSQL 통합 테스트로 별도 확인합니다. `open-in-view=false`로 HTTP 응답 처리까지 영속성 컨텍스트를 열어두지 않습니다.
- DB 생성 시각은 `TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP`를 사용해 네이티브 상품·재고 INSERT에도 채웁니다. 이 기본값이 없으면 시각을 생략한 INSERT는 NOT NULL 제약으로 실패합니다. 대안은 모든 INSERT에서 시각을 명시하는 방식입니다. `CURRENT_TIMESTAMP`는 트랜잭션 시작 시각이며 커밋 순서를 뜻하지 않습니다. 수정 시각 자동 갱신 트리거는 없으며 재고 엔티티의 입출고 메서드가 `updatedAt`을 갱신합니다. 이력의 `createdAt`은 JPA 저장 전 애플리케이션에서 채웁니다. JDBC의 시각 변환 기준은 UTC입니다.
- inventory의 PK는 상품당 재고 중복을 막고 FK는 참조 무결성만 보장합니다. 모든 상품에 재고가 존재하는 것은 입고 서비스가 한 트랜잭션으로 보장합니다. 기존 상품의 재고 누락은 500으로 처리하며 자동 복구하지 않습니다.
- 연쇄 삭제는 사용하지 않습니다. 초기 인덱스는 PK·상품 코드 UNIQUE에 필요한 것만 두며 이력 조회 API는 제공하지 않습니다. 입출고 이력의 수정·삭제 API도 없지만 DB 직접 변경까지 막는 감사 저장소는 아닙니다.

### 8. 검증 결과와 실행 범위

최신 전체 테스트는 동시성 대기·해제 경로 보강 후 `./gradlew --no-daemon clean check :inventory-api:bootJar`로 실행했습니다. macOS ARM64 / Homebrew Java 21.0.12.1, PostgreSQL 16.14 Linux ARM64에서 단위 68건·DB 통합 63건·실제 HTTP E2E 109건, **총 240건 성공**, 실패·오류·건너뜀 0건입니다. 19개 actionable task가 모두 실행됐고 실행 JAR도 생성했습니다. 패키지 정리 전 6단계에는 Linux ARM64 / Temurin 21.0.13+9에서도 전체 테스트를 수행했으며 당시 결과는 검증 기록에 구분합니다.

| 구분 | 확인한 범위 | 한계 |
| --- | --- | --- |
| 단위 | 입력·유니코드·재고 규칙·long 경계, BigInteger 모델 256연산 | Spring·DB 정합성의 근거로 사용하지 않음 |
| PostgreSQL 통합 | 실제 DDL 제약·신규 등록 경쟁·입출고·혼합 요청·롤백·행 잠금·연결 설정 복원 | PostgreSQL 16.14에서 검증, H2 사용 안 함 |
| 실제 HTTP E2E | 실제 서버·DB에서 입력·업무 오류·동시 변경·requestId·잠금 시간 초과·데드락·커밋 실패 | Mock 업무 경로로 대체하지 않음 |
| Compose | 소스 이미지 빌드·Flyway·입출고·조회·오류·재시작·컨테이너 재생성 후 보존 | Linux ARM64 실행, Lombok 적용 후 이미지 내부 소스 컴파일 재실행 |
| CI | check와 Compose 검증 workflow 구성, YAML 구문 확인 | 원격 GitHub Actions 실행·AMD64 실행 미검증 |

동시성 시나리오는 작업자 8개·최대 DB 풀 8개로 독립 트랜잭션을 사용하고 각 3회 반복합니다. 시작 조율·모든 결과와 예외 수집·제한된 대기 후 최종 수량, 성공·실패 수, 이력 수와 응답별 이력 일치를 확인합니다. 충분한 재고와 부족한 재고, 순서에 따라 성공 수가 달라지는 혼합 요청을 구분합니다. 자동 ID·시각을 커밋 순서로 가정하지 않습니다.

롤백 테스트는 테스트 DB의 지연 트리거로 재고 UPDATE 후 커밋 실패를 주입합니다. 기존 재고·수정 시각·이력 복원과 신규 상품·재고·이력 전체 취소, HTTP 500·503 응답을 확인합니다. 운영 스키마에는 오류 주입 트리거가 없습니다. Compose의 재고 부족 거부는 저장 실패 시 롤백 검증과 구분합니다.

실행 명령·환경·시나리오와 최초 실패 및 수정 기록은 [단계별 검증 기록](docs/verification-history.md)에 보존합니다. 테스트 통과는 모든 실행 순서·운영 처리량·사용자의 설계 이해를 보장하지 않습니다. 원격 push와 원격 clone 검증은 수행하지 않았습니다.

7단계에서는 로컬 커밋 `f276062`를 새 임시 경로에 clone하고 변경한 문서를 적용해 curl 예시 6개를 실제 검증했습니다. 순서대로 201·201·200·409·404·400, 응답 필드·요청 ID·한글 상품명·재고 7·성공 이력 2건을 확인했습니다. `stop/start`와 `down/up` 후에도 같은 상태를 확인하고 검증 프로젝트만 정리했습니다. 기존 환경과 분리하려고 고유 Compose 프로젝트·임의 호스트 포트를 사용했고 Docker 빌드 캐시를 재사용했습니다. Gradle은 `--offline check --dry-run`으로 세 테스트 작업의 연결만 확인했으며 이번 단계에서 전체 테스트를 다시 실행하지 않았습니다. 업무 코드·설정·의존성·DDL은 6단계와 같습니다.

## 이미지와 CI

`Dockerfile`은 digest로 고정한 Temurin 21.0.13+9의 JDK에서 소스를 빌드하고, JRE 단계에는 실행 JAR만 복사합니다. 호스트 JDK·기존 빌드 산출물에 의존하지 않기 위한 구성입니다. 호스트에서 JAR를 빌드해 복사하는 대안은 호스트 빌드 환경과 산출물 최신 여부를 별도로 관리해야 합니다. 최초 빌드는 이미지·Gradle·의존성 다운로드가 필요하며 네트워크·컴파일 오류가 있으면 실패합니다.

Compose의 DB healthcheck는 DB 접속 준비를 확인합니다. API는 이후 Flyway·JPA 검증을 거쳐 기동하므로 `depends_on`이나 `up --wait`만으로 HTTP 준비를 판단하지 않습니다. [검증 스크립트](scripts/verify-compose.sh)가 실제 조회 경로를 제한된 횟수로 확인하고 입출고는 한 번씩만 전송합니다.

[CI workflow](.github/workflows/ci.yaml)는 main push·pull request·수동 실행에서 Ubuntu 24.04 / Temurin 21.0.13+9로 `./gradlew --no-daemon clean check`와 `./scripts/verify-compose.sh`를 실행합니다. 어느 명령이든 실패하면 작업이 실패합니다. checkout·setup-java는 커밋 SHA로 고정하고 저장소 읽기 권한만 부여합니다. 현재는 로컬 검증 결과이며 원격 CI 성공을 주장하지 않습니다.

## 입고 처리와 트랜잭션

1. HTTP DTO를 Core의 `InboundCommand`로 변환하며 입력 검증·상품명 정규화를 끝냅니다. `@Valid`는 누락·null·양수 조건을 HTTP 경계에서 검사하고, Core도 직접 호출에 대비해 업무 입력을 검사합니다. Jackson은 소수·숫자 문자열을 정수로, 숫자·boolean을 문자열로 자동 변환하지 않도록 명시합니다. 이 설정이 없으면 잘못된 JSON 타입이 변환 후 유효한 값처럼 보일 수 있습니다.
2. Controller가 Spring 프록시를 통해 공개 `StockService.inbound()`를 호출합니다. `@Transactional`의 기본 REQUIRED로 상품·재고·이력을 하나의 트랜잭션에 묶고, Hikari의 READ COMMITTED 설정을 사용합니다. 내부 직접 호출에 트랜잭션 생성을 기대하지 않으며 REQUIRES_NEW·MANDATORY·자동 재시도는 사용하지 않습니다.
3. 상품을 조회하고 없을 때만 `INSERT ... ON CONFLICT(product_code) DO NOTHING`을 실행한 다음 **별도 SELECT**로 읽습니다. READ COMMITTED에서 충돌 INSERT가 기다린 트랜잭션의 행을 최초 명령 스냅샷으로 읽을 수 있다고 가정하지 않습니다. [PostgreSQL 격리 수준 설명](https://www.postgresql.org/docs/16/transaction-iso.html)을 기준으로 실제 대기 테스트를 수행합니다.
4. 정규화한 이름이 일치하면 신규 등록 경로에서만 재고 0 행을 `ON CONFLICT(product_id) DO NOTHING`으로 확보합니다. 기존 상품의 재고 누락을 자동 복구하면 데이터 불일치를 숨길 수 있으므로 오류로 처리합니다. JPQL이 제공하지 않는 PostgreSQL 충돌 처리 INSERT와 아래 lock_timeout 설정에 네이티브 SQL을 사용하며 Repository에 두고 값을 바인딩합니다.
5. `@Lock(PESSIMISTIC_WRITE)` 조회로 재고 행의 쓰기를 조율합니다. 재고를 일반 조회한 뒤 다시 잠그는 흐름이 없어 영속성 컨텍스트의 오래된 수량을 사용하는 것을 피합니다. `Inventory.inbound()`는 양수·오버플로를 검사한 후에만 수량과 수정 시각을 바꿉니다. `Long.MAX_VALUE - 현재 수량`과 비교하므로 덧셈 자체가 먼저 오버플로하지 않습니다.
6. 변경 후 수량을 이력에 기록하고 JPA 변경 감지를 통해 함께 커밋합니다. IDENTITY ID를 받았다는 것만으로 성공 응답을 보내지 않으며 Service 프록시의 커밋까지 성공한 후 201을 반환합니다. 업무 예외는 RuntimeException이고 DB 예외도 삼키지 않으므로 현재 예외 경로에서는 이력뿐 아니라 신규 상품·재고와 기존 재고 변경도 롤백됩니다.

JPA 엔티티는 `@Entity`·`@Table`·`@Column`으로 DDL과 매핑하고 IDENTITY를 DB 자동 생성 ID에 대응시킵니다. 이력 유형은 `@Enumerated(STRING)`으로 저장해 enum 순서에 의존하지 않습니다. 참조 ID만 보유하고 불필요한 양방향 관계·이력 컬렉션은 만들지 않았습니다. API는 엔티티 대신 별도의 record 응답을 반환합니다.

유일성 오류를 잡고 같은 트랜잭션을 계속 사용하는 대안은 PostgreSQL의 실패한 트랜잭션 상태를 해결하지 못합니다. 별도 트랜잭션 재시도나 낙관적 잠금도 대안이지만 충돌 처리·재시도 정책이 필요합니다. 현재는 UNIQUE와 충돌 무시 INSERT, 재고 행 잠금으로 조율합니다. 상품·재고를 합치면 생성과 JOIN 비용은 줄지만 현재 합의한 책임 분리를 유지합니다.

입출고는 트랜잭션 시작 직후 `lock_timeout`을 적용합니다. 기본 3초는 초기 후보값이며 측정한 최적값이 아닙니다. 아래 설정 설명과 검증 범위를 참고합니다. 커밋 이후 응답 유실은 롤백되지 않으며 요청 재전송도 별도 입고입니다.

## 출고와 조회 설계

출고는 별도의 공개 `StockService.outbound()`에 기본 `@Transactional`을 적용합니다. 상품이 없으면 404에 해당하는 업무 예외를 내고, 상품은 있지만 재고가 없으면 내부 불일치로 처리합니다. 입고와 동일한 `PESSIMISTIC_WRITE` 조회로 재고를 처음 읽고, `Inventory.outbound()`가 양수·잔여 수량을 검사한 뒤에만 차감합니다. 이력 수량은 양수이고 유형이 OUTBOUND이며 재고·수정 시각·이력은 함께 커밋하거나 롤백합니다.

쓰기 잠금 없이 재고 5를 두 요청이 읽고 각각 3을 출고하면 둘 다 검사를 통과해 재고 2를 덮어쓰고 이력에는 총 6이 출고될 수 있습니다. CHECK >= 0만으로는 이 상황을 막을 수 없습니다. 현재는 같은 재고 행을 잠근 요청이 커밋·롤백할 때까지 다른 변경이 기다리고, 대기 후 읽은 수량으로 다시 업무 검사를 합니다. 대안은 조건부 UPDATE의 영향 행 수를 검사하는 방식이나 낙관적 잠금이며, 후자는 충돌 응답·재시도 정책이 필요합니다.

조회는 `InventoryQueryService`가 상품 코드 검증 후 한 번의 LEFT JOIN SELECT로 record 프로젝션을 반환합니다. INNER JOIN은 재고가 누락된 상품을 미등록 상품과 구분하기 어렵기 때문에 사용하지 않았습니다. 조회용 `StockSnapshot.quantity`는 조인 누락을 감지할 수 있도록 Long이며, 서비스에서 null을 검사한 후 성공 응답의 long 수량으로 변환합니다.

엔티티 관계를 추가하지 않고 [Hibernate의 명시적 엔티티 조인](https://docs.hibernate.org/orm/6.5/querylanguage/html_single/#explicit-root-joins)을 사용하므로 이 구문은 현재 Hibernate 지원에 의존합니다. 대안은 JPA 연관관계 매핑 또는 SQL 프로젝션입니다. 상품·재고를 두 개의 별도 SELECT로 조회하면 READ COMMITTED에서 서로 다른 시점의 데이터를 조합할 수 있으므로 단일 SELECT로 유지합니다.

조회에 쓰기 잠금이나 별도 트랜잭션 옵션은 없습니다. 일반 SELECT는 커밋된 스냅샷을 읽으며 [PostgreSQL의 행 잠금](https://www.postgresql.org/docs/16/explicit-locking.html#LOCKING-ROWS)이 보유된 동안에도 실행할 수 있습니다. DDL 등 더 강한 테이블 잠금까지 어떤 상황에서도 기다리지 않는다는 뜻은 아닙니다. 조회의 추가 트랜잭션이 없어도 단일 SELECT의 스냅샷 일관성을 이용할 수 있습니다.

## 요청 추적과 잠금 오류

`RequestIdFilter`는 높은 우선순위의 `OncePerRequestFilter`로 동기 MVC 처리 전 요청 ID를 생성합니다. ID는 요청 속성·응답 헤더·MDC에 넣습니다. 오류 응답은 같은 요청 속성에서 ID를 읽고, 로그 패턴은 MDC의 ID를 출력합니다. 요청 종료·예외 발생 모두 `finally`에서 이전 MDC 전체를 복원하며 기존 컨텍스트가 없으면 비웁니다. 응답 코드·메서드·경로를 기록하고 요청 본문 전체나 쿼리 문자열은 기록하지 않습니다. 애플리케이션의 예상하지 못한 오류 스택은 예외 처리 계층에서 기록하며, 분류한 잠금 오류는 코드만 경고 로그로 남깁니다.

요청 ID를 클라이언트 입력 그대로 신뢰하는 대안 대신 서버에서 매번 생성합니다. 같은 업무 요청을 다시 보내도 새 ID가 생기고 별도 입출고로 처리됩니다. ID를 멱등성 키나 처리 결과 조회 기능으로 사용하지 않습니다. 현재 검증 범위는 동기 MVC API이며 비동기 작업의 MDC 전파 기능은 추가하지 않았습니다.

`PostgresTransactionRepository`는 `@Value`로 양의 밀리초 설정을 주입받습니다. 기본값은 3000이며 0·음수 또는 int 범위 밖의 설정은 정상 기동할 수 없습니다. Service의 기존 트랜잭션 안에서 첫 SQL로 `SELECT set_config('lock_timeout', :timeout, true)`를 실행하고 값을 바인딩합니다. 별도의 전파 옵션이나 트랜잭션은 만들지 않습니다. `true`는 [현재 트랜잭션에만 설정을 적용](https://www.postgresql.org/docs/16/functions-admin.html)하므로 커밋·롤백 후 원래 값으로 복원됩니다. 이 Repository 호출을 트랜잭션 밖으로 옮기면 설정이 다음 업무 SQL까지 유지되지 않으므로 현재 호출 위치가 중요합니다.

이 제한은 신규 상품 UNIQUE 충돌을 포함한 [각 잠금 획득 시도의 대기](https://www.postgresql.org/docs/16/runtime-config-client.html#GUC-LOCK-TIMEOUT)에 적용됩니다. SQL의 전체 실행 시간, 연결 풀 대기 또는 HTTP 전체 응답 시간 제한이 아닙니다. 대안인 세션 범위 SET은 풀 연결 재사용 시 설정이 남을 수 있고, statement_timeout은 잠금 외의 SQL 실행 시간도 포함해 의미가 다릅니다. 현재는 트랜잭션 범위의 잠금 제한을 선택했습니다.

예외 메시지 문구나 모든 DataAccessException을 기준으로 분류하지 않습니다. [PostgreSQL SQLSTATE](https://www.postgresql.org/docs/16/errcodes-appendix.html) `55P03`은 lock_not_available이고 현재 입출고 경로에서는 잠금 대기 시간 초과로 재현합니다. `40P01`은 deadlock_detected입니다. 실제 Spring/JPA 예외의 cause와 JDBC nextException을 따라 이 두 상태만 각각 STOCK_BUSY·TRANSACTION_RETRY_REQUIRED로 변환합니다. 나머지 DB 오류는 INTERNAL_ERROR로 유지합니다.

## 범위와 한계

- 단일 고객사·창고 구분 없는 상품별 총재고를 관리합니다.
- 상품 수정·삭제, 입출고 이력 조회·수정·삭제, 관리자 화면, 인증·권한은 제외합니다.
- 요청 재전송을 막는 멱등성과 자동 재시도는 제외합니다. 같은 입출고 요청을 두 번 보내면 별개의 작업입니다.
- 커밋 이후 응답이 유실되면 재요청으로 중복 반영될 수 있습니다. 향후 멱등성 키 기반 처리를 검토할 수 있습니다.
- requestId는 요청 추적용이며 중복 처리 방지 키가 아닙니다.
- 창고·옵션·멀티테넌트는 향후 확장 검토 사항이며 현재 지원 기능이 아닙니다.

같은 상품으로 요청이 몰리면 한 재고 행의 잠금이 병목이 될 수 있습니다. 잠금 대기 제한은 무한 대기를 줄이기 위한 값이며 처리량 개선이나 공정한 실행 순서를 보장하지 않습니다. 트랜잭션에서 잠금을 보유한 채 외부 서비스를 호출하지 않습니다. 운영 부하·처리량과 장애 시 복구 시간은 측정하지 않았습니다.

향후 창고별 재고가 필요하면 재고 식별자를 `(product_id, warehouse_id)` 등으로 확장하고 잠금 대상·이력의 창고 정보·전체 합계 조회 의미를 함께 정해야 합니다. 테이블을 분리했다는 이유만으로 현재 코드가 창고를 지원하는 것은 아닙니다.

향후 멱등성을 추가한다면 업무 요청 키와 입력 내용, 처리 결과를 재고 변경과 원자적으로 저장하고 같은 키의 동시 요청·다른 본문·응답 유실 정책을 정해야 합니다. requestId만 재사용하거나 단순 자동 재시도를 추가하는 것으로 해결되지 않습니다. 이 확장들은 현재 구현·검증 범위에 포함하지 않습니다.
