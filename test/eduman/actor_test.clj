(ns eduman.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [eduman.actor :as actor]
            [eduman.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-program! st {:program-id "P-1" :client-id "client-1"
                                 :name "diploma-in-trades"
                                 :required-credits 120
                                 :accreditation-issued-day 100
                                 :accreditation-expiry-day 400})
    st))

(deftest commits-a-sufficient-credit-in-window-graduation
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-graduation :stake :low
                 :program-id "P-1" :completed-credits 130 :as-of-day 200}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-insufficient-credit-graduation
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-graduation :stake :low
                 :program-id "P-1" :completed-credits 60 :as-of-day 200}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-waives-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-credit-waiver :stake :high
                 :program-id "P-1"}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
