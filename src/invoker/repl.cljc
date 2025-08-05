(ns invoker.repl
  (:require
   [babashka.process :as process]
   [invoker.utils :as utils])
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

(defn ignore-sigint []
  (Signal/handle
     (Signal. "INT")
     (proxy [SignalHandler] []
       (handle [sig] #_(prn :sigint)))))

(defn server
  [cmd]
  (when (:ignore-sigint cmd)
    (ignore-sigint))
  (let [port (-> cmd :opts :repl-port)
        sym #?(:bb 'babashka.nrepl.server/start-server!
               :clj 'nrepl.server/start-server)]
    ((requiring-resolve sym) {:port port, :quiet true})
    (println (str "Started nREPL server at 0.0.0.0:" port)))
  ;; TODO: write port file
  @(promise))

(defn server-process [{:as cmd, :keys [opts]}]
  (let [port         (:repl-port opts)
        dialect      (:dialect opts)
        process-opts {:out :inherit, :err :inherit, :shutdown process/destroy-tree}
        process-args (utils/exec-args dialect 'invoker.repl/server (assoc cmd :ignore-sigint true))
        process      (apply process/process process-opts process-args)]
    (utils/wait-for-port port)
    process))

(defn run [{:as cmd, :keys [opts]}]
  (let [repl-port      2525
        create-server? true
        client         (requiring-resolve 'rebel-readline.nrepl/connect)]
    (when create-server?
      (utils/ensure-repl-port-not-taken repl-port)
      (server-process cmd))
    (println (str "Connecting to nREPL server at 0.0.0.0:" repl-port))
    (println "Quit REPL with ctrl+d, more help at https://github.com/bhauman/rebel-readline")
    (client {:port repl-port})))
