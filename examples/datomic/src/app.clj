(ns app
  (:require
   [datomic.api :as d]
   [filipesilva.datomic-pro-manager :as dpm]))

(def db-uri "datomic:sql://app?jdbc:sqlite:./storage/sqlite.db")
(def *conn (atom nil))

(defn start []
  (future (dpm/up))
  (dpm/wait-for-up)
  (d/create-database db-uri)
  (reset! *conn (d/connect db-uri)))

(defn db-stats
  {:invoker/http true}
  []
  (d/db-stats (d/db @*conn)))
