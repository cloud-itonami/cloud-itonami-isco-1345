(ns eduman.advisor
  "EducationManagersAdvisor — proposes a graduation operation (approve
  a graduation, approve a credit waiver) for a registered
  organization. Swappable mock/llm; the advisor ONLY proposes —
  `eduman.governor` checks the credit-hour arithmetic and
  accreditation window independently. Modeled on
  cloud-itonami-isco-4311's advisor.

  A proposal: {:op :approve-graduation|:approve-credit-waiver
               :effect :propose :program-id str :completed-credits
               number :as-of-day int :stake kw :confidence n
               :rationale str}")

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake program-id completed-credits as-of-day] :as request}]
  {:op op
   :effect :propose
   :program-id program-id
   :completed-credits completed-credits
   :as-of-day as-of-day
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are an education management advisor. Given a request, propose
   an :op, the :program-id, :completed-credits and :as-of-day, an
   honest :confidence and a :stake. Never call an under-credit or
   expired-accreditation graduation conforming — the governor checks
   both against the registered program record.")

(defn- parse-proposal [content]
  (try
    (let [p (read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
