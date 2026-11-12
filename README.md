# DeepFine Backend Assignment — Inventory API

재고 관리 MVP 과제입니다. 현재는 1단계인 프로젝트 기반·DB 스키마·기동 검증을 완료했습니다. 입고·출고·조회 API와 업무 로직은 아직 구현하지 않았습니다.

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

## API 설계 (미구현)

| HTTP 메서드 | 경로 | 기능 |
| --- | --- | --- |
| POST | /api/v1/stock/inbounds | 신규 또는 기존 상품 입고 |
| POST | /api/v1/stock/outbounds | 기존 상품 출고 |
| GET | /api/v1/products/{productCode}/stock | 상품 정보와 현재 재고 조회 |

입출고 성공은 201, 조회 성공은 200으로 설계합니다. 실제 요청·응답 예시와 오류 목록은 구현 검증 후 보완합니다.

## 실행·DDL·검증

> 현재 Compose에는 DB만 포함됩니다. 애플리케이션은 Java 21로 로컬 실행합니다. 애플리케이션 이미지·전체 Compose 실행·CI는 후속 단계에서 구성합니다. 아래 API 호출 예시는 구현 예정 계약이며 현재 성공하지 않습니다.

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

아래는 **후속 구현 후 실행할 계약 예시**입니다. 현재는 엔드포인트가 없으므로 404를 반환합니다.

신규 상품을 10개 입고합니다. 예상 HTTP 상태는 `201`입니다.

```bash
curl -i -X POST http://localhost:8080/api/v1/stock/inbounds \
  -H 'Content-Type: application/json' \
  -d '{"productCode":"DEMO-001","name":"테스트 상품","quantity":10}'
```

같은 상품을 3개 출고합니다. 예상 HTTP 상태는 `201`입니다.

```bash
curl -i -X POST http://localhost:8080/api/v1/stock/outbounds \
  -H 'Content-Type: application/json' \
  -d '{"productCode":"DEMO-001","quantity":3}'
```

현재 재고를 조회합니다. 예상 HTTP 상태는 `200`이며, 신규 상품에 위 요청을 각각 한 번만 실행했다면 재고는 `7`입니다.

```bash
curl -i http://localhost:8080/api/v1/products/DEMO-001/stock
```

같은 입출고 요청을 다시 보내면 재고에 다시 반영됩니다. 재실행 시에는 새 상품 코드를 사용하거나 기존 수량을 고려해야 합니다.

### 5. 테스트 실행

테스트 소스를 `src/test`, `src/integrationTest`, `src/e2eTest`로 분리합니다. 로컬 JDK 21과 Docker 엔진을 준비한 뒤 실행합니다. 현재 단위 테스트는 없으며 업무 기능과 함께 추가합니다.

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

현재 통합 테스트는 Flyway 재실행, DB 기본값, PK·UNIQUE·FK·CHECK, BIGINT 경계, 보조 평면 유니코드 이름 길이, 애플리케이션 기동을 검증합니다. E2E는 실제 HTTP 서버의 미등록 경로 404와 마이그레이션 완료만 확인합니다. 업무 API·동시성·롤백을 검증한 것은 아닙니다.

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
- JPA의 `ddl-auto=validate`는 엔티티와 스키마가 맞지 않으면 기동을 실패시킵니다. 현재 엔티티는 아직 없어 전체 매핑 검증은 후속 단계에서 수행합니다. `open-in-view=false`로 응답 처리 중 DB 접근을 제한합니다.
- DB 생성 시각은 `TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP`를 사용해 네이티브 INSERT에도 채웁니다. 이 기본값이 없으면 시각을 생략한 INSERT는 NOT NULL 제약으로 실패합니다. 대안은 모든 INSERT에서 시각을 명시하는 방식입니다. `CURRENT_TIMESTAMP`는 트랜잭션 시작 시각이며 커밋 순서를 뜻하지 않습니다. 수정 시각 자동 갱신 트리거는 없으며 후속 엔티티 변경에서 갱신합니다. JDBC의 시각 변환 기준은 UTC입니다.
- FK는 참조 무결성만 보장합니다. 상품마다 재고가 반드시 하나 존재한다는 조건은 후속 서비스의 원자적 생성으로 보장해야 합니다.
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

이 결과는 1단계 기반 검증입니다. 엔티티 매핑, 입출고 API, 재고 원자성·동시성·업무 롤백, 업무 HTTP E2E, 애플리케이션 이미지·CI와 깨끗한 clone에서의 전체 실행은 아직 검증하지 않았습니다.

## 범위와 한계

- 단일 고객사·창고 구분 없는 상품별 총재고를 관리합니다.
- 상품 수정·삭제, 입출고 이력 수정·삭제, 관리자 화면은 제외합니다.
- 요청 재전송을 막는 멱등성과 자동 재시도는 제외합니다. 같은 입출고 요청을 두 번 보내면 별개의 작업입니다.
- 커밋 이후 응답이 유실되면 재요청으로 중복 반영될 수 있습니다. 향후 멱등성 키 기반 처리를 검토할 수 있습니다.
- requestId는 요청 추적용이며 중복 처리 방지 키가 아닙니다.
- 창고·옵션·멀티테넌트는 향후 확장 검토 사항이며 현재 지원 기능이 아닙니다.
