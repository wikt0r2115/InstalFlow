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

Compose automatycznie wczytuje plik `.env`. PostgreSQL używa bezhasłowego uwierzytelniania `trust` wyłącznie w odizolowanej lokalnej sieci Compose. Ta sieć nie jest współdzielona z Mailpit; z hosta baza jest osiągalna tylko przez port `127.0.0.1:5432`. To ustawienie służy wyłącznie do lokalnego developmentu — nie używaj go dla produkcji ani danych produkcyjnych. Compose czeka na gotowość PostgreSQL przez healthcheck. Mailpit przyjmuje testowe wiadomości SMTP na `127.0.0.1:1025`, a jego panel jest dostępny pod <http://127.0.0.1:8025>; działa w osobnej, odizolowanej sieci Compose. Dane PostgreSQL są przechowywane w wolumenie Dockera.

## Budowanie i uruchamianie

W katalogu głównym repozytorium:

```bash
./mvnw clean verify
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Aby uruchomić testy (Spring Boot Test aktywuje profil `test` dla testów):

```bash
./mvnw test
```

Profil `demo` można włączyć podczas uruchamiania aplikacji:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

Profile wybiera się również w IntelliJ w konfiguracji uruchomieniowej aplikacji, w polu **Active profiles**. Profil `local` jest przeznaczony do pracy z usługami z Compose. Testy jednostkowe profilu `test` powinny działać bez uruchomionego Dockera, o ile nie korzystają z zewnętrznych usług.

Compose wczytuje `.env`, ale proces aplikacji uruchamiany z IntelliJ ani z powyższego polecenia Maven nie wczytuje tego pliku automatycznie. Domyślne wartości profilu `local` są zgodne z domyślną konfiguracją Compose. Jeśli zmienisz wartości w `.env`, ustaw odpowiadające im zmienne (`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PORT`, `MAILPIT_SMTP_PORT`) również w **Run Configuration → Environment variables** IntelliJ albo w środowisku powłoki przed startem Maven.

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
