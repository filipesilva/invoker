(ns invoker-test
  (:require
   [cheshire.core :as json]
   [clojure.pprint :as pprint]
   [clojure.test :refer [deftest is are testing]]
   [invoker.cli :as cli]
   [invoker.examples :as examples]
   [invoker.http :as http]
   [invoker.utils :as utils]))

(deftest require-ns-or-sym-test
  (is (= (find-ns 'clojure.string) (utils/require-ns-or-sym "clojure.string")))
  (is (= #'clojure.string/join (utils/require-ns-or-sym "clojure.string/join")))
  (is (= (find-ns 'clojure.set) (utils/require-ns-or-sym 'clojure.set)))
  (is (= #'clojure.set/union (utils/require-ns-or-sym 'clojure.set/union)))

  (are [sexp] (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot find symbol" sexp)
    (utils/require-ns-or-sym "not.a.namespace")
    (utils/require-ns-or-sym "clojure.string/not-a-fn")
    (utils/require-ns-or-sym 'also.not.a.namespace)
    (utils/require-ns-or-sym 'clojure.set/not-a-fn)))

(deftest simple-symbol-str?-test
  (are [s] (utils/simple-symbol-str? s)
    "clojure.string"
    "foo"
    "my-fn!"
    "fn+")

  (are [s] (not (utils/simple-symbol-str? s))
    "clojure.string/bar"
    "foo/bar/baz"
    "join bad"
    "foo/bar{"
    "ns/fn["
    123
    nil))

(deftest parse-var-and-args-test
  (is (= [#'examples/a-fn []]
         (utils/parse-var-and-args ["invoker.examples/a-fn"])
         (utils/parse-var-and-args ["invoker.examples" "a-fn"])
         (utils/parse-var-and-args ["invoker" "examples" "a-fn"])))

  (is (= [#'examples/a-fn ["a" "1" ":a"]]
         (utils/parse-var-and-args ["invoker.examples/a-fn" "a" "1" ":a"])
         (utils/parse-var-and-args ["invoker" "examples" "a-fn" "a" "1" ":a"])))

  (are [sexp] (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot resolve var" sexp)
    (utils/parse-var-and-args nil)
    (utils/parse-var-and-args [])
    (utils/parse-var-and-args ["invoker.examples/not-a-fn"])
    (utils/parse-var-and-args ["invoker" "examples/not-a-fn"])
    (utils/parse-var-and-args ["invoker" "examples" "not-a-fn"]))

  (testing "ns-default"
    (is (= [#'examples/a-fn ["1"]]
           (utils/parse-var-and-args ["a-fn" "1"] :ns-default 'invoker.examples)))
    (is (= [#'examples/a-fn ["1"]]
           (utils/parse-var-and-args ["invoker" "examples" "a-fn" "1"] :ns-default 'some.other.ns))))

  (testing "ns-aliases"
    (is (= [#'examples/a-fn ["1"]]
           (utils/parse-var-and-args ["ex/a-fn" "1"] :ns-aliases '{ex invoker.examples})
           (utils/parse-var-and-args ["ex" "a-fn" "1"] :ns-aliases '{ex invoker.examples})))))

(deftest parse-raw-args-test
  (are [raw-args ret] (= ret (utils/parse-raw-args #'examples/a-fn raw-args))
    []               [[] {}]
    ["a" "1" "true"] [["a" 1 true] {}]
    ["1" ":a"]       [[1] {:a true}]
    ["1" ":a" "2"]   [[1] {:a 2}])

  (is (= [[] {:a :abc, :b 123, :c {:d 456}}]
         (utils/parse-raw-args #'examples/args [":a" "abc" ":b" "123" ":c" "{:d 456}"]))))

(deftest mime-match-test
  (let [m {:application/edn  1
           :application/json 2}]
    (is (= nil (utils/mime-match "foo" m)))
    (is (= :application/edn
           (utils/mime-match "application/edn" m)
           (utils/mime-match "*/*" m)
           (utils/mime-match "text/html, application/edn" m)))
    (is (= :application/json (utils/mime-match "application/json" m)))))

(deftest dispatch-test
  (testing "without args"
    (are [var] (= @var (utils/dispatch var nil))
      #'examples/an-int
      #'examples/a-map
      #'examples/a-vec
      #'examples/a-set)

    (is (= (examples/a-fn) (utils/dispatch #'examples/a-fn nil)))
    (is (= @examples/an-atom (utils/dispatch #'examples/an-atom nil))))

  (testing "with args"
    (are [var arg ret] (= (var arg) (utils/dispatch var [arg]))
      #'examples/a-map :a 1
      #'examples/a-vec 0  1
      #'examples/a-set 1  1)

    (is (= (@examples/an-atom :a) (utils/dispatch #'examples/an-atom [:a])))

    (testing "arity negotiation"
      (is (= (examples/a-fn)
             (utils/dispatch #'examples/a-fn nil)
             (utils/dispatch #'examples/a-fn [1])
             (utils/dispatch #'examples/a-fn [1 2])))

      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not enough args for var"
                            (utils/dispatch #'examples/another-fn [1])))

      (is (= (examples/another-fn 1 2) (utils/dispatch #'examples/another-fn [1 2])))

      (are [args ret] (= ret (utils/dispatch #'examples/argv args))
        nil          []
        [1]          [1]
        [{:a 1}]     [{:a 1}]
        [1 {:a 1}]   [1 {:a 1}]
        [1 2 {:a 1}] [1 2 {:a 1}]))))

(defn submap?
  ([expected actual]
   (= expected (select-keys actual (keys expected))))
  ([expected actual1 actual2]
   (= expected
      (select-keys actual1 (keys expected))
      (select-keys actual2 (keys expected))))
  ([expected actual1 actual2 actual3]
   (= expected
      (select-keys actual1 (keys expected))
      (select-keys actual2 (keys expected))
      (select-keys actual3 (keys expected)))))

(deftest invoke-test
  (let [pprint      #(with-out-str (pprint/pprint %))
        json-pprint #(json/generate-string % {:pretty true})
        edn-ret     (fn [x] {:return x, :return-str (pprint x), :content-type "application/edn"})
        json-ret    (fn [x] {:return x, :return-str (json-pprint x), :content-type "application/json"})]
    (testing "args"
      (is (= (edn-ret [1, 2])
             (utils/invoke :var #'examples/argv, :args [1, 2]))))

    (testing "opts"
      (is (= (edn-ret [{:a 1, :b 2}])
             (utils/invoke :var #'examples/argv, :opts {:a 1, :b 2}))))

    (testing "arg order"
      (is (= (edn-ret [1, "2", {:a 3}])
             (utils/invoke :var #'examples/argv, :args [1], :body "2", :opts {:a 3}))))

    (testing "parse"
      (is (= (edn-ret {:a "b"})
             (utils/invoke :var #'examples/parse, :body "{:a \"b\"}",     :content-type "application/edn")
             (utils/invoke :var #'examples/parse, :body "{\"a\": \"b\"}", :content-type "application/json")
             (utils/invoke :var #'examples/parse, :body "a=b",            :content-type "application/x-www-form-urlencoded")
             (utils/invoke :var #'examples/parse, :body "foo",            :content-type "application/foo")))

      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot negotiate content-type mime-type"
                            (utils/invoke :var #'examples/parse, :body "", :content-type "foo")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot parse body"
                            (utils/invoke :var #'examples/parse, :body ")", :content-type "application/edn"))))

    (testing "render"
      (is (= (edn-ret [{:a 1, :b 2}])
             (utils/invoke :var #'examples/argv, :opts {:a 1, :b 2})
             (utils/invoke :var #'examples/argv, :opts {:a 1, :b 2}, :accept "*/*")
             (utils/invoke :var #'examples/argv, :opts {:a 1, :b 2}, :accept "application/edn")
             (utils/invoke :var #'examples/argv, :opts {:a 1, :b 2}, :accept "foo, application/edn, bar")))
      (is (= (json-ret [1 {:a 1, :b 2}])
             (utils/invoke :var #'examples/argv, :args [1], :opts {:a 1, :b 2}, :accept "application/json")))
      (is (= {:return {:a 1}, :return-str "a=1", :content-type "application/x-www-form-urlencoded"}
             (utils/invoke :var #'examples/render, :args [{:a 1}], :accept "application/x-www-form-urlencoded")))
      (is (= {:return 1, :return-str "foo", :content-type "application/foo"}
             (utils/invoke :var #'examples/render, :args [1], :accept "application/foo")))

      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot negotiate accept mime-type"
                            (utils/invoke :var #'examples/render, :args [], :accept "foo")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot render return"
                            (utils/invoke :var #'examples/render, :args [], :accept "application/throw"))))

    (testing "pre-render"
      (is (= {:return ["hello" "world"], :return-str (pprint "world"), :content-type "application/edn"}
             (utils/invoke :var #'examples/pre-render, :args ["world"])))
      (is (= {:return ["hello" "world"], :return-str "hello world", :content-type "text/plain"}
             (utils/invoke :var #'examples/pre-render, :args ["world"] :accept "text/plain")))
      (is (= {:return ["hello" "world"], :return-str "<h1>hello world</h1>", :content-type "text/html"}
             (utils/invoke :var #'examples/pre-render, :args ["world"] :accept "text/html"))))

    (testing "exception"
      (is (= {:exception? true, :exception {:cause "Divide by zero"}, :exception-str (pprint {:cause "Divide by zero"}), :content-type "application/edn"}
             (utils/invoke :var #'examples/exception)))
      (is (= #{:cause :via :trace} (set (keys (:exception (utils/invoke :var #'examples/exception, :ex-trace true)))))))))

(defmacro with-err-str
  "Like with-out-str, but for *err*."
  [& body]
  `(let [s# (new java.io.StringWriter)]
     (binding [*err* s#]
       ~@body
       (str s#))))

(defmacro ignore-err
  [& body]
  `(let [s# (new java.io.StringWriter)]
     (binding [*err* s#]
       ~@body)))

(deftest cli-test
  (let [cli #(with-out-str (cli/invoke (assoc-in % [:opts :exit] false)))]
    (is (= "1\n"
           (cli {:args ["invoker" "examples" "an-int"]})
           (cli {:args ["invoker.examples/an-int"]})))
    (is (= "[\"foo\" {:a 1}]\n" (cli {:args ["invoker.examples/argv" "foo" ":a" "1"]})))
    (is (= "[{:body \"{:a 1}\"}]\n" (cli {:args ["invoker.examples/argv" ":body" "{:a 1}"]})))
    (is (= "[{:a 1}]\n" (cli {:args ["invoker.examples/argv" "{:a 1}"], :opts {:content-type "application/edn"}})))
    (is (= "hello world" (cli {:args ["invoker.examples/pre-render" "world"], :opts {:accept "text/plain"}})))
    (is (= "^{:bar 2} {:foo 1}\n" (cli {:args ["invoker.examples/return-meta"]}))))
  (let [cli           #(with-err-str (cli/invoke (assoc-in % [:opts :exit] false)))
        cli-exit-code #(:exit-code (ignore-err (cli/invoke (assoc-in % [:opts :exit] false))))]
    (is (= "{:cause \"Cannot resolve var\",\n :data\n {:var-and-args [\"foo\"], :ns-default nil, :ns-aliases nil, :status 404}}\n" (cli {:args ["foo"]})))
    (is (= 2 (cli-exit-code {:args ["foo"]})))
    (is (= "{:cause \"Divide by zero\"}\n" (cli {:args ["invoker.examples/exception"]})))
    (is (= 1 (cli-exit-code {:args ["invoker.examples/exception"]})))
    (is (re-find #"Cannot resolve all command symbols" (cli {:args ["invoker.examples/an-int"], :opts {:parse 'not.a/symbol}})))
    (is (= 2 (cli-exit-code {:args ["invoker.examples/an-int"], :opts {:parse 'not.a/symbol}})))))

(deftest http-test
  (let [http (http/handler {:http-all true, :parse #'invoker.utils/parse, :render #'invoker.utils/render})
        edn-resp (fn [b] {:status 200, :body b, :content-type "application/edn"})]
    (is (= (edn-resp  "1\n")
           (http {:uri "invoker/examples/an-int"})
           (http {:uri "invoker.examples/an-int"})
           (http {:uri "invoker.examples/an-int", :headers {"accept" "*/*"}})))

    (is (= (edn-resp "[\"foo\" {:a 1, :b 2}]\n",)
           ;; curl localhost/invoker/examples/argv/foo?a=1&b=2
           (http {:uri "invoker.examples/argv/foo", :query-string "a=1&b=2"})))

    (is (= (edn-resp "[\"a:/?b\"]\n")
           ;; curl localhost/invoker/examples/argv/a%3A%2F%3Fb
           (http {:uri "invoker.examples/argv/a%3A%2F%3Fb"})))

    (is (= (edn-resp "[\"{:a 1}\"]\n")
           ;; curl localhost/invoker/examples/argv -d '{:a 1}'
           (http {:uri "invoker.examples/argv", :body "{:a 1}"})))

    (is (= (edn-resp  "[{:a \"1\"}]\n")
           ;; curl localhost/invoker/examples/argv -d 'a=1&b=2'
           (http {:uri "invoker.examples/argv", :body "a=1", :headers {"content-type" "application/x-www-form-urlencoded"}})
           ;; curl localhost/invoker/examples/argv -d '{:a "1"}' -H "Content-Type: application/edn"
           (http {:uri "invoker.examples/argv", :body "{:a \"1\"}", :headers {"content-type" "application/edn"}})))

    (is (= {:status 200, :body "<h1>hello world</h1>", :content-type "text/html"}
           ;; curl -X POST localhost/invoker/examples/pre-render/world
           (http {:uri "invoker.examples/pre-render/world", :headers {"accept" "text/html"}})))

    (is (= {:status 404}
           (http {:uri "foo"})))
    (is (= {:status 500}
           (ignore-err (http {:uri "invoker.examples/render", :body ")", :headers {"accept" "application/throw"}}))))
    (is (= "{:cause \"Cannot render return\",\n :data {:return \")\", :content-type :application/throw}}\n"
           (with-err-str (http {:uri "invoker.examples/render", :body ")", :headers {"accept" "application/throw"}}))))
    (is (= {:status 400, :body "{:cause \"Divide by zero\"}\n", :content-type "application/edn"}
           (http {:uri "invoker.examples/exception"}))))

  (testing ":invoker/http metadata check"
    (let [handler (http/handler {:parse #'invoker.utils/parse, :render #'invoker.utils/render})]
      (is (= {:status 404}
             (handler {:uri "invoker.examples/an-int"})))
      (is (= {:status 200, :body "42\n", :content-type "application/edn"}
             (handler {:uri "invoker.examples/http"}))))))
