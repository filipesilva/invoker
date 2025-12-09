(ns invoker.cli
  (:refer-clojure :exclude [test])
  (:require
   [babashka.process :as process]
   [clj-reload.core :as clj-reload]
   [clojure+.test :as clojure+.test]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.repl]
   [clojure.repl.deps]
   [clojure.tools.namespace.find :as ns-find]
   [invoker.http :as http]
   [invoker.repl :as repl]
   [invoker.utils :as utils]))

(def ^:dynamic *cmd* nil)

(defn invoke [cmd]
  (let [{:keys [exit]} (:opts cmd)]
    (try
      (let [[var raw-args]       (utils/parse-var-and-args (:args cmd) (:opts cmd))
            [args opts]          (utils/parse-raw-args var raw-args)
            cmd-opts             (select-keys (:opts cmd) [:content-type :accept :ex-trace])
            [args body]          (if (:content-type cmd-opts)
                                   [(butlast args) (last args)]
                                   [args])
            {:keys [exception?
                    exception-str
                    return-str]} (binding [*cmd* cmd]
                                   (utils/invoke (merge {:var var, :args args, :body body :opts opts} cmd-opts)))]
        (if exception?
          (utils/print-err-exit exit 1 exception-str)
          (print return-str)))
      (catch ^:sci/error Exception e
        (if (utils/ex-info-msgs (ex-message e))
          (utils/print-err-exit exit 2 e)
          (throw e))))))


(defn http
  "Start an HTTP server, using global cli command options. Will start nREPL server is none exists."
  []
  (http/server (:opts *cmd*)))

(defn repl
  "Start a nREPL client, using global cli command options. Will start nREPL server is none exists.
  Note: this client always runs as a clj process because it doesn't support bb."
  []
  (repl/client (:opts *cmd*)))

(defn reload
  "Reload changed namespaces, or all namespaces if all is true."
  [& {:keys [all]}]
  (clj-reload/reload (when all {:only :all})))

(defn test
  "Run tests for symbols, or all tests in the test folder if no symbols are passed.
  Reloads changed namespaces before running tests."
  [& symbols]
  (reload)
  (let [syms (map symbol symbols)
        _ (when (empty? syms) (run! require (ns-find/find-namespaces-in-dir (io/file "test"))))
        {:as summary, :keys [fail error]} (apply clojure+.test/run syms)]
    (when (pos-int? (+ fail error))
      (throw (ex-info "Tests failed" summary)))))

(defn dir
  "Prints a sorted directory of public vars in a namespace, or ns-default."
  ([]
   (if-let [ns-default (-> *cmd* :opts :ns-default)]
     (dir (str ns-default))
     (throw (ex-info "No namespace provided and no ns-default set" {}))))
  ([nsname]
   (utils/require-ns-or-sym nsname)
   (eval `(clojure.repl/dir ~(edn/read-string nsname)))))

(defn doc
  "Prints documentation for a var or special form given its name,
  or for a spec if given a keyword"
  [name]
  (utils/require-ns-or-sym name)
  (eval `(clojure.repl/doc ~(edn/read-string name))))

(defn source
  "Prints the source code for the given symbol, if it can find it.
  This requires that the symbol resolve to a Var defined in a
  namespace for which the .clj is in the classpath."
  [n]
  (utils/require-ns-or-sym n)
  (eval `(clojure.repl/source ~(edn/read-string n))))

(defn find-doc
  "Prints documentation for any var whose documentation or name
  contains a match for re-string-or-pattern"
  [str-or-pattern]
  (clojure.repl/find-doc (re-pattern str-or-pattern)))

(defn apropos
  "Given a regular expression or stringable thing, print all public
  definitions in all currently-loaded namespaces that match the str-or-pattern."
  [str-or-pattern]
  (run! println (clojure.repl/apropos (re-pattern str-or-pattern))))

(defn add-lib
  "Given a lib that is not yet on the repl classpath, make it available by
  downloading the library if necessary and adding it to the classloader.
  Libs already on the classpath are not updated."
  [lib]
  (clojure.repl.deps/add-lib (symbol lib)))

(defn sync-deps
  "Calls add-libs with any libs present in deps.edn but not yet present on the classpath."
  []
  (clojure.repl.deps/sync-deps))

(defn devtools
  "Call devtools var."
  []
  (if-let [var (-> *cmd* :opts :devtools)]
    ((requiring-resolve var))
    (throw (ex-info "No devtools symbol provided" *cmd*))))

(defn setup
  "Call setup var."
  []
  (if-let [var (-> *cmd* :opts :setup)]
    ((requiring-resolve var))
    (throw (ex-info "No setup symbol provided" *cmd*))))

(defn clojuredocs
  "Search ClojureDocs for q."
  [q]
  (let [url (str "https://clojuredocs.org/search?q=" q)]
    (process/shell "open" url)))

(defn exit
  "Exit the process with exit-code or 0."
  ([]
   (exit 0))
  ([exit-code]
   (System/exit exit-code)))
