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
   ;; TODO: links to gh commit/release
   [:version         {:desc   "Show version"
                      :coerce :boolean}]
   [:config          {:desc    "Invoker defaults config file"
                      :coerce  :string
                      :alias   :c
                      :default "nvk.edn"}]
   [:content-type    {:desc   "MIME type for body (last arg or piped input) on CLI content negotiation"
                      :coerce :string
                      :alias  :ct}]
   [:accept          {:desc    "MIME types accepted on CLI content negotiation, use with :invoker/render metadata"
                      :coerce  :string
                      :alias   :ac
                      :default "application/edn"}]
   [:parse           {:desc    "Map of MIME type regex to edn parsing fn"
                      :coerce  :symbol
                      :default 'invoker.utils/parse}]
   [:render          {:desc    "Map of MIME type regex to edn rendering fn"
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
   [:start           {:desc    "Start fn to call on process setup or nvk restart"
                      :coerce  :symbol}]
   [:stop            {:desc    "Stop fn to call on process setup or nvk restart"
                      :coerce  :symbol}]
   [:ns-default      {:desc    "Default namespace for var resolution"
                      :coerce  :symbol
                      :alias   :nd
                      :default 'invoker.cli}]
   [:ns-aliases      {:desc    "Map of alias to namespace for var resolution"
                      :coerce  :symbol
                      :alias   :na}]
   [:http-all        {:desc    "Expose vars without :invoker/http in the HTTP server"
                      :coerce  :int
                      :alias   :ha
                      :default false}]
   [:http-port       {:desc    "Port for HTTP server"
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
   "and repl/http create one if needed.\n\n"

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
   [:blue "  nvk app my-fn 1 2 --a=3\n\n"]

   [:blue "  nvk http"] "                   Start HTTP server and invoke my-fn via curl\n"
   [:gray "  curl localhost/app/my-fn/1/2\n"]
   [:gray "  curl localhost/app/my-fn/1/2?a=3\n"]
   [:gray "  curl localhost/app/my-fn/1/2 -d a=3\n\n"]

   [:blue "  nvk repl"] "                   Start nREPL server and invoke my-fn via code\n"
   [:gray "  (require 'app) (app/my-fn 1 2 :a 3)\n\n"]

   [:blue "  nvk test"] "                   Run tests in test/**/*.clj, reloading changed files\n"
   [:blue "  nvk test app-test\n"]
   [:blue "  nvk test app-test/my-fn-test\n\n"]

   "Helper " [:magenta "commands"] ":\n"
   [:blue "  nvk reload"] "                 Reload changed namespaces\n"
   [:blue "  nvk reload :all"] "            Reload all namespaces\n"
   [:blue "  nvk dir app"] "                List public vars in ns, or in ns-default\n"
   [:blue "  nvk source app/my-fn"] "       Source code for var\n"
   [:blue "  nvk doc app/my-fn"] "          Print var docstring\n"
   [:blue "  nvk find-doc My doc"] "        Find docs containing text\n"
   [:blue "  nvk apropos my-f"] "           Find vars containing text\n"
   [:blue "  nvk add-lib babashka/fs"] "    Add dependency by name, creates deps.edn if needed\n"
   [:blue "  nvk sync-deps"] "              Sync process to deps.edn\n"
   [:blue "  nvk devtools"] "               Call devtools var\n"
   [:blue "  nvk restart"] "                Call stop then start vars\n"
   [:blue "  nvk clojuredocs q"] "          Search ClojureDocs for q\n"
   [:blue "  nvk exit 1"] "                 Exit the process with exit-code or 0\n\n"

   [:purple "Options"] ":\n"
   (cli/format-opts {:spec spec})

   "\n\nYou can set custom defaults for options in " [:purple "nvk.edn"] ":\n"
   [:gray
    "{:http-port 8080
 :aliases   \":dev\"}"]))

(defn command [spec {:as cmd, :keys [opts args]}]
  (cond
    (:version opts)
    (println "version!")

    (empty? args)
    (help spec)

    (and (= "repl" (first args))
         (= 'invoker.cli (:ns-default opts)))
    (utils/exec :clj 'invoker.cli/invoke cmd)

    :else
    (utils/connect-or-exec 'invoker.cli/invoke cmd)))

(defn update-default [defaults [k m]]
  (if-some [default (k defaults)]
    [k (assoc m :default default)]
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

;; TODO: now
;; - --version
;; - invoker.utils/invoker-coord should use a public version if available
;; - http content via suffix! .html .edn .json
;;   - show in http example
;;   - turns both content-type and accept into that type
;;   - uses new symbol, ext, that maps extensions to mime-types
;;   - maybe --ext=html should work too for cli invoke?
;; - how to set cli return code?
;;   - similar to http status code, via ret metadata I guess
;;   - can't do meta on nil/ints/str tho, which really sucks
;;   - can't just use http status like 400, max exit code is 256
;; - http redirect
;;   - having a format fn that lets you customize responses for return format doesn't seem so bad now
;;   - there's something about redirects in https://github.com/ring-clojure/ring-defaults/blob/master/src/ring/middleware/defaults.clj
;;   - maybe it just works tho
;;   - can go in http render

;; TODO: maybe
;; - if I add dpm as a dependency on nvk, can I just call dpm/up as setup?
;; - figure out the how-to for making your own cli apps via invoker
;;   - can I jam help/cmds dispatch/defaults edn/whatever in there? like invoker itself
;; - fn routing? for http verbs, websocket, sse, others
;;   - you'd still map a route/cli cmd to a fn, but then that fn could dispatch to something else under some conditions
;;   - does this apply not only to http? unsure, doesn't look like it tbh
;;   - can always point to a multifn and dispatch based on *req*, I think
;; - repl helper cmds on http too? headers, or something else, or leave for openapi?
;; - implement --verbose logging
;; - use cases to try
;;   - make intel
;;     - prototype then make global cli for it
;;     - analyse fn deps and reverse deps
;;     - focus on impact of changes
;;     - tests that need to be rerun
;;   - make mdb
;;     - markdown db
;;     - markdown files with yml metadata that are loaded into a derived data datalog db
;;     - make issue tracker like https://github.com/steveyegge/beads
;;     - use modified-since https://blog.michielborkent.nl/speeding-up-builds-fs-modified-since.html
;;   - rails blog demo
;;     - might need verb mapping
;;     - deff need status codes and all that
;;       - rails uses :ok :created :unprocessable_entity :see_other
;;     - custom 404 500 and other static pages
;;       - maybe middleware, maybe cli opts
;;       - htmx doesn't swap by default on 400, only on 200, which is interesting...
;;         - makes for a more straighforward error render loop, almost a preview
;;     - use dpm, some simple schema management
;;     - how does rails notice thingy work?
;;     - if I want normal urls at all, I might need the interleaving stuff
;;       - /posts/1/comments/2 would need to map to (app.comments/get 1 2)
;;     - multiple arities might be a good pattern for stuff like edit
;;       - (foo 1) is html edit get, you don't call this to actually update the thing, just to get UI
;;       - (foo 1 {:a 1}) is the actual object update, can return edn or html
;;       - need to get the errors somewhere, might need a maybe arity 3, or something on map
;;     - just call a test fn via cli, using nrepl, and know it failed
;; - cache control
;;   - compelling to generalize
;;   - http already has this, and if I make a client, it should use it
;;   - for cli it would be very cool to create and use a cache
;;     - same command with no changes returns immediately
;;   - in the repl, some sort of (with-cache ,,,) would be cool
;;     - iirc someone mentioned that rails does some caching stuff auto for models
;;   - would probably work on the dispatch for cli/repl
;;   - set control stuff via metadata on the fn return
;;   - cache-control and etag seem to be the important headers
;;     - https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cache-Control
;;     - https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/ETag
;;   - fns could have etag fn in metadata, call it before calling fn
;;     - for pure fns this is identity over args, like memo
;; - fn that takes symbol, and returns url for symbol, to use in htmx url generation
;; - exec-args puts :paths in, but shouldn't if there's already any in the deps.edn/bb.edn

;; TODO: not now
;; - move this stuff to a roadmap or readme?
;; - openapi, mcp, ATproto
;;   - probably just handlers you can put in your root ns
;;   - similar in that they need to provide a listing, description, instructions for callers, have default endpoint
;;   - /openapi.json for openapi, /mcp for mcp
;;     - https://modelcontextprotocol.io/specification/2025-03-26/basic/transports#streamable-http
;; - sse
;;   - https://github.com/http-kit/http-kit/issues/578
;;   - I think it doesn't intersect with :invoker/render
;;     - messages have specific format
;;   - would be super cool to be shown as ergonomically working between invoker processes
;;   - whats a stream in cli?
;;     - stream of print to stdout?
;;     - and everything else to stderr?
;;       - that would mean that the usual println stuff should go to stderr by default
;;       - bit of a parallel between http 200 (stdout) and http 400 (stderr)
;;     - channel, backpressure, error semantics are key here
;;       - cli has channel with backpressure via pipes and pagers, errors via stderr
;;       - repl (clj) has channel backpressure semantics for core async channels and lazy seqs, errors are just throw?
;;       - http sse doesn't have great backpressure, but does have channel semantics, and err
;; - scheduler
;;   - whats the difference between scheduling and polling? as far as user intent
;; - env
;;   - rails is a good example https://guides.rubyonrails.org/configuring.html
;;   - would be very nice to support different envs on the same running process
;;   - https://github.com/juxt/aero
;;     - would be a nice default, just a resources/config.edn, load it properly
;; - db
;;   - some custom db setup and stuff?
;;     - setup config
;;   - not necessarily a datomic thing, most stateful apps need some sort of db
;;   - make multi-db easy, like multi tenants
;;   - good for schema https://github.com/qtrfeast/conformity
;; - auth
;;   - --authorization sym, works on cli etc too?
;;     - goes on default middleware if present
;;   - or maybe --authorization works more like the auth header on http calls
;;     - contains auth info about the current call, and the fn can get it somehow
;;       - but then cli needs to access that resolved auth info, resolve to a user or something
;;     - then you have a separate --http-auth sym option
;;   - could use the contents of :invoker/http fn metadata
;;     - e.g. {:invoker/http :auth} or something
;; - socket repl
;; - http verbs
;;   - atm there's no verbs really, anything works
;;   - verbs could auto-add a fn name, like in braid
;;     - but then the var resolution would need to resolve namespace only
;;     - e.g.
;;       - GET    /invoker/examples/1 -> (invoker.examples/pull 1)
;;       - GET    /invoker/examples   -> (invoker.examples/query)
;;       - POST   /invoker/examples   -> (invoker.examples/create opts)
;;       - PATCH  /invoker/examples   -> (invoker.examples/update! opts)
;;       - DELETE /invoker/examples   -> (invoker.examples/delete! opts)
;;     - how to distinguish between query and fetch? maybe just leave one of them?
;;     - maybe PUT remains without a mapping
;; - connect to remote repl
;;   - reverse ssh or something
;; - fetch
;;   - fetch (http) command, and expose the lib, uses xformers to get edn, takes auth header too
;;   - nvk -ct application/edn -ac application/edn fetch localhost invoker.examples/args 1 2 {:a 1}
;;   - (invoker.http/fetch {:content-type "application/edn" :accept "application/edn"} "localhost" 'invoker.examples/args 1 2 {:a 1})
;; - maybe --commands?
;;   - need to understand the usecases and how just calling the cli version of the fn isn't enough
;; - language server
;;   - would be super cool to be editor tooling super easily
;;   - nice way to hook intel stuff into editor
;; - browser experience
;;   - maybe cljs stuff, or squint or whatever
;;     - cool scittle example https://clojurecivitas.github.io/scittle/presentations/browser_native_slides.html
;;       - just write some cljs that runs on browser
;;       - some kind of hiccup and reagent plugin
;;       - https://babashka.org/scittle/ shows script tag usage
;;         - the "source from file" usage is pretty tempting, or some equivalent with real cljs
;;     - cool squint reagent https://github.com/borkdude/reagami
;;       - https://github.com/rads/squint-browser
;;     - https://clojurescript.org/news/2025-11-24-release#_refer_global_and_require_global
;;       - the bit about using only global deps require, with hypermedia stuff
;;       - so I could just compile cljs to use it as a global script
;;       - https://swannodette.github.io/2025/11/24/aimless/
;;         - both a good example of adding cljs, and a usecase for invoker
;;       - official docs https://clojurescript.org/guides/quick-start
;;     - could just integrate with shadow-cljs?
;;   - live reload on changes during dev
;;   - helpful debug stuff like rails erubi, that shows the templates where the html came from
;;   - easy way to connect html element handlers to the code
;;     - maybe a HTML handler interface to clojure code
;;     - maybe just reuse the http url interface and make use of native post on form submit
;;       - with this, could even intercept on js runtime and handle on FE
;;   - on FE, would be cool to return hiccup edn from BE calls, and FE renders it
;;   - https://github.com/PEZ/browser-jack-in
;;   - https://github.com/realgenekim/browser-reload
;; - :parse ?
;;   - like :render, for custom body content-type processing
;;   - xforms are [parse, render]
;; - datastar?
