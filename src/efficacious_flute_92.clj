(ns efficacious-flute-92
  (:require [clojure.java.io :as io]
            [org.httpkit.server :as server]
            ;; garden-id
            [hiccup.page :as page]
            [ring.middleware.session :as session]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [nextjournal.garden-id :as garden-id]
            ;; garden-email
            [ring.middleware.params :as ring.params]
            [nextjournal.garden-email :as garden-email]
            [nextjournal.garden-email.render :as render-email]
            [nextjournal.garden-email.mock :as mock-email]
            ;; garden-cron
            [nextjournal.garden-cron :as garden-cron]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.util.response :refer [response status]]
            [cheshire.core :as json]
            [watson :as w]
            ))

(def api-key (System/getenv "watson"))

(defn handle-prescription
  [prescription]
  (let [result (w/decompose-label-directions api-key
                                             prescription)]
    (response result)))

(defn handle-celd
  [prescription]
  (let [result (w/celd api-key
                       prescription)]
    (response result)))

(defn handle-translation
  [language prescription]
  (let [result (w/translate api-key
                            language
                            prescription)]
    (response result)))

(defn handle-head-request []
  (-> (response "")
      (status 202)))

(defroutes app-routes
  (HEAD "/" [] (handle-head-request))
  (POST "/prescription" {body :body}
        (let [prescription (slurp body)]
          (handle-prescription prescription)))
  (POST "/celd" {body :body}
        (let [prescription (slurp body)]
          (handle-celd prescription)))
  (POST "/translate/:language" [language :as {body :body}]
        (let [prescription (slurp body)]
          (handle-translation language
                              prescription)))
  (route/not-found "Not Found"))

(def wrapped-app
  (-> app-routes
      wrap-json-response))

(defn start! [opts]
  (let [server (server/run-server #'wrapped-app
                                  (merge {:legacy-return-value? false
                                          :host "0.0.0.0"
                                          :port 7777}
                                         opts))]
    (println (format "server started on port %s"
                     (server/server-port server)))))
