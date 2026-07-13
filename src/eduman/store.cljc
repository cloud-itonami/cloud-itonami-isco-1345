(ns eduman.store
  "SSoT for the ISCO-08 1345 community education managers actor
  (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors section).
  Modeled on cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client  — a registered organization (:client-id, :name)
    program — a registered academic program {:program-id :client-id
              :name :required-credits number
              :accreditation-issued-day int
              :accreditation-expiry-day int}. `:required-credits` is
              the registered minimum credit-hour sum a graduation
              approval's completed-credits must meet or exceed;
              `:accreditation-issued-day`/`:accreditation-expiry-day`
              is the registered accreditation window (simple
              monotonic day clock, day 0 = epoch for this program) a
              proposed as-of day must fall inside.
    record  — a committed operating record (approved graduation) —
              written ONLY via commit-record!.
    ledger  — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (program [s program-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-program! [s p])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (program [_ program-id] (get-in @a [:programs program-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-program! [s p]
    (swap! a assoc-in [:programs (:program-id p)] p) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :programs {} :records [] :ledger []}
                                   seed)))))
