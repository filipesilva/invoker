(ns invoker.md)

;; would be cool to have a default text/md rendered or even encoder
;; rails now seems to try and print any object as md in their format negotiation

;; From https://clojurians.slack.com/archives/C03S1KBA2/p1588517547414400
;;
#_(defn write-markdown [hiccup]
  (let [[h & t] hiccup
        recur-join (fn [spacing] (string/join spacing (map write-markdown t)))]
    (cond
      (= h :div)
      (recur-join "\n\n")

      (= h :h1)
      (str "# " (recur-join " "))

      (= h :p)
      (recur-join " ")

      (= h :em)
      (str "_" (recur-join " ") "_")

      (= h :strong)
      (str "**" (recur-join " ") "**")

      (string? hiccup)
      hiccup

      (= h :span)
      (recur-join " ")
      )))

#_(deftest test-write-markdown
  (are [md ast] (= md (write-markdown ast))
    "hello" "hello"
    "# Header" [:h1 "Header"]
    "# Header" [:div [:h1 "Header"]]
    "_emphasized_" [:em "emphasized"]
    "**bold**" [:strong "bold"]
    "hello" [:span "hello"]))

;; nice list of stuff to support
;; https://github.com/nextjournal/markdown/blob/main/src/nextjournal/markdown/transform.cljc
;;
