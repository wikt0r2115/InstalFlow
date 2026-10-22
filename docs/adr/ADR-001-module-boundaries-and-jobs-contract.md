# ADR-001: Zamknięte moduły i migawka przekazywana z oferty do zlecenia

Data: 2026-10-22. Status: przyjęta w zakresie F02.

## Kontekst

InstalFlow powstaje jako modułowy monolit. Aktualnie działa moduł kont, a dalsze zadania wprowadzają klientów, oferty, zlecenia i operacje w tle. Sam podział na katalogi nie zabezpiecza przed odwołaniem do obcej encji albo repozytorium. Akceptacja oferty powinna przekazać dane zleceniu bez zależności zwrotnej `jobs -> offers` oraz bez zależności od późniejszych zmian klienta czy cennika.

## Decyzja

1. Każdy bezpośredni pakiet modułu pod `pl.instalflow` jawnie otrzymuje `@ApplicationModule(type = CLOSED, allowedDependencies = ...)`. Domyślna lista dozwolonych zależności jest pusta. Moduły rezerwowe zawierają metadane pakietu, a nie puste serwisy Spring.
2. API Java wystawiamy celowo przez `@NamedInterface`. Pierwszym jest `jobs :: api`. W F02 jedyną dopuszczoną zależnością jest `offers -> jobs :: api`. Podpakiety z encjami i repozytoriami pozostają ukryte. Nie tworzymy relacji JPA pomiędzy modułami.
3. Metoda `Jobs.createFromAcceptedOffer` przyjmuje komendę z UUID obiektów źródłowych oraz skopiowanymi wartościami klienta, kontaktu, adresu, pozycji zakresu, ceny i czasu akceptacji. Zwraca identyfikator i numer zlecenia. Lista pozycji jest defensywnie kopiowana; pozostałe wartości to niezmienne typy JDK i rekordy.
4. Pozycja zakresu zawiera typ `MATERIAL`/`LABOR`, opis, ilość, jednostkę i cenę jednostkową netto. Ilości mają maksymalnie 3 miejsca po przecinku, ceny i suma netto maksymalnie 2. Wszystkie kwoty są w PLN. Kontrakt odrzuca nadmiarową precyzję bez niejawnego zaokrąglania. Nie oblicza sum i nie ustala reguł rabatów/VAT. Właścicielem kalkulacji będzie moduł ofert.
5. Dane kontaktowe są nullable, planowany termin jest nullable. Czas akceptacji i identyfikator aktora są obowiązkowe. Rozszerzenie migawki o kolejne uzgodnione pola będzie świadomą zmianą kontraktu przy implementacji O01/J01.
6. J01 dostarczy implementację oraz trwałą idempotencję po identyfikatorze oferty. F02 dostarcza sam kontrakt. Nie wprowadzamy zastępczych beanów, endpointów ani tabel zleceń.
7. Weryfikator Spring Modulith sprawdza rzeczywisty kod i celowo błędne próbki testowe. Próbki znajdują się poza pakietem aplikacji i dowodzą wykrywania niedozwolonych importów i cykli. Test dodatkowo sprawdza, że żadna encja ani repozytorium nie stanowi publicznego API modułu.
8. Używamy jednej stabilnej wersji Spring Modulith 2.1.1 z BOM, w tym `spring-modulith-api` w kodzie i `spring-modulith-core` w testach. Usuwamy osobną wersję milestone core, aby kompilacja i weryfikacja używały spójnego modelu metadanych.

## Skutki

Rozbudowa wymaga jawnego kontraktu zamiast dowolnego importu klas z obcego modułu. Zlecenie będzie mogło zachować uzgodnione dane po zmianie obiektów źródłowych. Testy architektury obejmują także moduły zarezerwowane, zanim pojawi się ich logika.

Interfejs nie zapewnia jeszcze wykonania operacji: transakcja utworzenia zlecenia, autoryzacja aktora, przechowywanie migawki i ograniczenie unikalności źródłowej oferty należą do J01. Kierunki przyszłych zależności oraz stan implementacji są zapisane w [architekturze](../architecture.md); nie są automatycznie dopuszczonymi importami.

## Źródła

- [Spring Modulith 2.1.1: moduły, jawne zależności i nazwane interfejsy](https://docs.spring.io/spring-modulith/reference/fundamentals.html).
- [Spring Modulith 2.1.1: wykrywanie cykli i naruszeń granic](https://docs.spring.io/spring-modulith/reference/verification.html).
