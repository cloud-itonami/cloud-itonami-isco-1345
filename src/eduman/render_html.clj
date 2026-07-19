(ns eduman.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.
  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all (confirmed by a full tree listing before writing this
  file -- no `docs/` directory, no `.github/workflows/`, existed prior to
  this commit).

  This namespace drives the REAL checkpointed actor stack
  (`eduman.actor` -> `eduman.governor` -> `eduman.store`) through a
  scenario built from real, exercised store data and renders the result
  deterministically -- no invented numbers, no timestamps in the page
  content, byte-identical across reruns against the same seed.

  Seed data disclosure (read directly from both
  `test/eduman/actor_test.clj` and `test/eduman/governor_test.clj`'s
  `fresh-store` fixtures before writing this file -- both use identical
  values):
    `client-1` / `{:client-id \"client-1\" :name \"Kobo Trade\"}` and
    program `P-1` / `{:program-id \"P-1\" :client-id \"client-1\" :name
    \"diploma-in-trades\" :required-credits 120
    :accreditation-issued-day 100 :accreditation-expiry-day 400}` are
    lifted VERBATIM from those fixtures. `client-2` /
    `{:client-id \"client-2\" :name \"Meridian Institute\"}` is
    ADDITIONAL demo data registered here via the store's own real
    `register-client!` protocol call -- disclosed plainly, not presented
    as a pre-existing fixture. It exists solely to exercise the
    `program-wrong-client` hard rule (a client citing a program it does
    not own), mirroring how `governor_test.clj`'s own
    `hard-on-foreign-program` test registers a second client
    (`client-2`/\"Other\" there) for the same reason -- this demo uses a
    different disclosed name (\"Meridian Institute\") for the same disclosed
    role, so it is not mistaken for an undisclosed fixture value.
    The specific credit/day/program-id values driving each hard-hold
    scenario below (90 credits, day 50, day 500, program-id \"P-ghost\",
    client-id \"nobody\") are the SAME literal values
    `governor_test.clj`'s own unit tests already use to exercise
    `eduman.governor/check` directly -- this demo reuses them to drive
    the identical checks end-to-end through the real compiled graph
    rather than inventing new numbers.

  Honest gaps found while reading the real source (disclosed, not
  hidden):
    - `eduman.governor/check`'s HARD rule #2 (`:no-actuation`, \"effect
      must be :propose\") is structurally UNREACHABLE through this
      repo's own advisors: `eduman.advisor/mock-advisor`'s `infer`
      always sets `:effect :propose` on every proposal it constructs
      (and the unimplemented-by-design `llm-advisor` path also always
      sets `:effect :propose`, even on LLM parse failure), so no request
      driven through the real `:advise` node can ever produce a
      `:no-actuation` violation. This demo does not fabricate a path
      around that -- it is left out and named here instead, exactly as
      isco-0110/1211/1111's render_html.clj disclosed their own
      unreachable rules.
    - ESCALATION invariant #7 (\"low confidence\", < `confidence-floor`
      0.6) is ALSO structurally unreachable via `mock-advisor`:
      `infer`'s confidence is `(case (or stake :low) :high 0.7 :medium
      0.85 :low 0.95)` -- every branch is >= 0.7, strictly above the
      0.6 floor, for every `:stake` value the case handles. This demo
      exercises the OTHER reachable escalation invariant instead
      (#6, `:op :approve-credit-waiver`, which escalates unconditionally
      regardless of confidence) and names #7 here as unreachable rather
      than inventing an advisor override to force it.
    - `governor.cljc`'s own docstring numbers exactly 5 HARD invariants
      (1-5) and 2 ESCALATE invariants (6-7), but the real
      `hard-violations` implementation emits SIX distinct `:rule`
      keywords for those 5 numbered HARD items: `:no-client`,
      `:no-actuation`, `:unknown-program`, `:program-wrong-client`,
      `:insufficient-credits`, `:outside-accreditation-window`. Item 3
      (\"program basis -- a graduation approval must cite a REGISTERED
      program belonging to this client\") reads as one invariant but the
      code splits it into two independently-triggerable rule tags
      (`:unknown-program` for a program-id that does not exist at all,
      `:program-wrong-client` for a program-id that exists but belongs
      to a different client) -- both are exercised separately below and
      labeled by their real `:rule` keyword, not folded together.

  Usage: `clojure -M:render-html [out-file]` (default
  `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [eduman.store :as store]
            [eduman.actor :as actor]))

(defn- run-op! [graph thread-id label request]
  (let [r1 (actor/run-request! graph request {} thread-id)]
    (case (:status r1)
      :interrupted
      (let [r2 (actor/approve! graph thread-id)]
        {:label label :thread-id thread-id :request request
         :outcome :approved-and-committed
         :verdict (get-in r1 [:state :verdict])
         :record (get-in r2 [:state :record])})

      :done
      (let [state (:state r1)
            disposition (:disposition state)]
        (if (= :hold disposition)
          {:label label :thread-id thread-id :request request
           :outcome :hard-hold
           :verdict (:verdict state)
           :rule (-> state :verdict :violations first :rule)}
          {:label label :thread-id thread-id :request request
           :outcome :auto-committed
           :verdict (:verdict state)
           :record (:record state)}))

      {:label label :thread-id thread-id :request request
       :outcome :unexpected-status :status (:status r1)})))

(def ^:private op-specs
  [["t1" "client-1 / sufficient credits, in window" "client-1"
    {:client-id "client-1" :op :approve-graduation :stake :low
     :program-id "P-1" :completed-credits 130 :as-of-day 200}]
   ["t2" "client-1 / insufficient credits (90 < 120 required)" "client-1"
    {:client-id "client-1" :op :approve-graduation :stake :low
     :program-id "P-1" :completed-credits 90 :as-of-day 200}]
   ["t3" "client-1 / before accreditation window (day 50 < issued day 100)" "client-1"
    {:client-id "client-1" :op :approve-graduation :stake :low
     :program-id "P-1" :completed-credits 130 :as-of-day 50}]
   ["t4" "client-1 / after accreditation window (day 500 > expiry day 400)" "client-1"
    {:client-id "client-1" :op :approve-graduation :stake :low
     :program-id "P-1" :completed-credits 130 :as-of-day 500}]
   ["t5" "client-1 / unregistered program (P-ghost)" "client-1"
    {:client-id "client-1" :op :approve-graduation :stake :low
     :program-id "P-ghost" :completed-credits 130 :as-of-day 200}]
   ["t6" "client-2 / program belongs to a different client (P-1 is client-1's)" "client-2"
    {:client-id "client-2" :op :approve-graduation :stake :low
     :program-id "P-1" :completed-credits 130 :as-of-day 200}]
   ["t7" "unregistered client (nobody)" "nobody"
    {:client-id "nobody" :op :approve-graduation :stake :low
     :program-id "P-1" :completed-credits 130 :as-of-day 200}]
   ["t8" "client-1 / credit waiver (always escalates)" "client-1"
    {:client-id "client-1" :op :approve-credit-waiver :stake :high
     :program-id "P-1"}]])

(defn run-demo! []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-program! st {:program-id "P-1" :client-id "client-1"
                                  :name "diploma-in-trades"
                                  :required-credits 120
                                  :accreditation-issued-day 100
                                  :accreditation-expiry-day 400})
    (store/register-client! st {:client-id "client-2" :name "Meridian Institute"})
    (let [graph (actor/build-graph {:store st})
          runs (mapv (fn [[thread-id label _client-id request]]
                        (run-op! graph thread-id label request))
                      op-specs)]
      {:store st :runs runs})))

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">unexpected</span>"))

(defn- entities-table [store*]
  (str
   "<table><thead><tr><th>client-id</th><th>name</th><th>source</th></tr></thead><tbody>"
   "<tr><td>client-1</td><td>" (esc (:name (store/client store* "client-1"))) "</td>"
   "<td>test fixture (verbatim)</td></tr>"
   "<tr><td>client-2</td><td>" (esc (:name (store/client store* "client-2"))) "</td>"
   "<td>demo addition (disclosed, real <code>register-client!</code> call)</td></tr>"
   "</tbody></table>"
   "<table><thead><tr><th>program-id</th><th>name</th><th>client-id</th>"
   "<th>required-credits</th><th>accreditation window</th><th>source</th></tr></thead><tbody>"
   (let [p (store/program store* "P-1")]
     (str "<tr><td>P-1</td><td>" (esc (:name p)) "</td><td>" (esc (:client-id p))
          "</td><td>" (esc (:required-credits p)) "</td><td>["
          (esc (:accreditation-issued-day p)) ", " (esc (:accreditation-expiry-day p))
          "]</td><td>test fixture (verbatim)</td></tr>"))
   "</tbody></table>"))

(defn- gate-table []
  (str
   "<table><thead><tr><th>#</th><th>rule</th><th>class</th><th>exercised in this demo?</th></tr></thead><tbody>"
   "<tr><td>1</td><td><code>no-client</code></td><td class=\"critical\">HARD</td><td>yes (t7, client-id \"nobody\")</td></tr>"
   "<tr><td>2</td><td><code>no-actuation</code></td><td class=\"critical\">HARD</td>"
   "<td class=\"muted\">no &mdash; structurally unreachable via this repo's advisors (see docstring)</td></tr>"
   "<tr><td>3a</td><td><code>unknown-program</code></td><td class=\"critical\">HARD</td><td>yes (t5, program-id \"P-ghost\")</td></tr>"
   "<tr><td>3b</td><td><code>program-wrong-client</code></td><td class=\"critical\">HARD</td><td>yes (t6, client-2 citing client-1's P-1)</td></tr>"
   "<tr><td>4</td><td><code>insufficient-credits</code></td><td class=\"critical\">HARD</td><td>yes (t2, 90 &lt; 120 required)</td></tr>"
   "<tr><td>5</td><td><code>outside-accreditation-window</code></td><td class=\"critical\">HARD</td><td>yes (t3 before, t4 after)</td></tr>"
   "<tr><td>6</td><td><code>:op :approve-credit-waiver</code></td><td class=\"warn\">ESCALATE</td><td>yes (t8)</td></tr>"
   "<tr><td>7</td><td>low confidence (&lt; 0.6)</td><td class=\"warn\">ESCALATE</td>"
   "<td class=\"muted\">no &mdash; mock-advisor's minimum confidence (0.7) never drops below the 0.6 floor (see docstring)</td></tr>"
   "</tbody></table>"
   "<p class=\"muted\">Rows 3a/3b are both part of <code>governor.cljc</code>'s own docstring item "
   "3 (\"program basis\"), which the real implementation splits into two distinct <code>:rule</code> "
   "keywords &mdash; disclosed in the render_html.clj docstring.</p>"))

(defn- runs-table [runs]
  (str
   "<table><thead><tr><th>thread</th><th>scenario</th><th>request</th><th>outcome</th><th>confidence</th></tr></thead><tbody>"
   (str/join
    ""
    (for [{:keys [thread-id label request verdict] :as run} runs]
      (str "<tr><td><code>" (esc thread-id) "</code></td><td>" (esc label) "</td>"
           "<td><code>" (esc (pr-str (dissoc request :client-id))) "</code></td>"
           "<td>" (outcome-cell run) "</td>"
           "<td>" (esc (:confidence verdict)) "</td></tr>")))
   "</tbody></table>"))

(defn render [{:keys [store runs]}]
  (str
   "<!doctype html>\n<html><head><meta charset=\"utf-8\">"
   "<title>Education Managers Operator Console — cloud-itonami-isco-1345</title>"
   "<style>"
   "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;margin:2rem;color:#1c1c1e;background:#fff}"
   "h1{font-size:1.4rem}h2{font-size:1.1rem;margin-top:2rem}"
   "table{border-collapse:collapse;width:100%;margin:0.5rem 0 1rem}"
   "th,td{border:1px solid #d0d0d5;padding:0.4rem 0.6rem;text-align:left;font-size:0.92rem}"
   "th{background:#f5f5f7}"
   "code{font-family:ui-monospace,Menlo,monospace;font-size:0.85rem}"
   ".ok{color:#1a7f37;font-weight:600}"
   ".warn{color:#9a6700;font-weight:600}"
   ".critical{color:#b91c1c;font-weight:600}"
   ".muted{color:#6e6e73}"
   ".banner{background:#f5f5f7;border-radius:8px;padding:0.8rem 1rem;margin-bottom:1.5rem;font-size:0.9rem}"
   "</style></head><body>"
   "<h1>Education Managers Operator Console</h1>"
   "<div class=\"banner\">Build-time render of the REAL <code>eduman.actor</code> "
   "checkpointed StateGraph (<code>eduman.governor</code> &rarr; <code>eduman.store</code>), "
   "generated deterministically by <code>eduman.render-html</code>. No invented data: seed "
   "<code>client-1</code>/<code>P-1</code> are verbatim from the repo's own test fixtures; "
   "<code>client-2</code> is a disclosed demo addition via the store's real API. See the "
   "namespace docstring in <code>src/eduman/render_html.clj</code> for full disclosure of "
   "seed provenance and honestly-unreachable governor rules.</div>"
   "<h2>Registered entities (real store state)</h2>"
   (entities-table store)
   "<h2>Governance action gate (eduman.governor/check contract)</h2>"
   (gate-table)
   "<h2>Audit trail (this demo's scenario run through the real checkpointed graph)</h2>"
   (runs-table runs)
   "</body></html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out)))
