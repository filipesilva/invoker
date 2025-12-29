(ns invoker.cli
  (:refer-clojure :exclude [test])
  (:require
   [babashka.fs :as fs]
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
   [invoker.utils :as utils]
   [rewrite-clj.zip :as z]))

(def ^:dynamic *cmd* nil)

(declare doc)
(declare reload)

(defn invoke [cmd]
  (let [{:keys [exit help]} (:opts cmd)
        syms (select-keys (:opts cmd) [:parse :render :devtools :setup :http-handler])]
    (try
      (when-not (->> syms vals (remove nil?) (every? utils/val-or-sym))
        (throw (ex-info "Cannot resolve all command symbols" syms)))

     (when (-> cmd :opts :reload)
        (clj-reload/reload))

      (let [[var raw-args]       (utils/parse-var-and-args (:args cmd) (:opts cmd))
            [args opts]          (utils/parse-raw-args var raw-args)
            cmd-opts             (select-keys (:opts cmd) [:parse :render :content-type :accept :ex-trace])
            [args body]          (if (:content-type cmd-opts)
                                   [(butlast args) (last args)]
                                   [args])]
        (if help
          (doc (str (symbol var)))
          (let [{:keys [exception? exception-str return-str]}
                (binding [*cmd* cmd]
                  (utils/invoke (merge {:var var, :args args, :body body :opts opts} cmd-opts)))]
            (if exception?
              (utils/print-err-exit exit 1 exception-str)
              (print return-str)))))
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

(defn test
  "Run tests for symbols, or all tests in the test folder if no symbols are passed.
  Reloads changed namespaces before running tests."
  [& symbols]
  (when (-> *cmd* :opts :repl-connect)
    (reload))
  (let [syms (map symbol symbols)
        _ (when (empty? syms) (run! require (ns-find/find-namespaces-in-dir (io/file "test"))))
        {:as summary, :keys [fail error]} (apply clojure+.test/run syms)]
    (when (pos-int? (+ fail error))
      (throw (ex-info "Tests failed" summary)))))

(defn reload
  "Reload changed namespaces, or all namespaces if all is true."
  [& {:keys [all]}]
  (if (-> *cmd* :opts :repl-connect)
    (clj-reload/reload (when all {:only :all}))
    (throw (ex-info "No nREPL process to connect to" *cmd*))))

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
  Also writes the lib to deps.edn, preserving formatting and comments.
  Libs already on the classpath are not updated."
  [lib]
  (when utils/bb?
    (throw (ex-info "add-lib is not available in babashka, use with --dialect clj to create deps.edn" {})))
  (utils/when-not-bb?
   (require 'clojure.java.basis)
   (let [lib       (symbol lib)
         _         (clojure.repl.deps/add-lib lib)
         coord     (-> (clojure.java.basis/current-basis)
                       :libs
                       (get lib)
                       (select-keys [:mvn/version]))
         deps-file (cond
                     (fs/exists? "deps.edn") "deps.edn"
                     (fs/exists? "bb.edn")   "bb.edn"
                     :else                   (do
                                               (spit "deps.edn" "{}")
                                               "deps.edn"))
         file-zloc (z/of-file deps-file {:track-position? true})
         file-zloc (if (z/get file-zloc :deps)
                     file-zloc
                     (z/assoc file-zloc :deps {}))
         deps-zloc (z/get file-zloc :deps)
         first-key (z/down deps-zloc)
         indent    (if first-key (-> first-key z/position second dec) 1)
         zloc      (-> deps-zloc
                       (z/assoc lib coord)
                       (z/find-value z/next lib)
                       (cond-> first-key (-> (z/insert-newline-left)
                                             (z/insert-space-left indent)))
                       z/up)]
     (spit deps-file (z/root-string zloc))
     lib)))

(defn sync-deps
  "Calls add-libs with any libs present in deps.edn but not yet present on the classpath."
  []
  (when utils/bb?
    (throw (ex-info "sync-deps is not available in babashka" {})))
  (utils/when-not-bb?
   (clojure.repl.deps/sync-deps)))

(defn devtools
  "Call devtools var."
  []
  (if-let [var (-> *cmd* :opts :devtools)]
    ((requiring-resolve var))
    (throw (ex-info "No devtools symbol provided" *cmd*))))

(defn restart
  "Call stop then start vars."
  []
  (let [stop (-> *cmd* :opts :stop)
        start (-> *cmd* :opts :start)]
    (when-not (or stop start)
      (throw (ex-info "No start or stop symbols provided" *cmd*)))
    (when stop ((requiring-resolve stop)))
    (when start ((requiring-resolve start)))))

(defn clojuredocs
  "Search ClojureDocs for q."
  [q]
  (let [url (str "https://clojuredocs.org/search?q=" q)]
    (process/shell "open" url)
    url))

(defn exit
  "Exit the process with exit-code or 0."
  ([]
   (exit 0))
  ([exit-code]
   (System/exit exit-code)))

