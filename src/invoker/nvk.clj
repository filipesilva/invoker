(ns invoker.nvk
  "help for invoker goes here?"
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [bling.banner :as banner]
   [bling.core :as bling]
   [bling.fonts.ansi-shadow :as ansi-shadow]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [invoker.utils :as utils]))

(def base-spec
  [[:help            {:desc   "Show general usage help"
                      :coerce :boolean}]
   [:help-options    {:desc   "Show detailed options help"
                      :coerce :boolean}]
   ;; TODO: links to gh commit/release
   [:version         {:desc   "Show version"
                      :coerce :boolean}]
   [:config          {:desc    "Invoker defaults config file"
                      :coerce  :string
                      :alias   :c
                      :default "nvk.edn"}]
   [:ns-default      {:desc    "Default namespace for var resolution"
                      :coerce  :symbol
                      :alias   :nd
                      :default 'invoker.cli}]
   [:ns-aliases      {:desc    "Map of alias to namespace for var resolution"
                      :coerce  :edn
                      :alias   :na
                      :default {}}]
   [:dialect         {:desc    "Clojure (clj) or Babashka (bb), defaults to clj if there's a deps.edn"
                      :coerce  :keyword
                      :alias   :d}]
   ;; TODO
   [:aliases         {:desc   "Aliases to call Clojure with, does nothing with Babashka"
                      :coerce :string
                      :alias  :a}]
   [:devtools        {:desc    "Developer tools fn to call on nREPL server creation"
                      :coerce  :symbol
                      :alias   :dt
                      :default 'invoker.utils/dev-tools}]
   [:setup           {:desc    "Setup fn to call on nREPL server creation"
                      :coerce  :symbol
                      :alias   :s}]
   [:http-port       {:desc    "Port for HTTP server"
                      :coerce  :int
                      :alias   :hp
                      :default 80}]
   [:http-handler    {:desc    "Ring handler fn for HTTP server"
                      :coerce  :symbol
                      :alias   :hh
                      :default 'invoker.http/handler}]
   [:repl-port       {:desc    "Port for nREPL server creation"
                      :coerce  :int
                      :alias   :rp
                      :default 2525}]
   [:repl-connect    {:desc    "nREPL server address to connect on, defaults to content of .nrepl-port file if present and port is taken"
                      :coerce  :string
                      :alias   :rc}]
   [:content-type    {:desc    "MIME type for body (last arg or piped input) on CLI content negotiation"
                      :coerce  :string
                      :alias   :ct}]
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
   ;; TODO
   [:print-meta      {:desc    "Print return metadata"
                      :coerce  :boolean
                      :alias   :pm
                      :default false}]
   ;; TODO
   [:ex-trace        {:desc    "Include stack trace on exception"
                      :coerce  :boolean
                      :alias   :et
                      :default false}]
   ;; TODO
   [:verbose         {:desc    "Print extra debug logging"
                      :coerce  :boolean
                      :alias   :v
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

   "Given " [:gray "src/app.clj"] ":\n"
   [:gray
    "  (ns app)

  (defn my-fn
    \"My doc\"
    [x y & {:as opts}]
    [x y opts])\n\n"]

   "Main " [:magenta "commands"] ":\n"
   [:blue "  nvk app/my-fn 1 2"] "          Invoke my-fn via CLI\n"
   [:blue "  nvk app my-fn 1 2\n"]
   [:blue "  nvk app my-fn 1 2 :a 1\n"]
   [:blue "  nvk app my-fn 1 2 --a 1\n"]
   [:blue "  nvk app my-fn 1 2 --a=1\n\n"]

   [:blue "  nvk http"] "                   Start HTTP server and invoke my-fn via curl\n"
   [:gray "  curl localhost/app/my-fn/1/2\n"]
   [:gray "  curl localhost/app/my-fn/1/2?a=1\n"]
   [:gray "  curl localhost/app/my-fn/1/2 -d a=1\n\n"]

   [:blue "  nvk repl"] "                   Start nREPL server and invoke my-fn via code\n"
   [:gray "  (require 'app) (app/my-fn 1 2 :a 1)\n\n"]

   [:blue "  nvk test"] "                   Run tests in test/**.clj\n\n"

   "Helper " [:magenta "commands"] " connect to REPL server if available:\n"
   [:blue "  nvk reload"] "                 Reload changed namespaces\n"
   [:blue "  nvk reload :all"] "            Reload all namespaces\n"
   [:blue "  nvk dir app"] "                List public vars in ns\n"
   [:blue "  nvk doc app/my-fn"] "          Print var doc\n"
   [:blue "  nvk source app/my-fn"] "       Source code for var\n"
   [:blue "  nvk find-doc My doc"] "        Find docs containing text\n"
   [:blue "  nvk apropos my-f"] "           Find vars containing text\n"
   [:blue "  nvk add-lib babashka/fs"] "    Add dependency by name, create deps.edn if needed\n"
   [:blue "  nvk sync-deps"] "              Sync dependencies to process\n"
   [:blue "  nvk devtools"] "               Call devtools var\n"
   [:blue "  nvk setup"] "                  Call setup var\n"
   [:blue "  nvk clojuredocs q"] "          Search ClojureDocs for q\n"
   [:blue "  nvk exit 1"] "                 Exit the process with exit-code or 0\n\n"

   [:purple "Options"] ", custom defaults can be set in " [:purple "nvk.edn"] ":\n"
   (cli/format-opts {:spec (into {} spec), :order [:help :help-options :version :config
                                                   :http-port :repl-port :repl-connect
                                                   :ns-default :ns-aliases]})))

(defn help-options [spec]
  (bling/print-bling
   [:purple "Options"] ", custom defaults can be set in " [:purple "nvk.edn"] ":\n"
   (cli/format-opts {:spec spec})

   "\n\nExample defaults in " [:purple "nvk.edn"] ":\n"
   [:gray
    "{:http-port 8080
 :repl-port 6060}"]))

(defn command [spec {:as cmd, :keys [opts args]}]
  (cond
    (:help-options opts)
    (help-options spec)

    (:version opts)
    (println "version!")

    (or (empty? args) (:help opts))
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
                          :repl-connect (when (fs/exists? ".nrepl-port")
                                          (let [port (->> ".nrepl-port" slurp str/trim parse-double int)]
                                            (when (utils/port-taken? port)
                                              (str "localhost:" port))))}
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
;; - use claude for some of the nows, otherwise I won't move forward quickly
;; - use help from sym metadata? or list docstring from ns-default?
;; - `nvk --help reload` should be same as nvk doc invoker.cli/reload
;; - default 0 for random nrepl port
;; - just nvk should tell you about the repl/http server being up or not
;; - https://clojure.org/reference/deps_edn recommends using aliases for config
;;   - so :invoker alias instead of nvk.edn?
;;   - what about bb?
;; - make add-lib actually save the lib in deps?
;; - default unhandled exception handler?

;; TODO: maybe
;; - http content via suffix! .html .edn .json
;; - http redirect
;;   - having a format fn that lets you customize responses for return format doesn't seem so bad now
;; - should default http calls gives http or edn?
;;   - on zero config, what do I want to happen?
;;     - I want a user to be able to put down some hiccup and not think about it
;;     - I want API-like calls to work fine
;;     - I want a user to be able to force a fn return to be either hiccup or html
;;   - figure out how html vs edn works for defaults
;;     - http/cli/repl should default to html/text/edn?
;;     - should fns say what kind of content they are returning?
;;       - like :invoker/content-type metadata
;;       - then render would still be able to change it
;;   - should I just add :content-type metadata on the fn, to talk about the return?
;;     - but for html, we're returning edn hiccup, not html
;;     - I guess I could just return string for real content-type, and xform with hiccup etc if not string
;;     - would even be cool for non-fns... some static stuff
;;     - fn author knows the content-type
;;       - setting content-type signals what render should be used if available
;;       - can still force application/edn, which is the base content-type for clojure, render just converts that
;;       - this is like the normal http content type
;;         - everything is actually just strings in a req/resp
;;         - then content-type is a hint to convert it
;;         - but http starts with everything string, clojure starts with everything edn
;;   - I think key is to figure out what --accept means
;;     - --accept */* on http requests is a bit different than on CLI, and on REPL
;; - redirects?
;;   - there's something about redirects in https://github.com/ring-clojure/ring-defaults/blob/master/src/ring/middleware/defaults.clj
;;   - maybe it just works tho
;;   - can go in http render
;; - figure out the how-to for making your own cli apps via invoker
;;   - can I jam help/cmds dispatch/defaults edn/whatever in there? like invoker itself
;; - fn routing? for http verbs, websocket, sse, others
;;   - you'd still map a route/cli cmd to a fn, but then that fn could dispatch to something else under some conditions
;;   - does this apply not only to http? unsure, doesn't look like it tbh
;;   - can always point to a multifn and dispatch based on *req*, I think
;; - put only cli load time deps here?
;;   - remove utils, maybe move the fns here, or make a separate ns
;;   - measured naively using fish time, total/usr/sys:
;;     - with utils:   124/86/30
;;     - without utils: 87/50/27
;;     - so I guess the difference is usr time
;; - repl helper cmds on http too? headers, or something else, or leave for openapi?
;; - test that utils/connect exists with 1 on err returns, and prints returns
;; - add-deps isn't done yet
;; - drop the :invoker/etc ns in the metadata kw, cleaner
;; - implement --verbose logging
;; - --version
;; - support port 0 (random) for http and repl port, write .http-port to disk
;; - some way to only make some fns public/callable
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
;; - some way to list all public fns, like nvk ns-publics
;;   - :invoke true
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
;; - how to set cli return code?
;;   - similar to http status code, via ret metadata I guess
;;   - can't do meta on nil/ints/str tho, which really sucks
;;   - can't just use http status like 400, max exit code is 256
;; - figure out something for default 404 and 500
;; - consider some tui lib
;;   - node https://github.com/vadimdemedes/ink
;;   - python https://github.com/Textualize/rich
;; - would be nice to have the http/repl options really on the fn, but still be easy to set defaults in config
;;   - so that you can just do nvk http and it just works
;;   - right now this happens via nvk flags, but that means those aren't options proper
;; - fn that takes symbol, and returns url for symbol, to use in htmx url generation
;; - would be really nice if nvk repl worked like the others
;;   - it's specialcased to always launch a clj exec

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
;; - auth
;;   - --authorization sym, works on cli etc too?
;;     - goes on default middleware if present
;;   - or maybe --authorization works more like the auth header on http calls
;;     - contains auth info about the current call, and the fn can get it somehow
;;       - but then cli needs to access that resolved auth info, resolve to a user or something
;;     - then you have a separate --http-auth sym option
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
;;       - https://babashka.org/scittle/
;;     - cool squint reagent https://github.com/borkdude/reagami
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
;; - :parse ?
;;   - like :render, for custom body content-type processing
;;   - xforms are [parse, render]
;; - datastar?
