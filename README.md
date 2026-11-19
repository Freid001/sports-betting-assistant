# Sports Betting Assistant

A personal web app for betting research. It compares **Sky Bet's odds** with chances worked out from
**team stats**, shows which prices look like value, and follows **live match stats** during games.

It is **read-only**: it never logs into a bookmaker and never places bets. Bets are placed by hand.
It follows the "Betting Framework Spec" (kept in the Claude project *Gambling*).

> Gamble responsibly. Value verdicts are estimates, not guarantees: a good price still loses often,
> especially on long shots. Not financial advice. UK help: [GamCare](https://www.gamcare.org.uk) · 0808 8020 133.

---

## What it does

**Football page** (`/football`)
- Upcoming matches in your tournaments (Premier League, Champions League, Nations League, Euros, World Cup),
  one card per day, with club crests or national flags.
- Sky Bet odds as UK fractions in coloured badges (green = likely, red = long shot). Enter a stake
  (or use £5 / £10 / £20 / £50) to see returns instead.
- **Pick** column: for each match, the outcome that is reasonably likely (35%+) *and* at least a fair price,
  or "No Value".
- **★ Favourites**: pick your clubs and countries once; their matches get a ★ and can be filtered.
- **Live Stats**: tick today's games to follow; their score and minute show as *LIVE 23' 1–0*.
- **Guide** explains the badges and verdicts.

**Match page** (`/football/match/{id}`)
- **Best Bets**: the value outcomes from every market, best first (or "No Bet"), optional small bets (fair price,
  35%+ chance), plus expected goals, confidence and the bookmaker's margin.
- **Markets**: every priced market for the picked bookmaker, each outcome once with **Odds · Chance · Verdict**,
  sorted by verdict, then chance, then the bigger odds. Related markets share a table with a Type column
  (**Result**: FT / HT / HT & FT; **Goalscorer**: First / Anytime, with each player's team and position). Long
  markets show the top 5, with "Show all" in a pop-up.
- Chance tags: Very Likely, Likely, Toss Up, Outsider, Long Shot, Moonshot. Price verdicts: Great / Good /
  Possible Value, Fair Price, Small Cost, Poor Price; "–" where the Value check can't price an outcome.
- Form and league position, goals profile, head to head and key players.
- **Live Match Stats** for followed games: shots, on target, xG (estimated), dangerous attacks, corners,
  possession, cards, and players' shots on target.

**Horse Racing** is a placeholder for now.

## Getting started

Requires **Java 25+** and **Maven 3.8+**. Node.js 22+ is only needed to run the JavaScript tests.

1. **Get free API keys** (see [Data sources](#data-sources-and-limits)):
   [The Odds API](https://the-odds-api.com), [football-data.org](https://www.football-data.org/client/register)
   and [GOAL API](https://goal-api.com).
2. **Add them to a `.env` file** in the project folder (copy `.env.example`):
   ```properties
   ODDS_API_KEY=...
   FOOTBALL_DATA_API_KEY=...
   GOAL_API_KEY=...
   ```
   `.env` is git-ignored, so keys never go to GitHub. They are only used by the server and never sent to the browser.
3. **Run it**:
   ```bash
   mvn spring-boot:run
   ```
   Use `mvn clean spring-boot:run` after Java or config changes (the app shows a green *Restart needed*
   pop-up when it spots them). Page and script changes only need a browser refresh.
4. Open **http://localhost:8080**, then click **Refresh Odds** and **Refresh Stats** once to fill the pages.

## Data sources and limits

All three are on their free plans. The pop-ups for **Refresh Odds**, **Refresh Stats** and **Live Stats**
show the plan, calls left, limit and reset time before anything is spent.

| Source | Used for | Free plan | What uses calls |
|---|---|---|---|
| [The Odds API](https://the-odds-api.com/liveapi/guides/v4/) | Sky Bet, Betway, Paddy Power or William Hill odds | 500 credits / month | **Refresh Odds**: about 2 credits per tournament with fixtures (match result for every match; others are skipped for free). **Extra markets** (a tick box per market, each showing its cost): one request per match, up to 1 credit per ticked market per match |
| [football-data.org](https://www.football-data.org) | Results, tables, form, scorers, head to head, crests | 10 calls / minute, no monthly limit | **Refresh Stats**: about 17 calls (queued at 10 a minute); head to head: 1 call the first time a match is opened |
| [GOAL API](https://goal-api.com) | Live scores and match stats | 1,000 calls / day | Opening **Live Stats**: 1 call per tournament (reused for 3 hours). While a ticked game is on: every 5 minutes, 1 call per tournament playing + 1 per live game |

Notes:
- **Bookmaker**: Refresh Odds starts with a Bookmaker switch: Sky Bet, Betway, Paddy Power or William Hill. It picks whose match result and extra markets are
  fetched, and whose odds the Football page, match pages and Value Check show from then on. Each bookmaker's
  prices are saved separately, so switching never overwrites the other's.
- **Extra markets** are only available one match at a time, and each bookmaker offers different ones through The
  Odds API (set in `odds-api.bookmakers`). Sky Bet: goals lines, BTTS, first-half result, half-time/full-time,
  anytime and first goalscorer. Betway also has double chance, draw no bet, team goals, correct score, corners,
  cards and player shots on target. Paddy Power has corners and shots on target; William Hill has draw no bet,
  shots on target and score-or-assist. Markets the picked bookmaker doesn't offer are greyed out; only offered ones
  are asked for (asking for one it doesn't have can make the whole answer come back empty).
- Each market is its own tick box with "up to N credits", plus a running total. A switch above them picks the
  matches: **Off**, **★ Favourites' matches** (a starred team playing) or **All matches**, which sets N. You only pay
  for markets that come back; it uses every credit left and stops when there are none, and a normal Refresh Odds keeps them
  (only the match result is replaced). Choices are remembered.
- Ticking a 3pm game in the morning costs nothing until two minutes before kick-off.
- Live refreshing pauses if fewer than 50 GOAL API calls are left today.
- GOAL API has no xG, so xG is **estimated** from shot counts: 0.12 per shot inside the box,
  0.035 per shot outside, 0.76 per penalty.
- Live Stats only refresh while the app is running.

## How the value check works

1. **Sky Bet's fair chance**: the odds with the bookmaker's margin removed.
2. **Goals model**: a Poisson model from the home side's recent home games and the away side's recent away games
   (any venue if there are fewer than 5).
3. **Our chance**: Sky Bet's fair chance nudged half-way towards the model, but never by more than
   5 points (2–3 with little data) or a fifth of Sky Bet's own chance, so long shots can't look like big value.
4. **Verdict**: the average return per £1 at our chance: Great Value +15% or more, Good Value +5% to +15%
   ("Possible Value" when data is thin), Fair Price within ±5%, Small Cost for likely outcomes slightly short,
   otherwise Poor Price.
5. **Other markets** use the same goals model: each goals line, BTTS, first-half result (about 45% of goals come
   before half-time), half-time/full-time and correct score.
6. **Goalscorers** (anytime and first), for players in the scorer tables: goals per game, scaled to the team's
   expected goals in the match. A player's record is blended with a typical rate for his position (forward 0.35,
   midfielder 0.12, defender 0.04 goals a game) worth 10 games, so early-season bursts (a defender's 2 early
   headers) and slow starts are evened out; after 20+ games his own record counts most. The bookmaker's margin is
   estimated from the whole market. Players who haven't scored yet get the bookmaker's chance and no verdict.
   Not checked yet: corners, cards, double chance, shots on target, score-or-assist.

## Configuration

Settings live in `src/main/resources/application.yaml`; secrets only in `.env`.

| Setting | What it does |
|---|---|
| `football.tournaments` | Tournaments shown and refreshed (The Odds API keys without `soccer_`) |
| `football-data.leagues` | Domestic leagues that may be fetched for form and tables |
| `football-data.league-scorers` | `false` saves a call per league (Key players then shows tournament goals only) |
| `football-data.calls-per-minute` | Pace for football-data.org (raise it if you upgrade) |
| `goal-api.refresh-minutes` / `max-games` / `reserve-calls` | Live Stats refresh rate, games you can tick, calls kept spare |
| `goal-api.leagues` | GOAL API league id for each tournament |
| `store.file` | The SQLite database (default `data/sports-betting-assistant.db`) |

To add a tournament (e.g. the Championship): add its key to `football.tournaments`, its football-data.org code
to `Competitions.java`, its display name in `football-data.js`, and its GOAL API league id to `goal-api.leagues`.

## Local data

Everything fetched is saved so pages load without spending calls, all in **one SQLite file**:
`data/sports-betting-assistant.db` (git-ignored). Open it with IntelliJ's database tool or
[DB Browser for SQLite](https://sqlitebrowser.org) if you want to look inside.

Tables (created automatically; see `Store.java`):

| Table | Contents |
|---|---|
| `teams`, `competitions`, `competition_teams` | Clubs and countries with crests, which competition they're in |
| `matches` | Results and fixtures (score, half-time, stage), indexed by team and competition |
| `scorers` | Top scorers per competition, with each player's position |
| `squads` | Each team's players and positions (Refresh Stats: teams playing in the next 2 weeks, kept for a month) |
| `odds_fetches`, `odds_events`, `odds_prices`, `odds_extra_fetches` | Bookmaker odds: each refresh, its matches, every price (per bookmaker), and when each match's extra markets were fetched from each bookmaker |
| `live_games`, `live_stats`, `live_stat_rows`, `live_players` | Live Stats: ticked games, scores, stats, shooters |
| `favourites` | Your favourite teams |
| `head_to_head`, `head_to_head_matches` | Head to head for a fixture: totals and the last meetings (kept a week) |
| `refreshes`, `api_usage` | When things were last refreshed, and calls left per API |

Only the fields the app uses are kept, so the database is much smaller than the raw API answers, and pages
read from indexed tables instead of parsing JSON.

- **Start fresh**: stop the app and delete the file (favourites go too), then refresh odds and stats.
- **Back up or move it**: copy the file while the app is stopped. Don't run the app on a file inside a
  synced folder (e.g. Google Drive); copy a snapshot there instead.

## Project layout

```
src/main/java/com/sportsbettingassistant/
  SportsBettingAssistantApplication.java   starts the app
  controller/   Home (redirects to Football), AppStatus (Restart needed check)
  odds/         The Odds API client, saved odds and Refresh Odds
  football/     football-data.org client, Refresh Stats, tables, crests, Favourites, Competitions
  football/live GOAL API client and Live Stats (refresh, xG estimate)
  json/         Shared JSON helpers and the bad-request handler
  store/        The SQLite database (Store.java)
src/main/resources/
  application.yaml
  templates/    football.html, football-match.html, horse-racing.html, fragments.html (layout, Guide, pop-ups)
  static/js/    football-data.js (shared helpers), value-check.js, match-stats.js, live.js, favourites.js
src/test/java/  JUnit tests (support/FakeServer.java stands in for the APIs)
src/test/js/    Node tests (fake-dom.js is a small fake browser)
```

Built with Spring Boot 4, SQLite, Thymeleaf, Bootstrap 5 and plain JavaScript.

## Tests

```bash
mvn test                                                           # Java tests + coverage report
node --test "src/test/js/*.test.js"                                # page scripts
node --test --experimental-test-coverage "src/test/js/*.test.js"   # page scripts with coverage
```

- Java coverage report: `target/site/jacoco/index.html` (open it after `mvn test`).
- API clients are tested against a small fake web server, so tests never call the real APIs or use credits.
- The JavaScript tests run the real page scripts in a small fake browser with Node's built-in test runner,
  so there is nothing to install.
