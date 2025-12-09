(ns invoker.repl
  (:require
   [babashka.process :as process]
   [invoker.utils :as utils]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (sun.misc Signal SignalHandler)))

;; Why ignore sigint?
;; Because sometimes I want the parent to handle ctrl+c (sigint), but the OS itself sends it to all child processes.
;; Rebel handles ctrl+c itself, for instance.
;; This way I can spawn clj/bb exec processes that don't handle it.
;;
;; See https://bugs.openjdk.org/browse/JDK-8168608:
;;   Another aspect of "background" is whether signals from the keyboard (^C) reach the child.
;;   If the parent is in the foreground of the invoking shell, a ^C will result in SIGINT being
;;   sent to all processes of the process group, which includes the child.
;;   The child could put itself into a different process group, which will prevent this.
;;
;; Minimal repro showing this on both bb and clj:
;;   (require '[babashka.process :as process])
;;   (process/process {:shutdown (fn [_] (println "shutdown"))} "bb" "nrepl-server" "5555")
;;   (sun.misc.Signal/handle (sun.misc.Signal. "INT") (proxy [sun.misc.SignalHandler] [] (handle [sig] (prn :sigint))))
;; then ctrl+c a few times, the child process will be shutdown, while the parent will log instead

(defn ignore-sigint! []
  (Signal/handle
     (Signal. "INT")
     (proxy [SignalHandler] []
       (handle [sig] #_(prn :sigint)))))

(defn server
  [{:keys [repl-port ignore-sigint]}]
  (when ignore-sigint
    (ignore-sigint!))
  (let [server'   (if utils/bb?
                    'babashka.nrepl.server/start-server!
                    'nrepl.server/start-server)
        port-file (io/file ".nrepl-port")]
    ((requiring-resolve server') {:port repl-port, :quiet true})
    (spit port-file (str repl-port))
    (.deleteOnExit port-file)
    (println (str "Started nREPL server at localhost:" repl-port)))
  @(promise))

(defn server-process [{:keys [repl-port dialect]}]
  (let [process-opts {:out :inherit, :err :inherit, :shutdown process/destroy-tree}
        process-args (utils/exec-args dialect 'invoker.repl/server {:repl-port repl-port :ignore-sigint true})
        process      (apply process/process process-opts process-args)]
    (utils/wait-for-port repl-port)
    process))

(defn client [{:as opts, :keys [repl-port repl-connect]}]
  (let [client'       (requiring-resolve 'rebel-readline.nrepl/connect)
        create-server (nil? repl-connect)
        [host port]   (if create-server
                        ["localhost" repl-port]
                        (let [[host port-str] (str/split repl-connect #":")]
                          [host (int (parse-long port-str))]))
        port          (utils/port-or-random port)]
    (when create-server
      (utils/ensure-repl-port-not-taken port)
      (server-process (assoc opts :repl-port port)))
    (println (str "Connecting to nREPL server at " host ":" port))
    (println "Quit REPL with ctrl+d, autocomplete with tab")
    (println "More help at https://github.com/bhauman/rebel-readline")
    (client' {:host host, :port port})))

