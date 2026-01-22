# Invoker

Zero config CLI, HTTP, and REPL interface for Clojure.

Invoked vars run in [Clojure](https://clojure.org) if there's a `deps.edn`, otherwise in [Babashka](https://github.com/babashka/babashka).

Commands will automatically connect to an existing [nREPL server](https://nrepl.org/nrepl/index.html) if available using `.nrepl-port`. The `nvk http` and `nvk repl` commands start a nREPL server that can be connected to.

Invoker aims to make the following usecases easy:
- making a simple webapp from scratch, possibly with Datomic
- running functions and inspecting atoms inside an existing process
- running targetted tests with reloaded code
- allowing agents to interact with your clojure process
- adding dependencies and reloading code without restarting the process

The invoker:

![It's magic. I ain't gotta explain shit.](its-magic.jpg)


## CLI

Given `src/app.clj`:

``` clojure
(ns app)

(defn my-fn
  "My doc"
  {:invoker/http true}
  [x y & {:as opts}]
  [x y opts])
```

You can invoke it with the `nvk` CLI, using the fully qualified name or separated by spaces, and passing `opts` using keywords or flags:

``` sh
$ nvk app/my-fn 1 2
[1 2 nil]
$ nvk app my-fn 1 2
[1 2 nil]
$ nvk app my-fn 1 2 :a 3
[1 2 {:a 3}]
$ nvk app my-fn 1 2 --a 3
[1 2 {:a 3}]
$ nvk app my-fn 1 2 --a=3
[1 2 {:a 3}]
```

You must provide at least as the minimum number of arguments that the function takes.
You can invoke a value, and atom values will be dereferenced.

Exceptions will return the exception map with no stack trace unless you use the `--ex-trace` option.
The exit code for exceptions will be 1, and you can customize it with the `:exit` key: `(throw (ex-info "my error" {:exit 3}))`.


## HTTP

You can also serve it with `nvk http`, then invoke it with `curl` or by opening the address on your browser:

``` sh
$ nvk http
Started nREPL server at localhost:51548
Started HTTP server at http://localhost
```

``` sh
$ curl localhost/app/my-fn/1/2
[1 2 nil]
$ curl localhost/app/my-fn/1/2?a=3
[1 2 {:a 3}]
$ curl localhost/app/my-fn/1/2 -d a=3
[1 2 {:a "3"}]
```

Only vars with the `{:invoker/http true}` metadata will be served, unless you use `nvk --http-all http`.

Successful invocations return status 200, exceptions 400, internal server errors 500.
The HTTP method will be ignored. 
You can redirect on 200 to another var or string path using metadata: `{:invoker/http {:redirect #'another-fn}}`.


## REPL

You can also start a [rebel-readline](https://github.com/bhauman/rebel-readline) nREPL client with `nvk repl`:

``` sh
$ nvk repl
Started nREPL server at localhost:51534
Connecting to nREPL server at localhost:51534
Quit REPL with ctrl+d, autocomplete with tab
More help at https://github.com/bhauman/rebel-readline
[Rebel readline] Type :repl/help for online help info
user=> (require 'app)
nil
user=> (app/my-fn 1 2 :a 3)
[1 2 {:a 3}]
```


## Installation

To install Invoker you will need:
- Clojure 1.12+: https://clojure.org/guides/install_clojure
- Babashka 1.12+: https://github.com/babashka/babashka#installation
- bbin 0.2.3+: https://github.com/babashka/bbin#installation

Then run `bbin install io.github.filipesilva/invoker` to install Invoker as `nvk`.
Run the same command again to update.
Uninstall with `bbin uninstall nvk`.

You can install a custom Invoker by cloning this repo and running `bbin install .`.

Now you should have a `nvk` binary in your CLI.
Calling it with no arguments shows help.


## Content Negotiation

Invoker supports content negotiation via `--content-type`, `--accept`, and `--ext` options.
`--ext` will set both `--content-type` and `--accept` based on a file extension.

In the CLI, `--content-type` applies to the last non-option argument.
Piping in data through stdin will read it as the last non-option argument.

``` sh
$ nvk --accept application/json app my-fn 1 2
[ 1, 2, null ]
$ nvk --content-type application/json app my-fn 1 2 '{"a":3}'
[1 2 {:a 3}]
$ nvk --ext .json app my-fn 1 2 '{"a":3}'
[ 1, 2, {
  "a" : 3
} ]
echo '{"a":3}' | nvk --ext .json app my-fn 1 2
[ 1, 2, {
  "a" : 3
} ]
```

In HTTP calls you append the extension to the URL:
``` sh
$ curl localhost/app/my-fn/1/2 -H "Accept: application/json"
[ 1, 2, null ]
$ curl localhost/app/my-fn/1/2 -d '{"a": 3}' -H "Content-Type: application/json"
[1 2 {:a 3}]
$ curl localhost/app/my-fn/1/2.json -d '{"a": 3}'
[ 1, 2, {
  "a" : 3
} ]
```

Invoker supports the following MIME types out of the box:
- `application/edn` as `.edn`
- `application/json` as `.json`
- `application/yaml` as `.yaml`
- `text/html` as `.html`
- `text/plain` as `.txt`

HTML will be rendered from [Hiccup](https://github.com/weavejester/hiccup).

``` clojure
(defn index
  {:invoker/http true}
  []
  [:h1 "Hello World!"])
```

``` sh
$ curl localhost/app/index
[:h1 "Hello World!"]
$ curl localhost/app/index.html
<h1>Hello World!</h1>
```

You can add a content-negotiated pre-render step to functions via metadata.
This allows you to return objects for API consumers, and HTML for browser consumers.

``` clojure
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
```

``` sh
$ curl localhost/app/todo
{:id 42, :done false, :content "foo the bar"}
$ curl localhost/app/todo.html
<div><h1>foo the bar<input type="checkbox" /></h1></div>
```


## Tests

Run tests in `test/app_test.clj` using [`clojure+.test`](https://github.com/tonsky/clojure-plus#clojuretest), reloading changed files:

``` clojure
(ns app-test
  (:require
   [clojure.test :refer [deftest is]]
   [app :as app]))

(deftest my-fn-test
  (is (= [1 2 {:a 3}] (app/my-fn 1 2 :a 3))))
```

``` sh
$ nvk test
Reloading 0 namespaces...
Reloaded 0 namespaces in 1 ms
1/1 Testing app-test... 0 ms
╶───╴
Ran 1 tests containing 1 assertions in 0 ms.
0 failures, 0 errors.
{:test 1, :pass 1, :fail 0, :error 0}
```

You can target the namespace a single namespace (`nvk test app-test`), or a single test (`nvk test app-test/my-fn-test`), or select tests with `^:only` metadata.


## Devtools

Invoker will setup the following developer tools when creating a new process:

- [`clojure+.print`](https://github.com/tonsky/clojure-plus#clojuretest) and [`clojure+.error`](https://github.com/tonsky/clojure-plus#clojureerror) improve printing of values and errors
- [`clojure+.test`](https://github.com/tonsky/clojure-plus#clojuretest) installs the test runner
- [`clojure+.hashp`](https://github.com/tonsky/clojure-plus#clojurehasp) allows you to print pretty much anything by putting `#p` before the expression, including in threading macros
- [`clj-reload`](https://github.com/tonsky/clj-reload) tracks and reloads changed namespaces


## Helper Commands

Invoker comes with a set of helper commands in `invoker.cli`, which is configured to be the default namespace:

``` sh
nvk reload              # Reload changed namespaces
nvk reload :all         # Reload all namespaces
nvk dir app             # List public vars in ns
nvk source app/my-fn    # Source code for var
nvk doc app/my-fn       # Print var docstring
nvk find-doc My doc     # Find docs containing text
nvk apropos my-f        # Find vars containing text
nvk add-lib babashka/fs # Add dependency by name (Clojure only)
nvk sync-deps           # Sync process to deps.edn (Clojure only)
nvk devtools            # Call devtools var
nvk restart             # Call stop then start vars
nvk clojuredocs q       # Search ClojureDocs for q
nvk exit 1              # Exit the process with exit-code
```

Like all other `nvk` commands, they will connect to an existing nREPL if available.


## Configuration

You can configure `nvk` commands by passing options before the command:

```
Usage: nvk <options>* <command> <args>*

       --help                                  Show doc for var
       --version                               Show version
  -c,  --config       nvk.edn                  Invoker defaults config file
  -e,  --ext                                   Extension shorthand (.edn/.json/.yaml/.html/.txt) for content-type/accept MIME types
  -ct, --content-type                          MIME type for body (last arg or piped input) on CLI content negotiation
  -ac, --accept       application/edn          MIME types accepted on CLI content negotiation, use with :invoker/render metadata
       --extensions   invoker.utils/extensions Map of extension to MIME type
       --parse        invoker.utils/parse      Map of MIME type to parsing fn
       --render       invoker.utils/render     Map of MIME type to rendering fn
  -d,  --dialect      :bb                      Clojure (clj) or Babashka (bb), defaults to clj if there's a deps.edn
       --devtools     invoker.utils/devtools   Developer tools fn to call on process setup or nvk devtools
  -r,  --reload                                Reload changed files before invoking fn via CLI
       --start                                 Start fn to call on process setup or nvk restart
       --stop                                  Stop fn to call on process setup or nvk restart
  -nd, --ns-default   invoker.cli              Default namespace for var resolution
  -na, --ns-aliases                            Map of alias to namespace for var resolution
  -ha, --http-all     false                    Expose vars without :invoker/http in the HTTP server
  -hp, --http-port    80                       Port for HTTP server, written to .http-port
  -hh, --http-handler invoker.http/handler     Ring handler fn for HTTP server
  -rp, --repl-port    0                        Port for nREPL server creation, 0 for random
  -rc, --repl-connect                          nREPL server address to connect on, defaults to content of .nrepl-port file if present and port is taken
  -a,  --aliases                               Aliases to call Clojure with, does nothing with Babashka
  -et, --ex-trace     false                    Include stack trace on exception
```

You can set custom defaults for options in `nvk.edn`:

``` clojure
{:http-port 8080
 :aliases   ":dev"}
```

The `extensions`, `parse`, `render`, `devtools`, `start`, `stop`, `ns-default`, `ns-aliases`, `http-handler` options take symbols that will be resolved at in your codebase, allowing you to customize `nvk` behaviour with your own code.


## Datomic

You can use the `--start` option together with [dpm](https://github.com/filipesilva/datomic-pro-manager) to launch a Datomic database and wait for it to be up on startup.

First add these dependencies:

``` sh
$ nvk add-lib io.github.filipesilva/datomic-pro-manager
$ nvk add-lib com.datomic/peer
$ nvk add-lib org.xerial/sqlite-jdbc
$ nvk add-lib nvk add-lib org.slf4j/slf4j-nop
```

In your `src/app.clj` add:

``` clojure
(ns app
  (:require
   [datomic.api :as d]
   [filipesilva.datomic-pro-manager :as dpm]))

(def db-uri "datomic:sql://app?jdbc:sqlite:./storage/sqlite.db")
(defonce *conn (atom nil))

(defn start []
  (future (dpm/up))
  (dpm/wait-for-up)
  (d/create-database db-uri)
  (reset! *conn (d/connect db-uri)))

(defn db-stats
  {:invoker/http true}
  []
  (d/db-stats (d/db @*conn)))
```

Then set the `:start` option on `nvk.edn` so you don't have to write `nvk --start app/start http` all the time:

``` clojure
{:start app/start}
```

Now when you start a nREPL server via `nvk http` or `nvk repl` you should see datomic starting up too:

``` sh
$ nvk http
Started nREPL server at localhost:60250
Started HTTP server at http://localhost
SLF4J(I): Connected with provider of type [org.slf4j.nop.NOPServiceProvider]
info  Waiting for datomic to be up...
info  Starting Datomic
run   ./datomic-pro/1.0.7482/bin/transactor ./config/transactor.properties
Launching with Java options -server -Xms1g -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=50
System started
info  Datomic is up!
```

``` sh
$ curl localhost/app/db-stats
{:datoms 268,
 :attrs
 {:db/index {:count 2},
  :db/unique {:count 1},
  :db/valueType {:count 33},
  :db/txInstant {:count 6},
  :db/tupleType {:count 2},
  :db/lang {:count 2},
  :db/fulltext {:count 2},
  :db/cardinality {:count 33},
  :db/doc {:count 48},
  :db/ident {:count 67},
  :db/code {:count 2},
  :db.install/valueType {:count 16},
  :db.install/function {:count 2},
  :db.install/partition {:count 3},
  :db.install/attribute {:count 33},
  :fressian/tag {:count 16}}}
```

If you're running Datomic as a separate process (you should, for any serious stuff), remove `(future (dpm/up))` from `start` to just wait. You can use `dpm up` to start it if you have [dpm](https://github.com/filipesilva/datomic-pro-manager) installed globally.


## Claude SKILL.md

You can output this README.md with Claude skill metadata using `nvk --skill`.

Then you can output it to your global skills directory to teach Claude Code how to use `nvk`:

``` clojure
$ mkdir -p ~/.claude/skills/nvk
$ nvk --skill > ~/.claude/skills/nvk/SKILL.md
```

Now you should be able to prompt something like `make a basic blog using nvk and datomic` and it should just work.
