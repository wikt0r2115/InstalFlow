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

Profil `demo` korzysta z tej samej lokalnej bazy i usług Compose co `local`. Można go włączyć podczas uruchamiania aplikacji:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

Profile wybiera się również w IntelliJ w konfiguracji uruchomieniowej aplikacji, w polu **Active profiles**. Profil `local` jest przeznaczony do pracy z usługami z Compose. Pełny zestaw testów wymaga działającego Dockera, ponieważ testy aplikacji, kont i migracji uruchamiają PostgreSQL przez Testcontainers.

Compose wczytuje `.env`, ale proces aplikacji uruchamiany z IntelliJ ani z powyższego polecenia Maven nie wczytuje tego pliku automatycznie. Domyślne wartości profilu `local` są zgodne z domyślną konfiguracją Compose. Jeśli zmienisz wartości w `.env`, ustaw odpowiadające im zmienne (`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PORT`, `MAILPIT_SMTP_PORT`) również w **Run Configuration → Environment variables** IntelliJ albo w środowisku powłoki przed startem Maven.

## Migracje bazy danych

Przy starcie z podłączonym PostgreSQL Spring Boot uruchamia Flyway przed udostępnieniem aplikacji. Flyway zapisuje wykonane migracje i ich sumy kontrolne w tabeli `flyway_schema_history`. Migracja V1 tworzy `app_user`, a V2 uzupełnia ograniczenia i generowanie numeru pracownika. Przy nowej bazie migracje wykonują się kolejno. Próbne tabele testu F03 powstają wyłącznie w kontenerze testowym.

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
