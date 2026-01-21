(ns app
  (:require
   [datomic.api :as d]
   [filipesilva.datomic-pro-manager :as dpm]))

(def db-uri "datomic:sql://app?jdbc:sqlite:./storage/sqlite.db")
(def *conn (atom nil))

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

(defn start []
  (future (dpm/up))
  (dpm/wait-for-up)
  (d/create-database db-uri)
  (reset! *conn (d/connect db-uri)))

(defn db-stats
  {:invoker/http true}
  []
  (d/db-stats (d/db @*conn)))
