(ns invoker.utils
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.nrepl-client :as nrepl-client]
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [hiccup2.core :as hiccup]
   [hickory.core :as hickory]
   [medley.core :as m]
   [ring.util.codec :as codec])
  (:import
   [java.net Socket SocketException]))

(def ex-info-msgs
  #{"Cannot resolve var"
    "Not enough args for var"
    "Cannot resolve all command symbols"
    "Cannot parse body"
    "Cannot render return"
    "Cannot negotiate content-type mime-type"
    "Cannot negotiate accept mime-type"
    "Cannot connect to nREPL server"
    "nREPL server exited"})

(defmacro try-bool
  [expr]
  `(try
     ~expr
     true
     (catch Exception _#
       false)))

(defmacro catch-nil
  "Wraps expr in a try/catch that returns nil on err."
  [expr]
  `(try ~expr
     (catch Exception _#)))

(defn devtools []
  (try-bool ((requiring-resolve 'clojure+.print/install!)))
  (try-bool ((requiring-resolve 'clojure+.error/install!)))
  (try-bool ((requiring-resolve 'clojure+.test/install!)))
  (try-bool ((requiring-resolve 'clojure+.hashp/install!)))
  (try-bool ((requiring-resolve 'clj-reload.core/init) {})))

(comment
  (devtools)
  ,)

(defn require-ns-or-sym
  "Given a string or symbol, requires and returns the namespace if unqualified,
   or requires the namespace and returns the var if qualified."
  [ns-or-sym]
  (let [sym (if (string? ns-or-sym) (symbol ns-or-sym) ns-or-sym)
        ex  (ex-info "Cannot find symbol" {:ns-or-sym ns-or-sym})]
    (try
      (or
       (if (namespace sym)
         (requiring-resolve sym)
         (do (require sym) (find-ns sym)))
       (throw ex))
      (catch Exception _
        (throw ex)))))

(defn- apply-ns-alias [sym ns-aliases]
  (if-let [expanded (get ns-aliases (symbol (namespace sym)))]
    (symbol (str expanded) (name sym))
    sym))

(defn- resolve-var-str [var-str ns-aliases]
  (try (requiring-resolve (apply-ns-alias (symbol var-str) ns-aliases))
       (catch Exception _)))

(defn simple-symbol-str? [s]
  ;; https://clojure.org/reference/reader#_symbols
  (and (string? s) (not (re-find #"[^a-zA-Z0-9*+!\-_'?<>=.]" s))))

(defn val-or-sym [x]
  (if (symbol? x)
    (catch-nil @(requiring-resolve x))
    x))

(defn parse-var-and-args [var-and-args & {:keys [ns-default ns-aliases]}]
  (let [ns-aliases (val-or-sym ns-aliases)
        not-found  (ex-info "Cannot resolve var" {:var-and-args var-and-args,
                                                  :ns-default ns-default
                                                  :ns-aliases ns-aliases
                                                  :status 404})]
    (or
     (cond
       (empty? var-and-args)
       nil

       (str/includes? (first var-and-args) "/")
       [(or (resolve-var-str (first var-and-args) ns-aliases)
            (throw not-found))
        (vec (rest var-and-args))]

       :else
       (loop [[x & xs] var-and-args
              ns-str ""]
         (when (and (simple-symbol-str? x)
                    (simple-symbol-str? ns-str))
           (or
            ;; try ns-default first if set and no ns accumulated yet
            (when (and ns-default (= ns-str ""))
              (when-let [var (resolve-var-str (str ns-default "/" x) ns-aliases)]
                [var (vec xs)]))
            (when-let [var (resolve-var-str (str ns-str "/" x) ns-aliases)]
              [var (vec xs)])
            (let [ns-str'(if (seq ns-str) (str ns-str "." x) x)]
              (recur xs ns-str'))))))
     (throw not-found))))

(defn parse-raw-args [var raw-args]
  (let [{:keys [args opts]} (cli/parse-args raw-args (-> var meta :invoker/args))
        opts' (with-meta opts nil)
        args' (mapv cli/auto-coerce args)]
    [args' opts']))

;; common types, and all types
;; https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/MIME_types/Common_types
;; https://www.iana.org/assignments/media-types/media-types.xhtml

(def parse
  {:application/x-www-form-urlencoded #(->> % codec/form-decode (m/map-keys keyword))
   :application/json                  #(json/parse-string % true)
   :application/edn                   edn/read-string
   :text/html                         #(hickory/as-hiccup (hickory/parse %))
   :text/plain                        str})

(def render
  {:application/x-www-form-urlencoded codec/form-encode
   :application/json                  #(json/generate-string % {:pretty true})
   :application/edn                   #(with-out-str (binding [*print-meta* true] (pprint/pprint %)))
   :text/html                         #(str (hiccup/html %))
   :text/plain                        str})

(def mime-wildcard-defaults
  {:*/* :application/edn})

(defn mime-type-str [x]
  (if (keyword? x)
    (subs (str x) 1)
    x))

(defn mime-match [mime-types-str mime-map]
  (let [mime-types (->> (str/split mime-types-str #",")
                        (map str/trim)
                        (map keyword)
                        (map #(get mime-wildcard-defaults % %)))]
    (some (-> mime-map keys set) mime-types)))

(defn variadic? [arglists]
  (->> arglists (mapcat identity) (some #{'&}) boolean))

(defn dispatch [var args]
  (let [var-val (deref var)
        var-val (if (instance? clojure.lang.IDeref var-val)
                  @var-val
                  var-val)]
    (if (empty? args)
      (if (fn? var-val)
        (var-val)
        var-val)
      (let [args-count (count args)
            arglists   (-> var meta :arglists)
            arity      (if (variadic? arglists)
                         args-count
                         (or (some->> arglists
                                      (map count)
                                      (remove #(> % args-count))
                                      not-empty
                                      (apply max))
                             (when arglists
                               (throw (ex-info "Not enough args for var"
                                               {:var var, :argslists arglists})))
                             ;; might be calling over a value that has a fn
                             ;; interface, go along with it
                             args-count))]
        (apply var-val (take arity args))))))

(defn invoke [& {:keys [var args body opts content-type accept ex-trace]}]
  (let [parse               (merge (val-or-sym parse) (-> var meta :invoker/parse))
        render              (merge (val-or-sym render) (-> var meta :invoker/render))
        pre-render          (merge {} (-> var meta :invoker/pre-render))
        body-content-type   (or (not content-type)
                                (not body)
                                (mime-match content-type parse)
                                (throw (ex-info "Cannot negotiate content-type mime-type" {:content-type content-type, :status 415})))
        return-content-type (or (mime-match (or accept "*/*") render)
                                (throw (ex-info "Cannot negotiate accept mime-type" {:accept accept, :status 406})))
        parse-f             (or (parse body-content-type) identity)
        pre-render-f        (or (pre-render return-content-type) identity)
        render-f            (render return-content-type)
        exception-f         #(cond-> (Throwable->map %)
                               (not ex-trace) (select-keys [:cause :data]))
        str-f               #(try
                               (-> % pre-render-f render-f)
                               (catch Exception _
                                 (throw (ex-info "Cannot render return" {:return %, :content-type return-content-type}))))
        args                (try
                              (-> []
                                  (into args)
                                  (into (when body [(parse-f body)]))
                                  (into (when (not-empty opts) [opts])))
                              (catch Exception _
                                (throw (ex-info "Cannot parse body" {:body (last args), :content-type body-content-type, :status 400}))))
        [return exception]  (try
                              [(dispatch var args) false]
                              (catch Exception t
                                [nil (exception-f t)]))
        exception?          (boolean exception)]
    (merge
     {:content-type (mime-type-str return-content-type)}
     (if exception?
       {:exception?    exception?
        :exception     exception
        :exception-str (str-f exception)}
       {:return     return
        :return-str (str-f return)}))))

(defn sleep [ms]
  (Thread/sleep ms))

(defn port-taken?
  "Returns true if host:port is taken, host defaults to localhost."
  ([port]
   (port-taken? "localhost" port))
  ([host port]
   (try
     (.close (Socket. host port))
     true
     (catch SocketException _
       false))))

(defn ex-str [e]
  (with-out-str (-> e Throwable->map (select-keys [:cause :data]) pprint/pprint)))

(defn print-err-exit [exit exit-code ex-or-str]
  (binding [*out* *err*]
    (print (if (ex-data ex-or-str)
             (ex-str ex-or-str)
             ex-or-str))
    (flush))
  (if (false? exit)
    {:exit-code exit-code}
    (System/exit exit-code)))

(defn write-port-file [filename port]
  (let [f (io/file filename)]
    (spit f (str port))
    (.deleteOnExit f)))

(defn read-port-file [filename]
  (when (fs/exists? filename)
    (parse-long (str/trim (slurp filename)))))

(defn active-port [filename]
  (when-let [port (read-port-file filename)]
    (when (port-taken? port)
      port)))

(defn port-or-random [port]
  (if (= 0 port)
    (with-open [socket (java.net.ServerSocket. 0)]
      (.getLocalPort socket))
    port))

(defn ensure-http-port-not-taken [port]
  (when (port-taken? port)
    (print-err-exit true 2 (ex-info "HTTP server port already taken" {:port port}))))

(defn ensure-repl-port-not-taken [port]
  (when (port-taken? port)
    (print-err-exit true 2 (ex-info "REPL server port already taken" {:port port}))))

(defn wait-for
  "Like babashka.wait/wait-for-port, but waits on a boolean fn to return true."
  [fn & {:keys [:default :timeout :pause] :as opts}]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (let [v (when (not (fn))
                (let [took (- (System/currentTimeMillis) t0)]
                  (if (and timeout (>= took timeout))
                    :wait/timed-out
                    :wait/try-again)))]
        (cond (identical? :wait/try-again v)
              (let [^long pause (or pause 100)]
                (sleep pause)
                (recur))
              (identical? :wait/timed-out v)
              default
              :else
              (assoc opts :took (- (System/currentTimeMillis) t0)))))))

(defn wait-for-port
  [port]
  (wait-for #(port-taken? port)))

(def invoker-global-dir
  (-> (io/resource "marker")
      (fs/path  "../../")
      fs/normalize
      str))

(defn process-setup
  {;; Metadata is workaround for how bb -x doesn't parse edn args like clj -X.
   :org.babashka/cli {:coerce {:sym :symbol, :cmd :edn}}}
  [{:keys [sym cmd]}]
  (let [{:keys [devtools stop start]} (:opts cmd)]
    (when devtools ((requiring-resolve devtools)))
    (when stop ((requiring-resolve stop)))
    (when start ((requiring-resolve start))))
  ((requiring-resolve sym) cmd))

(def invoker-coord
  {:local/root invoker-global-dir})

(defn exec-args [dialect sym cmd]
  (let [deps    {:paths ["src" "resources" "test"]
                 :deps  {'io.github.filipesilva/invoker invoker-coord}}
        alias-X (str "-X" (-> cmd :opts :aliases))]
    (case dialect
      :clj ["clojure" "-Sdeps" deps alias-X 'invoker.utils/process-setup :sym sym, :cmd cmd]
      :bb  ["bb"      "-Sdeps" deps "-x"    'invoker.utils/process-setup :sym sym, :cmd cmd])))

(defn exec
  ([sym cmd]
   (exec (-> cmd :opts :dialect) sym cmd))
  ([dialect sym m]
   (apply process/exec (exec-args dialect sym m))))

(defn connect
  [sym cmd]
  (let [[host port]    (-> cmd :opts :repl-connect (str/split #":"))
        cmd            (update cmd :opts assoc :exit false)
        ensure-invoker `(when-not (try
                                    (requiring-resolve 'invoker.cli/invoke)
                                    (catch Exception _# nil))
                          ((requiring-resolve 'clojure.repl.deps/add-lib)
                           'io.github.filipesilva/invoker
                           ~invoker-coord))
        expr           (format "%s ((requiring-resolve '%s) '%s)" ensure-invoker sym cmd)
        ret            (try (nrepl-client/eval-expr {:port port :expr expr})
                            (catch java.net.ConnectException _
                              (throw (ex-info "Cannot connect to nREPL server" {:host host, :port port})))
                            (catch java.io.EOFException _
                              (throw (ex-info "nREPL server exited" {:host host, :port port}))))]
    (System/exit (-> ret :vals last edn/read-string :exit-code (or 0)))))

(defn connect-or-exec [sym cmd]
  (if (-> cmd :opts :repl-connect)
    (connect sym cmd)
    (exec sym cmd)))

(def bb? (System/getProperty "babashka.version"))

(defmacro when-not-bb? [& body]
  (when-not bb?
    `(do ~@body)))
