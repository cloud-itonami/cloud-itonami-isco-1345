(ns eduman.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [eduman.store :as store]
            [eduman.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-program! st {:program-id "P-1" :client-id "client-1"
                                 :name "diploma-in-trades"
                                 :required-credits 120
                                 :accreditation-issued-day 100
                                 :accreditation-expiry-day 400})
    st))

(defn- grad [credits day]
  {:op :approve-graduation :effect :propose :program-id "P-1"
   :completed-credits credits :as-of-day day :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-sufficient-credits-and-within-window
  (let [st (fresh-store)
        v (governor/check req {} (grad 130 200) st)]
    (is (:ok? v))))

(deftest ok-at-exact-credits-and-window-edges
  (testing "the credit floor and accreditation window boundaries are inclusive"
    (let [st (fresh-store)]
      (is (:ok? (governor/check req {} (grad 120 100) st)))
      (is (:ok? (governor/check req {} (grad 120 400) st))))))

(deftest hard-on-insufficient-credits
  (testing "credit arithmetic has no partial credit"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (grad 90 200) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :insufficient-credits (:rule %)) (:violations v))))))

(deftest hard-on-before-accreditation-window
  (testing "an expired accreditation certificate is void"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (grad 130 50) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :outside-accreditation-window (:rule %)) (:violations v))))))

(deftest hard-on-after-accreditation-window
  (let [st (fresh-store)
        v (governor/check req {} (assoc (grad 130 500) :confidence 0.99) st)]
    (is (:hard? v))
    (is (some #(= :outside-accreditation-window (:rule %)) (:violations v)))))

(deftest hard-on-unknown-program
  (let [st (fresh-store)
        v (governor/check req {} (assoc (grad 130 200) :program-id "P-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-program (:rule %)) (:violations v)))))

(deftest hard-on-foreign-program
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (let [v (governor/check {:client-id "client-2"} {} (grad 130 200) st)]
      (is (:hard? v))
      (is (some #(= :program-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (grad 130 200) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (grad 130 200) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest escalates-credit-waiver
  (let [st (fresh-store)
        v (governor/check req {} {:op :approve-credit-waiver :effect :propose
                                  :program-id "P-1" :confidence 0.9 :stake :high} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (grad 130 200) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
