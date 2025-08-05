(ns invoker.http
  (:require
   [clojure.string :as str]
   [invoker.repl :as repl]
   [invoker.utils :as utils]
   [org.httpkit.server :as httpkit.server]
   [ring.middleware.resource :as rmr]
   [ring.util.codec :as codec]
   [ring.util.request :as ruq]))

(def ^:dynamic *req* nil)

(defn req [] *req*)

(defn invoke [{:as req, :keys [uri query-string headers]}]
  (try
    (let [var-and-args           (mapv codec/url-decode  (remove empty? (str/split uri #"/")))
          [var raw-args]         (utils/parse-var-and-args var-and-args)
          kv-args                (when query-string
                                   (mapcat (fn [[k v]] [(str ":" k) v]) (codec/form-decode query-string)))
          [args opts']           (utils/parse-raw-args var (into raw-args kv-args))
          body'                  (ruq/body-string req)
          {:keys [exception?
                  exception-str
                  return-str
                  content-type]} (binding [*req* req]
                                   (utils/invoke {:var          var,
                                                  :args         args
                                                  :opts         opts'
                                                  :body         body'
                                                  :content-type (get headers "content-type")
                                                  :accept       (get headers "accept")}))]
      (if exception?
        {:status       400
         :content-type content-type
         :body         exception-str}
        {:status       200
         :content-type content-type
         :body         return-str}))
    (catch Exception e
      (if-let [status (-> e ex-data :status)]
        {:status status}
        (do
          (utils/print-err-exit false 2 e)
          {:status 500})))))

(defn wrap-resource [handler]
  (rmr/wrap-resource handler "public"))

(defn middleware []
  [wrap-resource])

(defn handler []
  (reduce #(%2 %1) invoke (middleware)))

(defn server [cmd]
  (let [http-port 80
        repl-port 2525
        start-repl? true]
    (utils/ensure-http-port-not-taken http-port)
    (when start-repl?
      (utils/ensure-repl-port-not-taken repl-port)
      (future (repl/server cmd)))
    (httpkit.server/run-server (handler) {:port http-port})
    (utils/wait-for-port http-port)
    (println (str "Started HTTP server at http://localhost" (when-not (= http-port 80) http-port)))
    @(promise)))

;; TODO
;; use to make urls to put in htmx, elsewhere
(defn url-for [sym & args])
