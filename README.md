# InstalFlow

Szkielet aplikacji backendowej Spring Boot. Projekt używa Maven Wrappera, a lokalne usługi PostgreSQL i testową pocztę uruchamia przez Docker Compose. Konfiguracja Compose i dane dostępu opisane poniżej są wyłącznie do lokalnego developmentu; nie używaj ich w produkcji ani z danymi produkcyjnymi.

## Wymagania

- JDK 25 (ustaw jako Project SDK oraz JDK dla Maven w IntelliJ IDEA)
- Docker Engine/Desktop z działającym Docker Compose v2 (`docker compose version`)
- wolne porty `5432`, `1025` i `8025`

Nie trzeba instalować Mavena globalnie — repozytorium zawiera Maven Wrapper.

## Pierwsze uruchomienie po klonowaniu

```bash
git clone <adres-repozytorium>
cd InstalFlow
cp .env.example .env
docker compose up -d
```

Compose automatycznie wczytuje plik `.env`. PostgreSQL używa bezhasłowego uwierzytelniania `trust` na potrzeby lokalnego developmentu. PostgreSQL i Mailpit działają w osobnych sieciach Compose, a ich porty są publikowane na hoście wyłącznie pod `127.0.0.1`: baza na porcie `5432`, SMTP na `1025`, panel Mailpit pod <http://127.0.0.1:8025>. Nie używaj tej konfiguracji dla produkcji ani danych produkcyjnych. Compose czeka na gotowość PostgreSQL przez healthcheck. Dane PostgreSQL są przechowywane w wolumenie Dockera.

## Budowanie i uruchamianie

W katalogu głównym repozytorium:

```bash
./mvnw clean verify
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Aby uruchomić testy:

```bash
./mvnw test
```

Profil `demo` korzysta z tej samej lokalnej bazy i usług Compose co `local`. Na pustej bazie wymaga bootstrapu właściciela opisanego poniżej. Można go włączyć podczas uruchamiania aplikacji:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

Profile wybiera się również w IntelliJ w konfiguracji uruchomieniowej aplikacji, w polu **Active profiles**. Profil `local` jest przeznaczony do pracy z usługami z Compose. Pełny zestaw testów wymaga działającego Dockera, ponieważ testy aplikacji, kont i migracji uruchamiają PostgreSQL przez Testcontainers.

Compose wczytuje `.env`, ale proces aplikacji uruchamiany z IntelliJ ani z powyższego polecenia Maven nie wczytuje tego pliku automatycznie. Domyślne wartości profilu `local` są zgodne z domyślną konfiguracją Compose. Jeśli zmienisz wartości w `.env`, ustaw odpowiadające im zmienne (`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PORT`, `MAILPIT_SMTP_PORT`) również w **Run Configuration → Environment variables** IntelliJ albo w środowisku powłoki przed startem Maven.

## Pierwszy właściciel i konta pracowników

Na pustej tabeli `app_user` aplikacja wymaga jednorazowego utworzenia konta właściciela. Przed pierwszym startem ustaw w środowisku procesu aplikacji obie zmienne `INSTALFLOW_BOOTSTRAP_OWNER_EMAIL` i `INSTALFLOW_BOOTSTRAP_OWNER_PASSWORD` (w IntelliJ: **Run Configuration → Environment variables**). Po utworzeniu konta usuń te zmienne z konfiguracji uruchomieniowej; hasło nie powinno trafić do Git ani do `.env` Compose. Podanie tylko jednej z tych zmiennych przerywa start. Po utworzeniu jakiegokolwiek użytkownika bootstrap nie tworzy kolejnego konta.

Wszystkie trasy kont są pod `/api/v1`. Klient pobiera `GET /auth/csrf`, zachowuje otrzymane cookie sesji i wysyła token w nagłówku `X-CSRF-TOKEN` przy `POST /auth/login`. Po udanym logowaniu pobiera nowy token przez `GET /auth/csrf` w tej samej sesji i używa go przy `POST /auth/logout` oraz pozostałych żądaniach zmieniających dane. `GET /auth/me` zwraca bieżące konto. Sesja trwa maksymalnie 30 minut bezczynności; po zmianie roli, hasła lub dezaktywacji sesje danego użytkownika tracą ważność. W profilach `local` i `demo` cookie lokalnego HTTP ma `Secure=false`; poza nimi domyślnie `Secure=true`.

Właściciel (`OWNER`) może utworzyć użytkownika przez `POST /users` z JSON `{ "email": "...", "password": "...", "role": "TECHNICIAN" }`, pobrać `GET /users` i `GET /users/{id}`, zmienić rolę przez `PATCH /users/{id}` z `{ "role": "OFFICE", "version": 0 }`, zmienić hasło przez `POST /users/{id}/password` z `{ "password": "...", "version": 0 }` oraz dezaktywować przez `POST /users/{id}/deactivate` z `{ "version": 0 }`. Wartość `version` pochodzi z ostatniej odpowiedzi użytkownika; nieaktualna wersja daje `409`. Ostatniego aktywnego właściciela nie można zdegradować ani dezaktywować. Adres email jest unikalny także po dezaktywacji.

Właściciel i pracownik biura (`OFFICE`) mogą pobrać `GET /users/technicians`. Ta lista obejmuje wyłącznie aktywnych techników i zwraca tylko `id` oraz `employeeNumber`, bez adresu email. Pełne dane kont oraz adresy email innych użytkowników są dostępne wyłącznie właścicielowi. Numery pracowników rosną od `0001` i nie są ponownie używane. Listy zwracają obiekt `{ "content": [...], "page": 0, "size": 20, "totalElements": 1, "totalPages": 1 }`; parametry `page` i `size` mają domyślnie wartości `0` i `20`, a maksymalne `size` wynosi `100`. Można podać `sort` i `direction=asc|desc`.

Limit prób logowania działa w pamięci jednej instancji aplikacji. Uruchomienie wielu instancji wymaga wspólnego magazynu limitów.

Błędy API mają format `application/problem+json` z polami `status`, `title`, `detail`, `code`, `requestId` i `instance`. Pole `type` jest opcjonalne; jego brak oznacza `about:blank`. Błędy walidacji zawierają również `violations`. Identyfikator żądania jest zwracany także w nagłówku `X-Request-Id`.

## Migracje bazy danych

Przy starcie z podłączonym PostgreSQL Spring Boot uruchamia Flyway przed udostępnieniem aplikacji. Flyway zapisuje wykonane migracje i ich sumy kontrolne w tabeli `flyway_schema_history`. Migracja V1 tworzy `app_user`, V2 uzupełnia ograniczenia i generowanie numeru pracownika, a V3 tworzy tabele sesji Spring Session. Przy nowej bazie migracje wykonują się kolejno. Próbne tabele testu F03 powstają wyłącznie w kontenerze testowym.

Profil `local` łączy się domyślnie z bazą **`instalflow`** na porcie 5432; baza `postgres` w tym samym serwerze nie jest domyślną bazą aplikacji. Migracje uruchamiaj przez start aplikacji, nie wykonuj plików SQL ręcznie w IDE — samodzielne utworzenie tabeli nie dodaje wpisu V1 do historii Flyway.

Kolejne pliki migracji numeruj rosnąco. Schemat definiuj w SQL; nie pozwalaj Hibernate tworzyć ani zmieniać tabel. Przy projektowaniu tabel stosuj UUID dla kluczy głównych, `timestamptz` i UTC dla czasu, `numeric` dla kwot i ilości oraz jawne klucze obce, ograniczenia `UNIQUE` i indeksy dla istotnych zapytań. Schematy biznesowe, szczegóły kolumn i indeksów należą do zadań wprowadzających poszczególne funkcje.

Po wdrożeniu migracji nie edytuj jej treści ani nazwy. Zmianę istniejącego schematu zapisz w nowym pliku `V...__.sql`. Przy aktualizacji środowiska z danymi wykonaj kopię bazy, uruchom nową wersję aplikacji i sprawdź log Flyway oraz wpis w `flyway_schema_history`. Ponowny start nie powinien ponownie wykonywać zakończonej migracji. Jeśli migracja się nie powiedzie, start aplikacji kończy się błędem; przed ponowną próbą sprawdź rzeczywisty stan schematu i danych, a poprawkę przygotuj zgodnie z tym stanem.

W `application.yaml` ustawiono `spring.jpa.hibernate.ddl-auto=validate`. Przy uruchomieniu JPA sprawdza zgodność encji `AppUser` ze schematem utworzonym przez Flyway. Test F03 wyłącza tę walidację tylko we własnych, izolowanych schematach próbnych, które nie zawierają tabeli `app_user`.

## Zatrzymywanie usług

Zatrzymanie kontenerów zachowuje bazę:

```bash
docker compose down
```

Aby usunąć również lokalne dane bazy i zacząć od pustej:

```bash
docker compose down -v
```

Po zakończeniu pracy aplikację zatrzymaj przyciskiem Stop w IntelliJ lub `Ctrl+C` w terminalu. Wycofanie lokalnego środowiska polega na zatrzymaniu Compose; można też usunąć lokalny plik `.env`. Żadne z tych poleceń nie publikuje zmian w zdalnym repozytorium.

## Świeży checkout

„Świeży checkout” oznacza uruchomienie instrukcji z czystej kopii repozytorium, bez lokalnych plików konfiguracyjnych pominiętych przez Git. Po sklonowaniu wystarczy skopiować `.env.example` do `.env`, a następnie wykonać polecenia z sekcji **Pierwsze uruchomienie po klonowaniu** i **Budowanie i uruchamianie**. `.env.example` zawiera wyłącznie ustawienia local-only, nie zawiera haseł ani sekretów i jest szablonem tylko dla Compose. Plik `.env` jest ignorowany przez Git.
