(ns app-test
  (:require
   [clojure.test :refer [deftest is]]
   [app :as app]))

(deftest my-fn-test
  (is (= [1 2 {:a 3}] (app/my-fn 1 2 :a 3))))

