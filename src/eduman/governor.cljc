(ns eduman.governor
  "EducationManagersGovernor — the independent safety/traceability
  layer for the ISCO-08 1345 community education managers actor
  (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors section).
  Modeled on cloud-itonami-isco-4311's bookkeeping.governor. Academic
  twist: a graduation approval's completed-credits sum is checked
  arithmetically against the registered required-credits, and the
  proposed as-of day must fall inside the program's registered
  accreditation window — a degree is either earned and accredited, or
  the approval holds; there is no partial credit and no expired
  paper.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. program basis      — a graduation approval must cite a
                           REGISTERED program belonging to this
                           client.
    4. credit-hour arithmetic — the proposed completed-credits sum
                           must be >= the program's registered
                           :required-credits (arithmetic comparison,
                           not a judgement call).
    5. accreditation window — the proposed as-of day must satisfy
                           accreditation-issued-day <= as-of-day <=
                           accreditation-expiry-day (interval
                           containment against the registered
                           accreditation).
  ESCALATION invariants (:escalate? true, human sign-off):
    6. :op :approve-credit-waiver (granting credit outside the normal
                           sum).
    7. low confidence (< `confidence-floor`)."
  (:require [eduman.store :as store]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [request proposal]} client-record p]
  (let [{:keys [op completed-credits as-of-day]} proposal
        grad? (= :approve-graduation op)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and grad? (nil? p))
      (conj {:rule :unknown-program :detail "未登録 program への卒業承認は不可"})

      (and grad? p (not= (:client-id p) (:client-id request)))
      (conj {:rule :program-wrong-client :detail "program が別 client のもの"})

      (and grad? p (number? completed-credits)
           (< completed-credits (:required-credits p)))
      (conj {:rule :insufficient-credits
             :detail (str "修得単位 " completed-credits " < 卒業要件 "
                          (:required-credits p) "（単位算術に部分点はない）")})

      (and grad? p (integer? as-of-day)
           (or (< as-of-day (:accreditation-issued-day p))
               (> as-of-day (:accreditation-expiry-day p))))
      (conj {:rule :outside-accreditation-window
             :detail (str "day " as-of-day " が認定窓 [" (:accreditation-issued-day p) ", "
                          (:accreditation-expiry-day p) "] の外（失効した認定証は無効）")}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `eduman.store/Store`. Pure — never mutates the
  store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        p (some->> (:program-id proposal) (store/program store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record p)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (= :approve-credit-waiver (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
