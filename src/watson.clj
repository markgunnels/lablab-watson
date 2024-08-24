(ns watson
  (:require [clj-http.client :as client]
            [cheshire.core :as json]
            [clojure.core.async :refer [chan go go-loop <! <!! >! close! alts!]]
            [clojure.core.async :as async]
            [jsonista.core :as j]))

(defn ibm-ml-text-generation
  [access-token input & {:keys [model-id project-id]
                         :or {model-id "ibm/granite-34b-code-instruct"
                              project-id "817db2c1-f910-4602-80b8-5d05205f84d9"}}]
  (let [url "https://us-south.ml.cloud.ibm.com/ml/v1/text/generation?version=2023-05-29"
        headers {"Content-Type" "application/json"
                 "Accept" "application/json"
                 "Authorization" (str "Bearer " access-token)}
        body {:input input
              :parameters {:decoding_method "greedy"
                           :max_new_tokens 4096
                           :min_new_tokens 0
                           :stop_sequences []
                           :repetition_penalty 1}
              :model_id model-id
              :project_id project-id}
        response (client/post url
                              {:headers headers
                               :body (json/generate-string body)
                               :content-type :json
                               :accept :json})]
    (println response)
    (json/parse-string (:body response) true)))

(defn get-ibm-iam-token
  [api-key]
  (let [url "https://iam.cloud.ibm.com/identity/token"
        headers {"Content-Type" "application/x-www-form-urlencoded"}
        form-params {:grant_type "urn:ibm:params:oauth:grant-type:apikey"
                     :apikey api-key}
        response (client/post url
                              {:headers headers
                               :form-params form-params
                               :content-type :x-www-form-urlencoded
                               :accept :json})
        result (json/parse-string (:body response) true)]

    result
    ))

(defn long-str [& strings] (clojure.string/join "\n" strings))

(def celd-prompt
  (long-str
   "Task:"
   "Please take the provided SIG (label direction) and rewrite it according to the following guidelines:"
   "- Use four specific time periods to identify when medicine should be taken: morning, noon, evening, and bedtime."
   "- Simplify text to improve readability."
   "- Use numeric characters instead of words to detail the dose (e.g. \"2\" instead of \"two\")."
   "- Place each dose on a separate line, clearly identifying every time period a medicine is to be taken."
   ""
   "Example:"
   "Morning: Take 1 pill"
   "Noon: Take 2 pills"
   "Evening: Take 1 pill"
   "Bedtime: Take 1 pill"
   ""
   "Respond only with the rewritten SIG. Do not write an introduction, summary, explanation, or notes."
   ""
   "---"
   ""
   "SIG (label direction):"
   "%s"
   ""
   "---"
   ""
   "Respond only with the rewritten SIG. Do not write an introduction, summary, explanation, or notes."
   )
  )


(def frequency-prompt (long-str
  "Task:"
  "Given the following label directions for a medication, extract the frequency of administration as an integer representing how many times per day the medication should be taken or applied. If the frequency is not explicitly stated as per day, convert the frequency to a per-day equivalent (e.g., \"twice a day\" becomes 2, \"every 12 hours\" becomes 2, \"every other day\" becomes 0.5). If the frequency cannot be determined or is not specified, return null."
  ""
  "Return the answer as JSON using the following schema:"
  "{"
  "  \"$schema\": \"http://json-schema.org/draft-07/schema#\","
  "  \"type\": \"object\","
  "  \"properties\": {"
  "    \"frequency\": {"
  "      \"type\": \"integer\","
  "      \"minimum\": 1,"
  "      \"description\": \"The number of times an action is to be performed. Must be a positive integer.\""
  "    }"
  "  },"
  "  \"required\": [\"frequency\"],"
  "  \"additionalProperties\": false"
  "}"
  ""
  "---"
  ""
  "Label Directions:"
  "%s"
  ""
  "---"
  ""
  "Respond only with JSON. Do not write an introduction, summary, explanation, or notes.  Only JSON."
  ""
  "Answer: "
))

(def dosage-prompt (long-str
                    "Task:"
                    "Given the following label directions for a medication, extract the dosage as an integer representing the number of units (e.g., tablets, capsules, milliliters) to be taken or applied per administration. If the dosage is not explicitly stated, return null."
                    ""
                    "Return the answer as JSON using the following schema:"
                    "{"
                    "  \"$schema\": \"http://json-schema.org/draft-07/schema#\","
                    "  \"type\": \"object\","
                    "  \"properties\": {"
                    "    \"dosage\": {"
                    "      \"type\": \"integer\","
                    "      \"minimum\": 1,"
                    "      \"description\": \"The number of units to be taken or applied per administration. Must be a positive integer.\""
                    "    }"
                    "  },"
                    "  \"required\": [\"dosage\"],"
                    "  \"additionalProperties\": false"
                    "}"
                    ""
                    "---"
                    ""
                    "Label Directions:"
                    "%s"
                    ""
                    "---"
                    ""
                    "Respond only with JSON. Do not write an introduction, summary, explanation, or notes.  Only JSON."
                    ""
                    "Answer:"
                    ))

(defn generate [api-key
                prompt]
  (println prompt)
  (let [token-response (get-ibm-iam-token api-key)
        access-token (:access_token token-response)]
    (ibm-ml-text-generation access-token prompt)))


(defn process-prompts
  "Process a list of strings asynchronously and return results."
  [api-key prompts]
  (let [input-chan (async/chan)
        output-chan (async/chan)
        num-workers 3]  ; Adjust the number of workers as needed
    (dotimes [_ num-workers]
      (async/go
        (while true
          (when-let [s (async/<! input-chan)]
            (let [result (generate api-key s)]
              (async/>! output-chan result))))))
    (async/onto-chan! input-chan prompts)
    (async/<!!
     (async/go-loop [results []]
       (if (= (count results) (count prompts))
         results
         (recur (conj results (async/<! output-chan))))))))

(defn return-value
  [r]
  (-> r :results first :generated_text j/read-value))

(defn return-str
  [r]
  (-> r :results first :generated_text))

(defn decompose-label-directions
  [api-key label-directions]
  (let [prompts [(format frequency-prompt label-directions)
                 (format dosage-prompt label-directions)]
        results (process-prompts api-key prompts)
        prescription {}]
    (into {} (map return-value results))))

(defn celd
  [api-key label-directions]
  (let [prompt (format celd-prompt label-directions)
        token-response (get-ibm-iam-token api-key)
        access-token (:access_token token-response)
        result (ibm-ml-text-generation access-token prompt {:model-id "meta-llama/llama-3-405b-instruct"})]
    (return-str result)))
