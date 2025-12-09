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

(defn invoke [opts {:as req, :keys [uri query-string headers]}]
  (try
    (let [var-and-args           (mapv codec/url-decode  (remove empty? (str/split uri #"/")))
          [var raw-args]         (utils/parse-var-and-args var-and-args)
          kv-args                (when query-string
                                   (mapcat (fn [[k v]] [(str ":" k) v]) (codec/form-decode query-string)))
          [args opts']           (utils/parse-raw-args var (into raw-args kv-args))
          body'                  (ruq/body-string req)
          cmd-opts               (select-keys opts [:parse :render :ex-trace])
          {:keys [exception?
                  exception-str
                  return-str
                  content-type]} (binding [*req* req]
                                   (utils/invoke (merge {:var          var,
                                                         :args         args
                                                         :opts         opts'
                                                         :body         body'
                                                         :content-type (get headers "content-type")
                                                         :accept       (get headers "accept")}
                                                        cmd-opts)))]
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

(defn handler [opts]
  (reduce #(%2 %1) (partial invoke opts) (middleware)))

(defn server [{:as opts, :keys [http-port http-handler repl-connect]}]
  (let [http-handler (requiring-resolve http-handler)]
    (utils/ensure-http-port-not-taken http-port)
    (when (nil? repl-connect)
      (let [port (utils/port-or-random (:repl-port opts))]
        (utils/ensure-repl-port-not-taken port)
        (future (repl/server (assoc opts :repl-port port)))
        (utils/wait-for-port port)))
    (httpkit.server/run-server (http-handler opts) {:port http-port})
    (utils/wait-for-port http-port)
    (utils/write-port-file ".http-port" http-port)
    (println (str "Started HTTP server at http://localhost" (when-not (= http-port 80) http-port)))
    @(promise)))

