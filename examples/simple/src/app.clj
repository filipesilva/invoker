(ns app)

(defn my-fn
  "My doc"
  {:invoker/http true}
  [x y & {:as opts}]
  [x y opts])

(defn index
  {:invoker/http true}
  []
  [:h1 "Hello World!"])

(defn render-todo
  [{:keys [done content]}]
  [:div
   [:h1 content [:input {:type :checkbox, :checked done}]]])

(defn todo
  {:invoker/http true
   :invoker/pre-render {:text/html render-todo}}
  []
  {:id      42
   :done    false
   :content "foo the bar"})

(require '[invoker.cron :as cron])
(require '[filipesilva.inst :as inst])

(defn still-alive
  ;; every minute
  {:invoker/cron "* * * * *"}
  []
  (println "hello at" (cron/t) (inst/inst)))
