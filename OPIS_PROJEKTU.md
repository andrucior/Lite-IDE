# Kompleksowy Opis Projektu Lite-IDE

Niniejszy dokument stanowi szczegółowy opis architektury backendu projektu **Lite-IDE**, ze szczególnym naciskiem na mechanizm **historii zmian** oraz **system uprawnień**. Został on przygotowany jako materiał edukacyjny uczący języka Scala 3 na podstawie faktycznych rozwiązań użytych w kodzie, a także jako pomoc (tzw. "ściągawka") do obrony projektu przed prowadzącym.

---

## 1. System Uprawnień (Permissions / Roles)

Projekt implementuje trójpoziomowy system dostępu do dokumentów. Użytkownik współpracujący w dokumencie może przyjąć jedną z trzech ról, które decydują o jego uprawnieniach.

### Implementacja i Cechy Scali 3
W pliku `backend/src/main/scala/com/liteide/domain/User.scala` role zostały zamodelowane przy użyciu nowej konstrukcji ze Scali 3 — **`enum`**.

```scala
enum Role derives CanEqual:
  case Owner, Editor, Observer
```
**Czego to uczy o Scali?**
- W najnowszej wersji języka Scala 3, typ `enum` zastąpił znane ze Scali 2 uszczelnione traity (`sealed trait`) dla typów wyliczeniowych (Algebraic Data Types - ADT). Gwarantuje to tzw. *exhaustiveness checking* (kompilator upewni się, czy w pattern matchingu obsłużyliśmy wszystkie możliwe opcje z enum-a).
- Klauzula `derives CanEqual` to wbudowany w kompilator mechanizm ułatwiający bezpieczne (strict) porównywanie instancji typów za pomocą `==`.

### Weryfikacja Uprawnień
Uprawnienia chronią integralność systemu na dwóch warstwach:
1. **Warstwa REST API (`Routes.scala`):** Wyłącznie `Owner` może modyfikować dostępy. Żądania te chronione są w routingu (HTTP REST).
2. **Warstwa WebSocket / Czasu Rzeczywistego (`DocumentRoom.scala`):** W trakcje sesji *live collaboration* Twoja rola jest powiązana z obiektem obecności (`Presence`). Zanim Twoja nowa literka trafi do tekstu przez funkcję `submitEdit`, backend asynchronicznie odpytuje stan za pomocą `Ref`:

```scala
presence.get.map(_.get(authorSessionId).map(_.role).getOrElse(Role.Editor)).flatMap {
  case Role.Observer =>
    (Left("observers cannot submit edits"): Either[String, Int]).pure[F]
  case _ =>
    // Główna logika przetwarzająca operacje OT
}
```

**Czego to uczy o Scali?**
- **Pattern Matching (Dopasowanie do wzorca):** Blok `case Role.Observer => ...` jest jednym z najpotężniejszych aspektów programowania w Scali. Elegancko filtruje zły scenariusz zostawiając resztę do obsługi klauzuli `case _ =>`.
- **Typy Algebraiczne jako Alternatywa dla Wyjątków (`Either` i monady):** Programiści Scali unikają rzucania wyjątków (np. `throw Exception`) do obsługi logiki biznesowej, ponieważ wyjątki są niejawne (zaburzają przepływ). Tu widzimy zastosowanie typu monadycznego `Either[String, Int]`. Strona "lewa" (`Left`) jest nośnikiem błędu autoryzacji (String), podczas gdy strona "prawa" (`Right`) to sukces (Int - numer nowej wersji dokumetu). Metoda `pure[F]` to wzorzec *Cats Effect*, który "podnosi" czystą wartość do wybranego, używanego w programie typu asynchronicznego efektu `F` (np. operacji sieciowej).

### 🎓 Pytanie od Prowadzącego: UPRAWNIENIA
> **Pytanie:** *Jak serwer weryfikuje uprawnienia w czasie rzeczywistym i dlaczego użyto do tego takiego (funkcyjnego) podejścia w kodzie?*
> 
> **Odpowiedź:** Rola użytkownika przechowywana jest w bez-zamkowej strukturze równoległej typu `Ref` (obiekt `Presence`). Podczas każdej próby zmiany dokumentu (metoda `submitEdit`), backend sprawdza za pomocą "pattern matchingu", czy rola różni się od `Observer`. Od strony architektonicznej nie rzucamy żadnych wyjątków z błędem autoryzacji - korzystamy z tzw. konstrukcji `Either`, gdzie `Left` to bezpieczny ciąg znaków z powodem odrzucenia edycji. Zapobiega to kosztownemu "zrzucaniu stosu" (Stack Trace) na poziomie maszyny wirtualnej, dzięki czemu serwer nie blokuje się pod dużym obciążeniem odrzucanych pakietów od obserwatorów.

---

## 2. Historia Zmian (Edit History)

Aby bezkolizyjnie edytować kod ze współpracownikiem w tym samym czasie, serwer Lite-IDE opiera się na strategii tzw. Transformacji Operacji (Operational Transformation - OT). To algorytmiczne serce projektu nakłada wymóg pamiętania każdej edycji.

### Struktura Danych i Przechowywanie
Zamiast serializować do pamięci całego stringa z pliku przy wpisaniu każdego znaku, system zapamiętuje "Deltę" (np. *zachowaj 5 znaków, wstaw 'a'*), za co odpowiada obiekt `Op`. Pojedynczy punkt w osi czasu znajduje się w `HistoryEntry.scala`:

```scala
final case class HistoryEntry(
    op:                Op,
    authorSessionId:   SessionId,
    authorDisplayName: String,
    timestamp:         Long,
    version:           Int
)
```

**Czego to uczy o Scali?**
- **Case Class:** Prawdopodobnie najważniejsza konstrukcja klasowa w Scali, stworzona do trzymania niemutowalnych (immutable) danych domeny biznesowej. Kompilator automatycznie tworzy dla nich poprawne metody `equals`, `hashCode`, domyślne metody dostępu, a także metodę `copy()` ułatwiającą powielenie ze zmianą tylko wybranego atrybutu.
- Są one bez oporów mapowane w obie strony (JSON <-> Obiekt) przy pomocy świetnej, opartej na programowaniu generycznym biblioteki **Circe**. Definiując obiekt towarzyszący kompilator sam wstrzykuje instrukcje parsujące z użyciem "extension methods" takich jak metoda `.asJson`.

### Mechanika: `State` oraz Snapshots w `DocumentRoom.scala`
Jeżeli pracujesz z kolegą 2 dni w jednym pliku, stworzycie tysiące takich paczek "Op". Odtwarzanie dokumentu linijka po linijce ze stutysięcznego `Vector`a byłoby wąskim gardłem. Klasa `State` wewnątrz serwisu dokumentu używa strategii **Snapshotting**.

```scala
private final case class State(
    initialText: String,
    text:        String,
    version:     Int,
    history:     Vector[HistoryEntry],
    snapshots:   Map[Int, String]
)
```
Po wykonaniu okrągłych 100 wersji (bo zadeklarowano stałą `SnapshotInterval = 100`), pełny i złączony tekst zostaje dorzucony do mapy `snapshots`.
Kiedy ktoś (lub algorytm) zażąda tekstu do celów wygenerowania tzw. diff'a (podświetlenia różnicy w kodzie), wywołana zostaje funkcja `textAtVersion`. Przechodzi ona po strukturze `snapshots`, wyciąga najbliższy "kamień milowy" i zaledwie w kilkunastu małych iteracjach (maksymalnie 99) dolicza na to zmiany z historii.

**Czego to uczy o Scali?**
- **Szybkie Niemutowalne Kolekcje (`Map`, `Vector`):** Programowanie wielowątkowe wymaga braku mutowalnego stanu. `Vector` pod maską w Scali to zoptymalizowana hybryda Array/Drzewo - oferująca O(1) do doczepienia nowych paczek historii i bardzo szybki dostęp po indeksie do starych.
- **Podejście Deklaratywne do Danych:**
  By namierzyć najbliższego snapshota użyto kodu:
  `s.snapshots.filter(_._1 <= targetVersion).maxBy(_._1)`
  Programista Scali nie pisze pętli `for(int i=0...>`, nie stawia zewnętrznej flagi max. Robi to funkcyjnie - czytelnie wyraża swoje zamierzenia ("przefiltruj wersje mniejsze równe od szukanej", a z tego "daj mi z najwyższym kluczem"). Specjalny znak **`_`** to tak zwany uproszczony parametr anonimowej funkcji lambda, czyniący kod wyjątkowo zwięzłym.

### 🎓 Pytanie od Prowadzącego: HISTORIA ZMIAN
> **Pytanie:** *W jaki sposób zoptymalizowano problem puchnącej historii zmian w dokumencie oraz jak zagwarantowano bezpieczeństwo odtwarzania dokumentu w wątkach?*
> 
> **Odpowiedź:** Historia używa optymalnej z natury pamięci struktury w postaci zmian różnicowych - "delt" (OT), a nie całego dokumentu po każdej edycji. By sprostać ewentualnym wyzwaniom wydajności procesora w przypadku np. 50 tysięcy małych edycji (wpisanych liter), zastosowano model Snapshottingu. Dokładnie co 100 iteracji cały plik zapisywany jest do słownika typu Hash-Map w pamięci. Metody odtwarzające plik używają funkcyjnych operatorów kolekcji Scali (`filter` połączone z `maxBy`), odnajdują najbliższy temu zdarzeniu "pełny zrzut" pliku z przeszłości i doliczają co najwyżej 99 brakujących operacji. Z kolei przed błędami wyścigów wątków zabezpiecza nas Cats Effect - każda transformacja edycji jest zamykana chroniącym blokiem asynchronicznego zamka `Mutex`.

---

## 3. Dodatkowy kontekst - "Dlaczego Scala w tym projekcie, a nie Java?"

Gdyby pojawiły się inne ogólne pytania, oto 3 najlepsze argumenty, które widać w kodzie tego serwera:
1. **Konstrukcje For-comprehension:** Tworząc system asynchroniczny z Websocketami (jak `DocumentRoom`), kod w typowym języku stałby się nieczytelnym "spaghetti callbacków". W Scali, dzięki For-comprehension używamy monadycznych połączeń (`for { a <- zrobTo(); b <- zrobTamto() } yield wyik`). Wygląda to i wykonuje się jak łatwy kod blokowy, ale działa niesamowicie wydajnie w tle.
2. **Niemutowalność (*Immutability*) jako podstawa:** Ani jedna zbiżająca się struktura reprezentująca zsynchronizowany dokument (`State`) nie używa zmiennej lokalnej `var`. Scala domyślnie zmusza nas do korzystania z konstrukcji `val` (tylko do odczytu) i struktur Immutable. Eliminuje to 95% przypadkowych i bardzo ciężkich w testowaniu błędów przy systemach wielowątkowych czasu rzeczywistego (np. gubienie kursorów, błędy odczytów ze strumienia w połowie wpisywania).
3. **Funkcyjne Rozwiązywanie Błędów:** Widać to wyraźnie we wszystkich Endpointach z `Routes.scala` – błędy obsługiwane są systemami typów (np. monadą typowaną Either bądź Opcjami/Option). Dzięki temu wymusza się zdefiniowanie w kodzie planu działania dla momentu braku istnienia obiektu na serwerze i eliminuje tym samym klasykę pomyłek innych języków: niespodziewane wybuchy na skutek `NullPointerException`.
