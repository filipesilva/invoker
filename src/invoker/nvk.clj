(ns invoker.nvk
  "help for invoker goes here?"
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [bling.banner :as banner]
   [bling.core :as bling]
   [bling.fonts.ansi-shadow :as ansi-shadow]
   [clojure.edn :as edn]
   [invoker.utils :as utils]))

(def base-spec
  [[:help            {:desc   "Show doc for var"
                      :coerce :boolean}]
   [:version         {:desc   "Show version"
                      :coerce :boolean}]
   [:skill           {:desc   "Print README.md with Claude SKILL.md metadata"
                      :coerce :boolean}]
   [:status          {:desc   "Show nREPL and HTTP server status"
                      :coerce :boolean}]
   [:config          {:desc    "Invoker defaults config file"
                      :coerce  :string
                      :alias   :c
                      :default "nvk.edn"}]
   [:ext             {:desc    "Extension shorthand (.edn/.json/.yaml/.html/.txt) for content-type/accept MIME types"
                      :coerce  :string
                      :alias   :e}]
   [:content-type    {:desc   "MIME type for body (last arg or piped input) on CLI content negotiation"
                      :coerce :string
                      :alias  :ct}]
   [:accept          {:desc    "MIME types accepted on CLI content negotiation, use with :invoker/render metadata"
                      :coerce  :string
                      :alias   :ac
                      :default "application/edn"}]
   [:extensions      {:desc    "Map of extension to MIME type"
                      :coerce  :symbol
                      :default 'invoker.utils/extensions}]
   [:parse           {:desc    "Map of MIME type to parsing fn"
                      :coerce  :symbol
                      :default 'invoker.utils/parse}]
   [:render          {:desc    "Map of MIME type to rendering fn"
                      :coerce  :symbol
                      :default 'invoker.utils/render}]
   [:dialect         {:desc   "Clojure (clj) or Babashka (bb), defaults to clj if there's a deps.edn"
                      :coerce :keyword
                      :alias  :d}]
   [:devtools        {:desc    "Developer tools fn to call on process setup or nvk devtools"
                      :coerce  :symbol
                      :default 'invoker.utils/devtools}]
   [:reload          {:desc    "Reload changed files before invoking fn via CLI"
                      :coerce  :boolean
                      :alias   :r}]
   [:start           {:desc    "Start fn to call on nREPL server start  or nvk restart"
                      :coerce  :symbol}]
   [:stop            {:desc    "Stop fn to call on nREPL server start or nvk restart"
                      :coerce  :symbol}]
   [:ns-default      {:desc    "Default namespace for var resolution"
                      :coerce  :symbol
                      :alias   :nd
                      :default 'invoker.cli}]
   [:ns-aliases      {:desc    "Map of alias to namespace for var resolution"
                      :coerce  :symbol
                      :alias   :na}]
   [:http-all        {:desc    "Expose vars without :invoker/http in the HTTP server"
                      :coerce  :boolean
                      :alias   :ha
                      :default false}]
   [:http-port       {:desc    "Port for HTTP server, written to .http-port"
                      :coerce  :int
                      :alias   :hp
                      :default 80}]
   [:http-handler    {:desc    "Ring handler fn for HTTP server"
                      :coerce  :symbol
                      :alias   :hh
                      :default 'invoker.http/handler}]
   [:repl-port       {:desc    "Port for nREPL server creation, 0 for random"
                      :coerce  :int
                      :alias   :rp
                      :default 0}]
   [:repl-connect    {:desc   "nREPL server address to connect on, defaults to content of .nrepl-port file if present and port is taken"
                      :coerce :string
                      :alias  :rc}]
   [:repl-git-remote {:desc   "Git remote name to use for nREPL connection"
                      :coerce :string
                      :alias  :rgr}]
   [:aliases         {:desc   "Aliases to call Clojure with, does nothing with Babashka"
                      :coerce :string
                      :alias  :a}]
   [:ex-trace        {:desc    "Include stack trace on exception"
                      :coerce  :boolean
                      :alias   :et
                      :default false}]])

(defn help [spec]
  (println (banner/banner
            {:font               ansi-shadow/ansi-shadow
             :text               "invoker"
             :gradient-direction :to-top
             :gradient-colors    [:warm :cool]}))
  (bling/print-bling
   "Zero config CLI, HTTP, and REPL interface for Clojure.\n\n"

   "                    " [:blue "\\|/"] [:yellow "_(ツ)_"] [:blue "\\|/"] "\n\n"

   "Usage: " [:blue "nvk "] [:purple "<options>* "] [:magenta "<command> "] [:gray "<args>*"] "\n\n"

   "Commands run in Clojure if there's a deps.edn, otherwise in Babashka.\n"
   "Commands will automatically connect to an existing nREPL if available,\n"
   "and the repl/http commands create one if needed.\n\n"

   "Servers: .nrepl-port " [:blue (or (utils/active-port ".nrepl-port") "(missing)")]
   ", .http-port " [:blue (or (utils/active-port ".http-port") "(missing)")] "\n\n"

   "Given " [:gray "src/app.clj"] ":\n"
   [:gray
    "  (ns app)

  (defn my-fn
    \"My doc\"
    {:invoker/http true}
    [x y & {:as opts}]
    [x y opts])\n\n"]

   "Main " [:magenta "commands"] ":\n"
   [:blue "  nvk app/my-fn 1 2"] "          Invoke my-fn via CLI\n"
   [:blue "  nvk app my-fn 1 2\n"]
   [:blue "  nvk app my-fn 1 2 :a 3\n"]
   [:blue "  nvk app my-fn 1 2 --a 3\n"]
   [:blue "  nvk app my-fn 1 2 --a=3\n"]
   [:blue "  nvk --accept application/json app my-fn 1 2\n"]
   [:blue "  nvk --content-type application/json app my-fn 1 2 '{\"a\":3}'\n"]
   [:blue "  nvk --ext .json app my-fn 1 2 '{\"a\":3}'\n"]
   [:blue "  echo '{\"a\":3}' | nvk --ext .json app my-fn 1 2\n\n"]

   [:blue "  nvk http"] "                   Start HTTP server and invoke my-fn via curl\n"
   [:gray "  curl localhost/app/my-fn/1/2\n"]
   [:gray "  curl localhost/app/my-fn/1/2?a=3\n"]
   [:gray "  curl localhost/app/my-fn/1/2 -d a=3\n"]
   [:gray "  curl localhost/app/my-fn/1/2 -H \"Accept: application/json\"\n"]
   [:gray "  curl localhost/app/my-fn/1/2 -d '{\"a\": 3}' -H \"Content-Type: application/json\"\n"]
   [:gray "  curl localhost/app/my-fn/1/2.json -d '{\"a\": 3}'\n\n"]

   [:blue "  nvk repl"] "                   Start nREPL server and invoke my-fn via code\n"
   [:gray "  (require 'app) (app/my-fn 1 2 :a 3)\n\n"]

   [:blue "  nvk test"] "                   Run tests in test/**/*.clj, reloading changed files\n"
   [:blue "  nvk test app-test\n"]
   [:blue "  nvk test app-test/my-fn-test\n\n"]

   "Helper " [:magenta "commands"] ":\n"
   [:blue "  nvk reload"] "                 Reload changed namespaces\n"
   [:blue "  nvk reload :all"] "            Reload all namespaces\n"
   [:blue "  nvk routes"] "                 List routes for vars with :invoker/http metadata.\n"
   [:blue "  nvk dir app"] "                List public vars in ns, or in ns-default\n"
   [:blue "  nvk source app/my-fn"] "       Source code for var\n"
   [:blue "  nvk doc app/my-fn"] "          Print var docstring\n"
   [:blue "  nvk find-doc My doc"] "        Find docs containing text\n"
   [:blue "  nvk apropos my-f"] "           Find vars containing text\n"
   [:blue "  nvk add-lib babashka/fs"] "    Add dependency by name, creates deps.edn if needed (Clojure only)\n"
   [:blue "  nvk sync-deps"] "              Sync process to deps.edn (Clojure only)\n"
   [:blue "  nvk devtools"] "               Call devtools var\n"
   [:blue "  nvk restart"] "                Call stop then start vars\n"
   [:blue "  nvk clojuredocs q"] "          Search ClojureDocs for q\n"
   [:blue "  nvk exit 1"] "                 Exit the process with exit-code or 0\n\n"

   [:purple "Options"] ":\n"
   (cli/format-opts {:spec spec})

   "\n\nYou can set custom defaults for options in " [:purple "nvk.edn"] ":\n"
   [:gray
    "{:http-port 8080
 :aliases   \":dev\"}"] "\n\n"

   "Github: " [:blue "https://github.com/filipesilva/invoker"] "\n"
   "Local README: "[:blue (str utils/invoker-global-dir "/README.md")] "\n"
   "Version: " (str (utils/invoker-coord)) "\n"))

(defn skill []
  (println "---
name: nvk
description: How to use nvk (Invoker) as a CLI, HTTP, and REPL interface for Clojure.
---\n")
  (println (slurp (str utils/invoker-global-dir "/README.md"))))

(defn server-status [port-file probe up-str]
  (let [port (utils/read-port-file port-file)]
    (cond
      (nil? port)
      [[:gray "down"] (str ", no " port-file " file")]

      (not (utils/port-taken? port))
      [[:red "down"] (str ", " port-file " has port " port " but nothing is listening on it")]

      :else
      (if-let [info (probe port)]
        [[:green "up"] (str " " (up-str port info))]
        [[:red "not verified"] (str ", port " port " is listening but did not answer a probe")]))))

(defn status []
  (let [nrepl (future
                (server-status ".nrepl-port" utils/nrepl-probe
                               (fn [port {:keys [dialect pid]}]
                                 (str "at localhost:" port ", " (name dialect) ", pid " pid))))
        http  (future
                (server-status ".http-port" utils/http-probe
                               (fn [port _]
                                 (str "at " (utils/http-url port)))))]
    (apply bling/print-bling "nREPL " @nrepl)
    (apply bling/print-bling "HTTP  " @http)))

(defn maybe-force-clj-exec [{:as cmd, :keys [opts args]}]
  (if (= 'invoker.cli (:ns-default opts))
    (cond-> cmd
      ;; repl is special, it's always exec, then connects itself
      (#{"repl"} (first args))
      (assoc-in [:opts :force-exec] true)

      ;; these are clj-only
      (#{"repl" "add-lib" "sync-deps"} (first args))
      (assoc-in [:opts :dialect] :clj)

      ;; these should still work if called from bb, they just won't connect
      (and (#{"add-lib" "sync-deps"} (first args))
           (not= :clj (-> cmd :opts :dialect)))
      (assoc-in [:opts :repl-connect] nil))
    cmd))

(defn command [spec {:as cmd, :keys [opts args]}]
  (cond
    (:version opts)
    (println (utils/invoker-coord))

    (:skill opts)
    (skill)

    (:status opts)
    (status)

    (empty? args)
    (help spec)

    :else
    (utils/connect-or-exec 'invoker.cli/invoke (maybe-force-clj-exec cmd))))

(defn update-default [defaults [k m]]
  (if (contains? defaults k)
    [k (assoc m :default (k defaults))]
    [k m]))

(defn spec-with-defaults [base-spec args]
  (let [config-path      (-> [{:cmds [] :fn identity :spec base-spec}] (cli/dispatch args) :opts :config)
        _                (when (and (not= config-path "nvk.edn")
                                    (not (fs/exists? config-path)))
                           (utils/print-err-exit true 2 (ex-info "Config path does not exist"
                                                                 {:config-path config-path})))
        dynamic-defaults {:dialect      (if (fs/exists? "deps.edn") :clj :bb)
                          :repl-connect (when-let [port (utils/active-port ".nrepl-port")]
                                          (str "localhost:" port))}
        config-defaults  (utils/catch-nil (-> config-path slurp edn/read-string))]
    (->> base-spec
         (mapv (partial update-default dynamic-defaults))
         (mapv (partial update-default config-defaults)))))

(defn -main
  [& args]
  (try
    (let [piped?   (and (nil? (System/console))
                        (pos? (.available System/in)))
          args'    (if piped?
                     (concat args [(slurp *in*)])
                     args)
          spec     (spec-with-defaults base-spec args')
          ;; use dispatch commands to consume spec opts separately from symbol args
          commands [{:cmds [] :fn (partial command spec) :spec spec}]]
      (cli/dispatch commands args'))
    (catch ^:sci/error Exception e
      (if (utils/ex-info-msgs (ex-message e))
        (utils/print-err-exit true 2 e)
        (throw e)))))
