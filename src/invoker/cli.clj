(ns invoker.cli
  (:refer-clojure :exclude [test])
  (:require
   [clj-reload.core :as clj-reload]
   [clojure+.test :as clojure+.test]
   [clojure.edn :as edn]
   [clojure.java.classpath :as cp]
   [clojure.repl]
   [clojure.string :as str]
   [clojure.tools.namespace.find :as ns-find]
   [invoker.utils :as utils]))

(defn load-all []
  (let [cwd (System/getProperty "user.dir")]
    (doseq [dir    (filter #(and (.isDirectory %)
                                 (or (not (.isAbsolute %))
                                     (.startsWith (.getPath %) cwd)))
                           (cp/classpath))
            ns-sym (ns-find/find-namespaces-in-dir dir)]
      (require ns-sym))))

(defn invoke [cmd]
  (let [{:keys [exit]} (:opts cmd)]
    (when (-> cmd :opts :load-all)
      (load-all))
    (when (-> cmd :opts :reload)
      (with-out-str (clj-reload/reload)))
    (try
      (let [[var raw-args]       (utils/parse-var-and-args (:args cmd) (:opts cmd))
            [args opts]          (utils/parse-raw-args var raw-args)
            cmd-opts             (select-keys (:opts cmd) [:content-type :accept :ex-trace])
            [args body]          (if (:content-type cmd-opts)
                                   [(butlast args) (last args)]
                                   [args])
            {:keys [exception?
                    exception-str
                    return-str]} (utils/invoke (merge {:var var, :args args, :body body :opts opts} cmd-opts))]
        (if exception?
          (utils/print-err-exit exit 1 exception-str)
          (print return-str)))
      (catch ^:sci/error Exception e
        (if (utils/ex-info-msgs (ex-message e))
          (utils/print-err-exit exit 2 e)
          (throw e))))))

(defn reload [cmd]
  (clj-reload/reload (when (-> cmd :opts :all) {:only :all})))

(defn dir [& args]
  (eval `(clojure.repl/dir ~(-> args first edn/read-string))))

(defn doc [& args]
  (eval `(clojure.repl/doc ~(-> args first edn/read-string))))

(defn source [& args]
  (eval `(clojure.repl/source ~(-> args first edn/read-string))))

(defn find-doc [& args]
  (clojure.repl/find-doc (str/join " " args)))

(defn apropos [& args]
  (run! println (clojure.repl/apropos (first args))))

(defn test [& args]
  (let [{:as summary, :keys [fail error]} (apply clojure+.test/run (map symbol args))]
    (when (pos-int? (+ fail error))
      (throw (ex-info "Tests failed" summary)))))
