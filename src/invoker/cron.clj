(ns invoker.cron
  (:refer-clojure :exclude [time])
  (:require
   [filipesilva.inst :as inst]
   [invoker.utils :as utils]))

(def ^:dynamic *t* nil)

(defn t
  "Returns the inst that triggered this run, if any."
  [] *t*)

(defonce state (atom nil))

(defn tick
  "Pure function. Given now, last-triggered times, and cron vars, returns
   {:to-trigger [{:var v :time t} ...] :last-triggered {var time ...}}.
   For each var, walks forward from its last-triggered time to find the latest
   trigger time <= now (fire once, skip intermediate missed triggers)."
  [now last-triggered cron-vars]
  (reduce
   (fn [acc v]
     (let [cron-expr (-> v meta :invoker/cron)
           from      (or (get last-triggered v) (get last-triggered (symbol v)) now)
           trigger   (loop [t from]
                       (let [nxt (inst/next t cron-expr)]
                         (if (> (inst-ms nxt) (inst-ms now))
                           ;; nxt is past now, so t is the latest trigger <= now
                           ;; but only if t is after from (meaning we found at least one)
                           (when (not= t from) t)
                           ;; nxt is <= now, keep walking
                           (recur nxt))))]
       (if trigger
         (-> acc
             (update :to-trigger conj {:var v :time trigger})
             (assoc-in [:last-triggered v] trigger))
         (assoc-in acc [:last-triggered v] (or from now)))))
   {:to-trigger [] :last-triggered {}}
   cron-vars))

(defn stop
  "Stops the cron scheduler."
  []
  (when @state
    (swap! state assoc :running? false)))

(defn start
  "Starts a future that gathers cron vars and checks every minute."
  []
  (stop)
  (reset! state {:last-triggered {} :running? true})
  (future
    (try
      (while (:running? @state)
        (when (:running? @state)
          (let [cron-vars (utils/gather :invoker/cron)
                now'      (inst/inst)
                result    (tick now' (:last-triggered @state) cron-vars)]
            (swap! state assoc :last-triggered (:last-triggered result))
            (doseq [{v :var, t :time} (:to-trigger result)]
              (future
                (try
                  (binding [*t* t]
                    (v))
                  (catch Throwable e
                    (let [m {:var (symbol v), :error (-> e Throwable->map (select-keys [:cause :data]))}]
                      (utils/print-err-exit false 1 (ex-info "Cron invocation error" m))))))))
          (let [ms-to-next (- (inst-ms (inst/+ (inst/next (inst/inst) "* * * * *") 10 :millis))
                              (inst-ms (inst/inst)))]
            (utils/sleep ms-to-next))))
      (catch Throwable e
        (utils/print-err-exit false 1 e)))))
