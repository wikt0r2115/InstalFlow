# Kontrakt HTTP

[OpenAPI 3.1](../src/main/resources/static/openapi.yaml) opisuje działające endpointy
`/api/v1/auth` i `/api/v1/users`. Uruchomiona aplikacja udostępnia dokument pod
`GET /openapi.yaml` użytkownikowi z rolą `OWNER`.

Przykłady w [identity.http](http/identity.http) wykonuj kolejno. Klient musi zachowywać
ciasteczka. Ustaw `baseUrl`, `ownerEmail`, `ownerPassword`, `newUserEmail`,
`newUserPassword` i `replacementPassword` w prywatnym środowisku klienta HTTP.
Po zalogowaniu pobierz nowy token CSRF i zachowaj obrócone ciasteczko `SESSION`.

Test `OpenApiContractTests` parsuje dokument standardowym parserem OpenAPI,
sprawdza odwołania, porównuje ścieżki i metody z kontrolerami oraz pola DTO ze
schematami. Odpowiedzi rzeczywistej warstwy MVC i przykłady przechodzą walidację
JSON Schema 2020-12. Testy integracyjne modułu identity sprawdzają logowanie,
sesje, CSRF, uprawnienia oraz operacje na PostgreSQL.

W błędach `application/problem+json` pole `type` jest opcjonalne: jego brak oznacza
`about:blank`, zgodnie z [RFC 9457, sekcja 3.1.1](https://www.rfc-editor.org/rfc/rfc9457.html#name-type).
Pola `title`, `status`, `detail`, `instance`, `code` i `requestId` są wymagane przez
kontrakt aplikacji. Spring 7 pomija domyślny `type` w odpowiedziach `ProblemDetail`;
odpowiedzi filtra bezpieczeństwa mogą zwracać go jawnie.

Wspólne schematy `DecimalValue` i `Timestamp` określają format danych: liczby
dziesiętne są ciągami JSON bez wykładnika, a znaczniki czasu wymagają offsetu
i są zwracane w UTC. Ograniczenia dziedzinowe skali (ilości do 3 miejsc, kwoty do
2) będą należały do schematów konkretnych pól; obecny kontrakt nie implementuje
wyceny ani nowych endpointów ofert lub zleceń.
