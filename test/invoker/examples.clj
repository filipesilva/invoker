(ns invoker.examples
  (:require [clojure.string :as str]))

(def an-int 1)
(def a-map {:a 1})
(def a-vec [1 2])
(def a-set #{:a :b})
(defn a-fn "shows in nvk doc invoker.examples/a-fn" [] 2)
(defn another-fn [x y] [x y])
(def an-atom (atom {:a 1}))
(defn argv [& args] (vec args))

(defn args
  {:invoker/args {:coerce {:a :keyword, :b :long, :c :edn}}}
  [m] m)

(defn parse
  {:invoker/parse {:application/foo (constantly {:a "b"})}}
  [x] x)

(defn render
  {:invoker/render {:application/foo   (constantly "foo")
                    :application/throw (fn [_x] (throw (ex-info "foo" {})))}}
  [x] x)

(defn pre-render
  {:invoker/pre-render {:application/edn last
                        :text/plain      #(str/join " " %)
                        :text/html       (fn [[x y]] (into [:h1 x " " y]))}}
  [x]
  ["hello" x])

(defn exception []
  (/ 1 0))
